package dev.ayuislands.studio.session

import dev.ayuislands.settings.VcsSection
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioVcsEdit
import dev.ayuislands.studio.model.studioVcsFields
import dev.ayuislands.vcs.VcsColorCategory
import dev.ayuislands.vcs.VcsColorPreset

/** Converts an explicit VCS action into edits without changing other groups or hidden values. */
internal object StudioVcsEditing {
    fun edit(
        draft: DraftRecord,
        edit: StudioVcsEdit,
    ): DraftRecord =
        changes(edit).entries.fold(draft) { pending, (field, value) ->
            StudioDraftPolicy.edit(pending, FieldAddress(field), FieldValue.Present(value))
        }

    private fun changes(edit: StudioVcsEdit): Map<LegacyFieldKey, RawValue> =
        when (edit) {
            is StudioVcsEdit.Enable -> mapOf(VCS_ENABLED_FIELD to RawValue.BooleanValue(edit.enabled))
            is StudioVcsEdit.Preset -> presetValues(edit.section, edit.value)
            is StudioVcsEdit.Intensity -> intensityValues(edit)
        }

    private fun presetValues(
        section: VcsSection,
        preset: VcsColorPreset,
    ): Map<LegacyFieldKey, RawValue> =
        buildMap {
            put(section.presetField, RawValue.Text(preset.name))
            if (preset != VcsColorPreset.CUSTOM) {
                for ((category, _) in section.sliders) {
                    put(intensityField(category), RawValue.Integer(preset.intensityFor(category)))
                }
            }
        }

    private fun intensityValues(edit: StudioVcsEdit.Intensity): Map<LegacyFieldKey, RawValue> {
        val section =
            VcsSection.entries.firstOrNull { group -> group.sliders.any { it.first == edit.category } }
                ?: return emptyMap()
        return mapOf(
            intensityField(edit.category) to RawValue.Integer(edit.value.percent),
            section.presetField to RawValue.Text(VcsColorPreset.CUSTOM.name),
        )
    }

    private fun intensityField(category: VcsColorCategory): LegacyFieldKey =
        vcsField(studioVcsFields.entries.single { it.value == category }.key)
}
