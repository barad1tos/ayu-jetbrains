package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.AcceptedAppearance
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.CatalogIntent
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.ProfileReference
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.StudioFieldCatalog
import dev.ayuislands.studio.model.StudioFieldOwner
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.requireKnownType

internal object StudioSessionValidation {
    fun validate(session: StudioSessionRecord) {
        require(session.id.isNotBlank() && session.generation >= 0 && session.baseDocumentRevision >= 0)
        (session.target as? StudioTarget.Project)?.let { require(it.key.value.isNotBlank()) }
        session.work.drafts.forEach(::validateDraft)
        validateShared(session.work.shared)
        session.work.catalog.forEach(::validateCatalog)
    }

    private fun validateDraft(draft: DraftRecord) {
        require(draft.id.value.isNotBlank())
        draft.source?.let(::validateReference)
        validateAccepted(draft.base)
        validateAddresses(draft.appearance, draft.tracking.touched)
        validateAddresses(draft.appearance, draft.tracking.reapply)
    }

    fun validateAddresses(
        appearance: AppearanceSnapshot,
        addresses: List<FieldAddress>,
        shared: List<StudioFields> = emptyList(),
    ) {
        require(addresses.distinct().size == addresses.size) { "Duplicate field address" }
        val domains = listOf(appearance.accent, appearance.font, appearance.glow, appearance.syntax, appearance.vcs)
        val fields = (domains + shared).flatMap { it.records }.associateBy { it.key }
        for ((key, part) in addresses) {
            val field = requireNotNull(fields[key]) { "Unknown field address" }
            require(part == FieldPart.Value || field.value is RawValue.OrderedMap) { "Map address on scalar field" }
        }
    }

    fun validateAccepted(current: AcceptedAppearance) {
        require(current.revision >= 0)
        current.source?.let(::validateReference)
    }

    private fun validateReference(reference: ProfileReference) {
        require(reference.id.value.isNotBlank() && reference.revision >= 0)
    }

    fun validateShared(intents: List<SharedIntent>) {
        val fields = intents.filterIsInstance<SharedIntent.Field>()
        require(fields.map { it.before.key }.distinct().size == fields.size) { "Duplicate shared field intent" }
        require(intents.count { it is SharedIntent.SystemPolicy } <= 1) { "Duplicate system policy intent" }
        for ((before, after) in fields) {
            val key = before.key
            val definition =
                requireNotNull(
                    StudioFieldCatalog.fields.singleOrNull {
                        it.source == key.source &&
                            it.name == key.name
                    },
                )
            require(
                definition.owner in
                    setOf(
                        StudioFieldOwner.SYSTEM,
                        StudioFieldOwner.MAPPINGS,
                        StudioFieldOwner.WORKSPACE,
                        StudioFieldOwner.PLUGINS,
                    ),
            ) { "Appearance and auxiliary values are not shared preferences" }
            before.requireKnownType()
            after.requireKnownType()
        }
    }

    private fun validateCatalog(intent: CatalogIntent) {
        when (intent) {
            is CatalogIntent.Create -> validateUser(intent.definition)
            is CatalogIntent.Update -> {
                validateUser(intent.before)
                validateUser(intent.after)
                require(
                    intent.after.revision == intent.before.revision + 1,
                ) { "Update must advance its captured revision" }
            }
            is CatalogIntent.Rename -> validateUser(intent.before)
            is CatalogIntent.Delete -> validateUser(intent.before)
        }
    }

    private fun validateUser(profile: ProfileDefinition.User) {
        require(profile.id.value.isNotBlank() && !profile.id.value.startsWith("factory:"))
        require(profile.revision >= 0)
    }
}
