package dev.ayuislands.studio.session

import dev.ayuislands.studio.fields.StudioAppearanceFields
import dev.ayuislands.studio.fields.StudioFieldValues
import dev.ayuislands.studio.model.AcceptedAppearance
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.CatalogIntent
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.StudioFieldCatalog
import dev.ayuislands.studio.model.StudioFieldOwner
import dev.ayuislands.studio.model.StudioSessionRecord

internal object StudioSessionValidation {
    fun validate(session: StudioSessionRecord) {
        require(session.id.isNotBlank() && session.generation >= 0 && session.baseDocumentRevision >= 0)
        require(
            session.work.drafts
                .map { it.id }
                .distinct()
                .size == session.work.drafts.size,
        )
        require(session.work.drafts.any { it.id == session.work.selected })
        session.work.drafts.forEach(::validateDraft)
        validateShared(session.work.shared)
        session.work.catalog.forEach(::validateCatalog)
    }

    private fun validateDraft(draft: DraftRecord) {
        require(draft.id.value.isNotBlank())
        validateAccepted(draft.base)
        StudioAppearanceFields.fields(draft.appearance).forEach(StudioFieldValues::validateField)
        StudioAppearanceFields.fields(draft.tracking.initial).forEach(StudioFieldValues::validateField)
        validateAddresses(draft.appearance, draft.tracking.touched)
        validateAddresses(draft.appearance, draft.tracking.reapply)
    }

    fun validateAddresses(
        appearance: AppearanceSnapshot,
        addresses: List<FieldAddress>,
    ) {
        require(addresses.distinct().size == addresses.size) { "Duplicate field address" }
        val fields = StudioAppearanceFields.fields(appearance)
        for ((key, part) in addresses) {
            val field = requireNotNull(fields.singleOrNull { it.key == key }) { "Unknown appearance address" }
            require(part == FieldPart.Value || field.value is RawValue.OrderedMap) { "Map address on scalar field" }
        }
    }

    fun validateAccepted(current: AcceptedAppearance) {
        require(current.revision >= 0)
        StudioAppearanceFields.fields(current.appearance).forEach(StudioFieldValues::validateField)
        current.source?.let { reference -> require(reference.id.value.isNotBlank() && reference.revision >= 0) }
    }

    fun validateShared(intents: List<SharedIntent>) {
        val fields = intents.filterIsInstance<SharedIntent.Field>()
        require(fields.map { it.before.key }.distinct().size == fields.size) { "Duplicate shared field intent" }
        require(intents.count { it is SharedIntent.SystemPolicy } <= 1) { "Duplicate system policy intent" }
        for ((before, after) in fields) {
            require(before.key == after.key) { "Shared field identity cannot change" }
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
            StudioFieldValues.validateField(before)
            StudioFieldValues.validateField(after)
        }
    }

    private fun validateCatalog(intent: CatalogIntent) {
        when (intent) {
            is CatalogIntent.Create -> {
                validateUser(intent.definition)
                require(intent.draft.value.isNotBlank())
            }
            is CatalogIntent.Update -> {
                validateUser(intent.before)
                validateUser(intent.after)
                require(intent.before.id == intent.after.id) { "Update cannot change profile identity" }
                require(
                    intent.after.revision == intent.before.revision + 1,
                ) { "Update must advance its captured revision" }
                require(intent.draft.value.isNotBlank())
            }
            is CatalogIntent.Rename -> validateUser(intent.before)
            is CatalogIntent.Delete -> validateUser(intent.before)
        }
    }

    private fun validateUser(profile: ProfileDefinition.User) {
        require(profile.id.value.isNotBlank() && !profile.id.value.startsWith("factory:"))
        require(profile.revision >= 0)
        StudioAppearanceFields.fields(profile.appearance).forEach(StudioFieldValues::validateField)
    }
}
