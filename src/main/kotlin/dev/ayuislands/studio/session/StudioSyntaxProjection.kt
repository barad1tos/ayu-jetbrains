package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioSyntaxReadability
import dev.ayuislands.syntax.SyntaxPresetConfig
import dev.ayuislands.syntax.SyntaxReadabilityOptions
import dev.ayuislands.syntax.SyntaxSettings

internal object StudioSyntaxProjection {
    fun config(appearance: AppearanceSnapshot): SyntaxPresetConfig = settings(appearance).toPresetConfig()

    fun settings(appearance: AppearanceSnapshot): SyntaxSettings {
        val fields = appearance.syntax.records.associate { it.key to it.value }

        fun enabled(option: StudioSyntaxReadability): Boolean =
            (fields.getValue(option.field) as RawValue.BooleanValue).value

        return SyntaxSettings(
            selectedPreset = presetName(fields.getValue(StudioSyntaxFields.selectedPreset)),
            subordinatePreset = presetName(fields.getValue(StudioSyntaxFields.subordinatePreset)),
            customOverrides = encodedCells(fields.getValue(StudioSyntaxFields.customOverrides)),
            customStyles = encodedCells(fields.getValue(StudioSyntaxFields.customStyles)),
            customEmphasis = encodedCells(fields.getValue(StudioSyntaxFields.customEmphasis)),
            readability =
                SyntaxReadabilityOptions(
                    dimComments = enabled(StudioSyntaxReadability.DIM_COMMENTS),
                    softenDocumentation = enabled(StudioSyntaxReadability.SOFTEN_DOCUMENTATION),
                    quietOperators = enabled(StudioSyntaxReadability.QUIET_OPERATORS),
                    emphasizeDeclarations = enabled(StudioSyntaxReadability.EMPHASIZE_DECLARATIONS),
                ),
        )
    }

    private fun presetName(value: RawValue): String? =
        if (value == RawValue.Null) null else (value as RawValue.Text).value

    private fun encodedCells(value: RawValue): Map<String, String> =
        (value as RawValue.OrderedMap).entries.associate { (key, raw) -> key to (raw as RawValue.Text).value }
}

internal object StudioSyntaxFields {
    val selectedPreset = LegacyFieldKey(LegacyStore.SYNTAX, "selectedPreset")
    val subordinatePreset = LegacyFieldKey(LegacyStore.SYNTAX, "subordinatePreset")
    val customOverrides = LegacyFieldKey(LegacyStore.SYNTAX, "customOverrides")
    val customStyles = LegacyFieldKey(LegacyStore.SYNTAX, "customStyles")
    val customEmphasis = LegacyFieldKey(LegacyStore.SYNTAX, "customEmphasis")
}
