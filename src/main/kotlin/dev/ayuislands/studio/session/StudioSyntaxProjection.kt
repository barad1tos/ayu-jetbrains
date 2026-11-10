package dev.ayuislands.studio.session

import dev.ayuislands.settings.SyntaxPanelSettings
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.syntax.FontEmphasis
import dev.ayuislands.syntax.FontStyleOverride
import dev.ayuislands.syntax.SyntaxCellCodec
import dev.ayuislands.syntax.SyntaxPresetConfig
import dev.ayuislands.syntax.SyntaxReadabilityOptions

/** Projects raw appearance intent for the existing syntax applicator without native service reads. */
internal object StudioSyntaxProjection {
    fun config(appearance: AppearanceSnapshot): SyntaxPresetConfig {
        val requested = settings(appearance)
        return SyntaxPresetConfig(
            selectedPreset = requested.selectedPreset ?: "AMBIENT",
            subordinatePreset = requested.subordinatePreset ?: "AMBIENT",
            customOverrides = SyntaxCellCodec.decode(requested.customOverrides) { it.toIntOrNull() },
            customStyles = SyntaxCellCodec.decode(requested.customStyles) { FontStyleOverride.fromName(it)?.fontType },
            customEmphasis = SyntaxCellCodec.decode(requested.customEmphasis) { FontEmphasis.fromName(it)?.fontType },
            readabilityOptions = requested.readability,
        )
    }

    /** Preserves raw names and ordered cells for presentation without promoting the view to an authority. */
    fun settings(appearance: AppearanceSnapshot): SyntaxPanelSettings {
        val fields = appearance.syntax.records.associate { it.key.name to it.value }
        return SyntaxPanelSettings(
            selectedPreset = text(fields.getValue("selectedPreset")),
            subordinatePreset = text(fields.getValue("subordinatePreset")),
            customOverrides = cells(fields.getValue("customOverrides")),
            customStyles = cells(fields.getValue("customStyles")),
            customEmphasis = cells(fields.getValue("customEmphasis")),
            readability =
                SyntaxReadabilityOptions(
                    dimComments = boolean(fields.getValue("dimComments")),
                    softenDocumentation = boolean(fields.getValue("softenDocumentation")),
                    quietOperators = boolean(fields.getValue("quietOperators")),
                    emphasizeDeclarations = boolean(fields.getValue("emphasizeDeclarations")),
                ),
        )
    }

    private fun text(raw: RawValue): String? =
        if (raw == RawValue.Null) null else requireNotNull(raw as? RawValue.Text).value

    private fun boolean(raw: RawValue): Boolean = requireNotNull(raw as? RawValue.BooleanValue).value

    private fun cells(raw: RawValue): Map<String, String> =
        requireNotNull(raw as? RawValue.OrderedMap).entries.associate {
            it.key to requireNotNull(it.value as? RawValue.Text).value
        }
}
