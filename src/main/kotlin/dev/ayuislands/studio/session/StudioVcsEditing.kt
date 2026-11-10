package dev.ayuislands.studio.session

import dev.ayuislands.settings.VcsPanelEvent
import dev.ayuislands.settings.VcsSection
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.studioVcsFields
import dev.ayuislands.vcs.VcsColorCategory
import dev.ayuislands.vcs.VcsColorPreset

/** Produces exact edits for the existing five controls without rewriting hidden VCS memories. */
internal object StudioVcsEditing {
    fun edits(
        draft: DraftRecord,
        event: VcsPanelEvent,
    ): List<SessionIntent> =
        changes(event).map { (name, value) ->
            val field =
                draft.appearance.vcs.records
                    .single { it.key.name == name }
            SessionIntent.EditField(draft.id, FieldAddress(field.key), FieldValue.Present(value))
        }

    private fun changes(event: VcsPanelEvent): Map<String, RawValue> =
        when (event) {
            is VcsPanelEvent.Enabled -> {
                mapOf("vcsColorEnabled" to RawValue.BooleanValue(event.value))
            }

            is VcsPanelEvent.Preset -> {
                buildMap {
                    put(presetField(event.section), RawValue.Text(event.value.name))
                    if (event.value != VcsColorPreset.CUSTOM) {
                        for ((category, _) in event.section.sliders) {
                            put(intensityField(category), RawValue.Integer(event.value.intensityFor(category)))
                        }
                    }
                }
            }

            is VcsPanelEvent.Intensity -> {
                val section =
                    VcsSection.entries.singleOrNull { group ->
                        group.sliders.any { it.first == event.category }
                    }
                if (section == null) {
                    emptyMap()
                } else {
                    mapOf(
                        intensityField(event.category) to RawValue.Integer(event.value),
                        presetField(section) to RawValue.Text(VcsColorPreset.CUSTOM.name),
                    )
                }
            }

            is VcsPanelEvent.Expanded, VcsPanelEvent.Unlock -> {
                emptyMap()
            }
        }

    private fun intensityField(category: VcsColorCategory): String =
        studioVcsFields.entries.single { it.value == category }.key

    private fun presetField(section: VcsSection): String =
        when (section) {
            VcsSection.DIFF -> "vcsDiffPreset"
            VcsSection.MERGE -> "vcsMergePreset"
            VcsSection.BLAME -> "vcsBlamePreset"
        }
}
