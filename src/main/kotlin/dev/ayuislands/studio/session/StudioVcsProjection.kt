package dev.ayuislands.studio.session

import dev.ayuislands.settings.VcsPanelSettings
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.studioVcsFields
import dev.ayuislands.vcs.VcsColorRules
import dev.ayuislands.vcs.VcsColorSnapshot

/** Projects VCS appearance choices into the existing runtime snapshot. */
internal object StudioVcsProjection {
    fun snapshot(appearance: AppearanceSnapshot): VcsColorSnapshot {
        val requested = settings(appearance)
        return VcsColorSnapshot(
            requested.enabled,
            requested.intensities.mapValues { (category, raw) ->
                val preset =
                    VcsColorRules.preset(
                        category,
                        requested.diffPreset,
                        requested.mergePreset,
                        requested.blamePreset,
                    )
                VcsColorRules.intensity(requested.enabled, category, preset, raw).percent
            },
        )
    }

    /** Reads requested raw choices without clamping, resolving presets or rewriting unknown values. */
    fun settings(appearance: AppearanceSnapshot): VcsPanelSettings {
        val fields = appearance.vcs.records.associate { it.key.name to it.value }
        return VcsPanelSettings(
            enabled = requireNotNull(fields.getValue("vcsColorEnabled") as? RawValue.BooleanValue).value,
            diffPreset = text(fields.getValue("vcsDiffPreset")),
            mergePreset = text(fields.getValue("vcsMergePreset")),
            blamePreset = text(fields.getValue("vcsBlamePreset")),
            intensities =
                studioVcsFields.entries.associate { (name, category) ->
                    category to
                        integer(fields.getValue(name))
                },
        )
    }

    private fun text(raw: RawValue): String? =
        if (raw == RawValue.Null) null else requireNotNull(raw as? RawValue.Text).value

    private fun integer(raw: RawValue): Int = requireNotNull(raw as? RawValue.Integer).value
}
