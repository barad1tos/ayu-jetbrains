package dev.ayuislands.studio.session

import dev.ayuislands.settings.VcsSection
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.studioVcsFields
import dev.ayuislands.vcs.VcsColorCategory
import dev.ayuislands.vcs.VcsColorRules
import dev.ayuislands.vcs.VcsColorSnapshot

/** Requested values include unavailable preset names and dormant category values. */
internal data class StudioVcsSettings(
    val enabled: Boolean,
    val presets: Map<VcsSection, String?>,
    val intensities: Map<VcsColorCategory, Int>,
)

internal object StudioVcsProjection {
    fun settings(appearance: AppearanceSnapshot): StudioVcsSettings {
        val fields = appearance.vcs.records.associate { it.key to it.value }
        val presets =
            VcsSection.entries.associateWith { section ->
                when (val value = fields.getValue(section.presetField)) {
                    RawValue.Null -> null
                    else -> (value as RawValue.Text).value
                }
            }
        val intensities =
            studioVcsFields.entries.associate { (name, category) ->
                category to (fields.getValue(vcsField(name)) as RawValue.Integer).value
            }
        return StudioVcsSettings(
            enabled = (fields.getValue(VCS_ENABLED_FIELD) as RawValue.BooleanValue).value,
            presets = presets,
            intensities = intensities,
        )
    }

    fun snapshot(appearance: AppearanceSnapshot): VcsColorSnapshot {
        val requested = settings(appearance)
        val effective =
            requested.intensities.mapValues { (category, raw) ->
                val preset =
                    VcsColorRules.preset(
                        category,
                        requested.presets.getValue(VcsSection.DIFF),
                        requested.presets.getValue(VcsSection.MERGE),
                        requested.presets.getValue(VcsSection.BLAME),
                    )
                VcsColorRules.intensity(requested.enabled, category, preset) { raw }.percent
            }
        return VcsColorSnapshot(requested.enabled, effective)
    }
}

internal val VCS_ENABLED_FIELD = vcsField("vcsColorEnabled")

internal val VcsSection.presetField: LegacyFieldKey
    get() =
        when (this) {
            VcsSection.DIFF -> vcsField("vcsDiffPreset")
            VcsSection.MERGE -> vcsField("vcsMergePreset")
            VcsSection.BLAME -> vcsField("vcsBlamePreset")
        }

internal fun vcsField(name: String): LegacyFieldKey = LegacyFieldKey(LegacyStore.SETTINGS, name)
