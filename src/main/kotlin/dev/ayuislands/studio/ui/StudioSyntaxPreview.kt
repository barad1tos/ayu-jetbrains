package dev.ayuislands.studio.ui

import com.intellij.openapi.editor.colors.EditorColorsScheme
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.session.StudioSyntaxProjection
import dev.ayuislands.syntax.SyntaxIntensityApplicator
import dev.ayuislands.syntax.SyntaxPreset
import dev.ayuislands.syntax.effective
import dev.ayuislands.theme.EditorSchemeOverrides
import dev.ayuislands.theme.EditorSchemeOwner

internal class StudioSyntaxSample(
    val variant: AyuVariant,
    val scheme: EditorColorsScheme,
)

internal data class StudioSyntaxComparison(
    val current: StudioSyntaxSample,
    val draft: StudioSyntaxSample,
)

/** Prepare native sample attributes using the same preset rules as runtime syntax. */
internal object StudioSyntaxPreview {
    fun prepare(
        variant: AyuVariant,
        source: EditorColorsScheme,
        palette: SyntaxIntensityApplicator.Request,
        appearance: AppearanceSnapshot,
        isLicensed: Boolean,
    ): StudioSyntaxSample {
        val config = StudioSyntaxProjection.config(appearance)
        val request =
            palette.copy(
                preset = SyntaxPreset.fromName(config.selectedPreset).effective(isLicensed),
                subordinatePreset = SyntaxPreset.fromName(config.subordinatePreset),
                customOverrides = config.customOverrides,
                customStyles = config.customStyles,
                customEmphasis = config.customEmphasis,
                readabilityOptions = config.readabilityOptions.effective(isLicensed),
            )
        val colors = SyntaxIntensityApplicator.compute(request)
        val detached = EditorSchemeOverrides.Preview.copyWithoutOwnedAttributes(source, EditorSchemeOwner.Syntax)
        colors.forEach(detached::setAttributes)
        return StudioSyntaxSample(variant, detached)
    }
}
