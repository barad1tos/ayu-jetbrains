package dev.ayuislands.studio.profiles

import dev.ayuislands.font.FontPreset
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.studio.migration.StudioFieldMapping
import dev.ayuislands.studio.model.FactoryRecipe
import dev.ayuislands.studio.model.FieldOrigin
import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SourcePresence
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.studioVcsFields
import dev.ayuislands.syntax.SyntaxPreset
import dev.ayuislands.vcs.VcsColorPreset

internal object StudioFactoryProfiles {
    fun create(fresh: StudioFields): StudioList<ProfileDefinition.Factory> =
        StudioList(
            FactoryRecipe.entries.map { recipe ->
                val changes = overrides(recipe)
                val records =
                    fresh.records.map { field ->
                        val replacement = changes[field.key.name]
                        if (replacement ==
                            null
                        ) {
                            field
                        } else {
                            field.copy(value = replacement, origin = FieldOrigin(SourcePresence.VALUE))
                        }
                    }
                ProfileDefinition.Factory(
                    recipe.id,
                    FontPreset.valueOf(recipe.name).displayName,
                    StudioFieldMapping.appearance(StudioFields(StudioList(records))),
                    recipeVersion = 1,
                )
            },
        )

    private fun overrides(recipe: FactoryRecipe): Map<String, RawValue> {
        val glow = GlowPreset.valueOf(recipe.name)
        val style = requireNotNull(glow.style)
        val slot =
            when (style) {
                GlowStyle.SOFT -> "soft"
                GlowStyle.SHARP_NEON -> "sharpNeon"
                GlowStyle.GRADIENT -> "gradient"
            }
        val vcs = VcsColorPreset.valueOf(recipe.name)
        return buildMap {
            for (variant in listOf(
                "mirageAccent",
                "darkAccent",
                "lightAccent",
            )) {
                put(variant, RawValue.Text(recipe.accent))
            }
            put("followSystemAccent", RawValue.BooleanValue(false))
            put("accentRotationEnabled", RawValue.BooleanValue(false))
            put("fontPresetEnabled", RawValue.BooleanValue(true))
            put("fontPresetName", RawValue.Text(FontPreset.valueOf(recipe.name).name))
            put("glowEnabled", RawValue.BooleanValue(true))
            put("glowPreset", RawValue.Text(glow.name))
            put("glowStyle", RawValue.Text(style.name))
            put("glowAnimation", RawValue.Text(requireNotNull(glow.animation).name))
            put("${slot}Intensity", RawValue.Integer(requireNotNull(glow.intensity)))
            put("${slot}Width", RawValue.Integer(requireNotNull(glow.width)))
            put("selectedPreset", RawValue.Text(SyntaxPreset.valueOf(recipe.name).name))
            put("subordinatePreset", RawValue.Text(SyntaxPreset.valueOf(recipe.name).name))
            put("vcsColorEnabled", RawValue.BooleanValue(true))
            for (group in listOf(
                "vcsDiffPreset",
                "vcsMergePreset",
                "vcsBlamePreset",
            )) {
                put(group, RawValue.Text(vcs.name))
            }
            for ((field, category) in studioVcsFields) put(field, RawValue.Integer(vcs.intensityFor(category)))
        }
    }
}
