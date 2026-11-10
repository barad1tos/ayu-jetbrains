package dev.ayuislands.studio.session

import dev.ayuislands.studio.fields.StudioFieldEdits
import dev.ayuislands.studio.fields.StudioFieldValues
import dev.ayuislands.studio.model.AcceptanceOrigin
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioTarget

/** An extracted icon is an if-absent suggestion, never permission to replace a later user choice. */
internal object StudioIconPinPolicy {
    fun accepts(
        request: AcceptanceRequest,
        latest: StudioDocument,
    ): Boolean {
        val key = request.iconPin ?: return true
        if (request.origin != AcceptanceOrigin.QUICK_SWITCHER ||
            request.session.target != StudioTarget.Project(key)
        ) {
            return false
        }
        val enabled =
            latest.shared.system.records
                .single { it.key.name == "projectIconAccentEnabled" }
        if (enabled.value != RawValue.BooleanValue(true)) return false
        val pins =
            latest.shared.mappings.records
                .single { it.key.name == "projectAccents" }
                .value as RawValue.OrderedMap
        if (pins.entries.any { it.key == key.value }) return false
        val work = request.session.work
        if (work.catalog.isNotEmpty() || work.drafts.any { StudioDraftPolicy.isDirty(it) }) return false
        val edits = work.shared.filterIsInstance<SharedIntent.Field>()
        if (edits.size != work.shared.size || edits.none { it.before.key.name == "projectAccents" }) return false
        val address = FieldPart.MapEntry(key.value)
        return edits.all { editsEntry(it, address) }
    }

    private fun editsEntry(
        edit: SharedIntent.Field,
        address: FieldPart.MapEntry,
    ): Boolean {
        val before = edit.before
        if (before.key.source != LegacyStore.MAPPINGS ||
            before.key.name !in setOf("projectAccents", "projectDisplayNames")
        ) {
            return false
        }
        val requested = StudioFieldValues.value(edit.after, address)
        return requested is FieldValue.Present && StudioFieldEdits.edit(before, address, requested) == edit.after
    }
}
