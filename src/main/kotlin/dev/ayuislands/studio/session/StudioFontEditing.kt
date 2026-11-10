package dev.ayuislands.studio.session

import dev.ayuislands.font.FontPanelEvent
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontSettingSlot
import dev.ayuislands.font.FontSettings
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.StudioList

/** Maps explicit Font edits to existing session commands without owning a second working copy. */
internal object StudioFontEditing {
    fun edits(
        draft: DraftRecord,
        event: FontPanelEvent,
    ): List<SessionIntent> =
        when (event) {
            FontPanelEvent.Reset -> defaults(draft)
            FontPanelEvent.Reapply -> reapply(draft)
            else -> listOfNotNull(fieldEdit(draft, event))
        }

    private fun fieldEdit(
        draft: DraftRecord,
        event: FontPanelEvent,
    ): SessionIntent.EditField? =
        when (event) {
            is FontPanelEvent.Enable -> editScalar(draft, "fontPresetEnabled", RawValue.BooleanValue(event.value))
            is FontPanelEvent.Preset -> editScalar(draft, "fontPresetName", RawValue.Text(event.value.name))
            is FontPanelEvent.Console -> editScalar(draft, "fontApplyToConsole", RawValue.BooleanValue(event.value))
            else -> editTypography(draft, event)
        }

    private fun editTypography(
        draft: DraftRecord,
        event: FontPanelEvent,
    ): SessionIntent.EditField? {
        val settings = StudioFontProjection.selection(draft.appearance).settings ?: return null
        if (event is FontPanelEvent.Family && settings.preset.isCurated) return null
        val change = slotChange(event) ?: return null
        val encoded = readCustomization(draft, settings.preset)
        return editEntry(draft, settings.preset, replaceSlots(settings.preset, encoded, listOf(change)))
    }

    private fun replaceSlots(
        preset: FontPreset,
        encoded: String?,
        changes: List<Pair<FontSettingSlot, String>>,
    ): String {
        val defaults = FontSettings.fromPreset(preset).encode().split('|')
        val parts = (encoded?.split('|') ?: defaults).toMutableList()
        for ((slot, value) in changes) {
            while (parts.size <= slot.index) parts.add(defaults[parts.size])
            parts[slot.index] = value
        }
        return parts.joinToString("|")
    }

    private fun slotChange(event: FontPanelEvent): Pair<FontSettingSlot, String>? =
        when (event) {
            is FontPanelEvent.Size -> FontSettingSlot.SIZE to encodeDimension(event.value)
            is FontPanelEvent.Spacing -> FontSettingSlot.SPACING to encodeDimension(event.value)
            is FontPanelEvent.Ligatures -> FontSettingSlot.LIGATURES to event.value.toString()
            is FontPanelEvent.Weight -> FontSettingSlot.WEIGHT to event.value.name
            is FontPanelEvent.Family -> FontSettingSlot.FAMILY to event.value
            else -> null
        }

    private fun encodeDimension(value: Float): String {
        require(value.isFinite() && value > 0) { "Font dimensions must be finite and positive" }
        return value.toString()
    }

    private fun defaults(draft: DraftRecord): List<SessionIntent> =
        buildList {
            add(editScalar(draft, "fontPresetName", RawValue.Text(FontPreset.AMBIENT.name)))
            add(editScalar(draft, "fontApplyToConsole", RawValue.BooleanValue(false)))
            for (preset in FontPreset.entries) {
                val original = readCustomization(draft, preset) ?: continue
                val encoded = FontSettings.fromPreset(preset).encode()
                if (FontSettings.decode(original, preset).encode() != encoded) {
                    val parts = encoded.split('|')
                    val changes = FontSettingSlot.entries.filter { it.index < parts.size }.map { it to parts[it.index] }
                    add(editEntry(draft, preset, replaceSlots(preset, original, changes)))
                }
            }
        }

    private fun reapply(draft: DraftRecord): List<SessionIntent> {
        val enabled =
            draft.appearance.font.records
                .single { it.key.name == "fontPresetEnabled" }
                .value
        if (enabled != RawValue.BooleanValue(true) ||
            StudioFontProjection.selection(draft.appearance).settings == null
        ) {
            return emptyList()
        }
        return listOf(
            SessionIntent.Reapply(
                draft.id,
                StudioList(
                    draft.appearance.font.records
                        .map { FieldAddress(it.key) },
                ),
            ),
        )
    }

    private fun editScalar(
        draft: DraftRecord,
        name: String,
        value: RawValue,
    ): SessionIntent.EditField =
        SessionIntent.EditField(
            draft.id,
            FieldAddress(
                draft.appearance.font.records
                    .single { it.key.name == name }
                    .key,
            ),
            FieldValue.Present(value),
        )

    private fun editEntry(
        draft: DraftRecord,
        preset: FontPreset,
        encoded: String,
    ): SessionIntent.EditField =
        SessionIntent.EditField(
            draft.id,
            FieldAddress(
                draft.appearance.font.records
                    .single { it.key.name == "fontPresetCustomizations" }
                    .key,
                FieldPart.MapEntry(preset.name),
            ),
            FieldValue.Present(RawValue.Text(encoded)),
        )

    private fun readCustomization(
        draft: DraftRecord,
        preset: FontPreset,
    ): String? {
        val field =
            draft.appearance.font.records
                .single { it.key.name == "fontPresetCustomizations" }
        val value = (field.value as RawValue.OrderedMap).entries.singleOrNull { it.key == preset.name }?.value
        return (value as? RawValue.Text)?.value
    }
}
