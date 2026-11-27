package dev.ayuislands.studio.session

import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontSettingSlot
import dev.ayuislands.font.FontSettings
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioFontEdit
import dev.ayuislands.studio.model.StudioList

internal object StudioFontEditing {
    fun edit(
        draft: DraftRecord,
        edit: StudioFontEdit,
    ): DraftRecord =
        when (edit) {
            is StudioFontEdit.Enable -> scalar(draft, StudioFontFields.enabled, RawValue.BooleanValue(edit.enabled))
            is StudioFontEdit.Preset -> scalar(draft, StudioFontFields.presetName, RawValue.Text(edit.value.name))
            is StudioFontEdit.Console -> scalar(draft, StudioFontFields.console, RawValue.BooleanValue(edit.enabled))
            is StudioFontEdit.Size -> customize(draft, FontSettingSlot.SIZE, encodeDimension(edit.value))
            is StudioFontEdit.Spacing -> customize(draft, FontSettingSlot.SPACING, encodeDimension(edit.value))
            is StudioFontEdit.Ligatures -> customize(draft, FontSettingSlot.LIGATURES, edit.enabled.toString())
            is StudioFontEdit.Weight -> customize(draft, FontSettingSlot.WEIGHT, edit.value.name)
            is StudioFontEdit.Family -> customize(draft, FontSettingSlot.FAMILY, edit.value)
            StudioFontEdit.Reset -> reset(draft)
            StudioFontEdit.Reapply -> reapply(draft)
        }

    private fun customize(
        draft: DraftRecord,
        slot: FontSettingSlot,
        value: String,
    ): DraftRecord {
        val preset = StudioFontProjection.settings(draft.appearance).resolved?.preset ?: return draft
        if (slot == FontSettingSlot.FAMILY && preset.isCurated) return draft
        val records = StudioFontProjection.customizations(draft.appearance)
        val key = preset.customizationKey(records.keys)
        val original = records[key]
        val defaults = FontSettings.fromPreset(preset).encode().split('|')
        val edited = replaceSlots(original, defaults, listOf(slot to value))
        if (FontSettings.decode(original, preset) == FontSettings.decode(edited, preset)) return draft
        return entry(draft, key, edited)
    }

    private fun reset(draft: DraftRecord): DraftRecord {
        val selected = scalar(draft, StudioFontFields.presetName, RawValue.Text(FontPreset.AMBIENT.name))
        val initial = scalar(selected, StudioFontFields.console, RawValue.BooleanValue(false))
        val records = StudioFontProjection.customizations(draft.appearance)
        return FontPreset.entries.fold(initial) { pending, preset ->
            val key = preset.customizationKey(records.keys)
            val original = records[key] ?: return@fold pending
            val defaults = FontSettings.fromPreset(preset).encode()
            if (FontSettings.decode(original, preset).encode() == defaults) return@fold pending
            val parts = defaults.split('|')
            val changes = FontSettingSlot.entries.filter { it.index < parts.size }.map { it to parts[it.index] }
            entry(pending, key, replaceSlots(original, parts, changes))
        }
    }

    private fun replaceSlots(
        original: String?,
        defaults: List<String>,
        changes: List<Pair<FontSettingSlot, String>>,
    ): String {
        val parts = (original?.split('|') ?: defaults).toMutableList()
        for ((slot, value) in changes) {
            while (parts.size <= slot.index) parts.add(defaults[parts.size])
            parts[slot.index] = value
        }
        return parts.joinToString("|")
    }

    private fun reapply(draft: DraftRecord): DraftRecord {
        val font = StudioFontProjection.settings(draft.appearance)
        val address = FieldAddress(StudioFontFields.presetName)
        if (!font.enabled || font.resolved == null || address in draft.tracking.reapply) return draft
        return draft.copy(tracking = draft.tracking.copy(reapply = StudioList(draft.tracking.reapply + address)))
    }

    private fun scalar(
        draft: DraftRecord,
        field: LegacyFieldKey,
        value: RawValue,
    ): DraftRecord = StudioDraftPolicy.edit(draft, FieldAddress(field), FieldValue.Present(value))

    private fun entry(
        draft: DraftRecord,
        key: String,
        value: String,
    ): DraftRecord =
        StudioDraftPolicy.edit(
            draft,
            FieldAddress(StudioFontFields.customizations, FieldPart.MapEntry(key)),
            FieldValue.Present(RawValue.Text(value)),
        )

    private fun encodeDimension(value: Float): String {
        require(value.isFinite() && value > 0) { "Font dimensions must be finite and positive" }
        return value.toString()
    }
}
