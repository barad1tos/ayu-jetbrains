package dev.ayuislands.studio.ui

import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.font.FontPanelPreview
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.settings.GlowPreview
import dev.ayuislands.settings.PluginsPreview
import dev.ayuislands.settings.PluginsPreviewInput
import dev.ayuislands.settings.PluginsPreviewView
import dev.ayuislands.settings.SyntaxPreviewInput
import dev.ayuislands.settings.VcsPreviewInput
import dev.ayuislands.settings.VcsPreviewIntensities
import dev.ayuislands.studio.model.AppearanceSnapshot
import dev.ayuislands.studio.model.SharedPreferences
import dev.ayuislands.studio.session.StudioAccentEditing
import dev.ayuislands.studio.session.StudioFontProjection
import dev.ayuislands.studio.session.StudioGlowProjection
import dev.ayuislands.studio.session.StudioPluginsEditing
import dev.ayuislands.studio.session.StudioSyntaxProjection
import dev.ayuislands.studio.session.StudioVcsProjection
import dev.ayuislands.syntax.SyntaxIntensityApplicator
import dev.ayuislands.syntax.SyntaxPreset
import dev.ayuislands.vcs.VcsColorCategory

/** Detached samples retain only their latest expensive projections; no editor manager scheme is written. */
internal class StudioSettingsSamples(
    private var context: StudioSettingsContext,
    private val syntaxColors: StudioSyntaxColors = StudioSyntaxColors(),
) {
    private val licensed get() = context.entitlement == LicenseEntitlement.LICENSED
    private var syntaxSample: Pair<SyntaxSampleSource, SyntaxPreviewInput>? = null
    private var pluginsSample: Pair<PluginsPreviewInput, PluginsPreviewView>? = null

    fun updateContext(observation: StudioSettingsContext) {
        context = observation
    }

    fun accent(
        appearance: AppearanceSnapshot,
        shared: SharedPreferences,
    ): String {
        val requested = StudioAccentEditing.settings(appearance, shared).colors[context.accent.variant]
        return AccentHex.of(requested)?.value ?: context.accent.variant.defaultAccent
    }

    fun font(appearance: AppearanceSnapshot): FontPanelPreview? {
        val settings = StudioFontProjection.selection(appearance).settings ?: return null
        return FontPanelPreview(settings, settings.fontFamily, settings.fontFamily in context.font.installedFamilies)
    }

    fun glow(
        appearance: AppearanceSnapshot,
        shared: SharedPreferences,
    ): GlowPreview {
        val settings = StudioGlowProjection.settings(appearance)
        return GlowPreview(
            settings.shape,
            settings.style,
            settings.intensity.getValue(settings.style),
            settings.width.getValue(settings.style),
            AccentHex.require(accent(appearance, shared)).toColor(),
            settings.enabled,
            WaveformConfig(
                settings.waveformMovement,
                settings.waveformBaseline,
                settings.waveformAmplitude,
                settings.waveformIntensity,
                settings.waveformLoopSeconds,
                settings.waveformTraceDensity,
                settings.waveformTraceLength,
            ),
        )
    }

    fun syntax(appearance: AppearanceSnapshot): SyntaxPreviewInput {
        val request = syntaxRequest(appearance)
        val source = SyntaxSampleSource(context.editor, context.accent.variant, request)
        syntaxSample?.takeIf { it.first == source }?.let { return it.second }
        val clone = context.editor.scheme.clone() as EditorColorsScheme
        syntaxColors.compute(request).forEach { (key, attributes) -> clone.setAttributes(key, attributes) }
        return SyntaxPreviewInput(context.accent.variant, clone).also { syntaxSample = source to it }
    }

    private fun syntaxRequest(appearance: AppearanceSnapshot): SyntaxIntensityApplicator.Request {
        val config = StudioSyntaxProjection.config(appearance)
        return context.editor.syntaxBase.copy(
            preset =
                SyntaxIntensityApplicator.effectivePreset(
                    SyntaxPreset.fromName(config.selectedPreset),
                    licensed,
                ),
            subordinatePreset = SyntaxPreset.fromName(config.subordinatePreset),
            customOverrides = config.customOverrides,
            customStyles = config.customStyles,
            customEmphasis = config.customEmphasis,
            readabilityOptions =
                SyntaxIntensityApplicator.effectiveReadability(
                    config.readabilityOptions,
                    licensed,
                ),
        )
    }

    fun vcs(appearance: AppearanceSnapshot): VcsPreviewInput {
        val requested = StudioVcsProjection.settings(appearance)
        val resolved = StudioVcsProjection.snapshot(appearance)
        val values = resolved.perCategoryIntensities
        return VcsPreviewInput(
            context.accent.variant,
            VcsPreviewIntensities(
                values.getValue(VcsColorCategory.DIFF_VIEWER),
                values.getValue(VcsColorCategory.PROJECT_VIEW_FILE_STATUS),
                values.getValue(VcsColorCategory.EDITOR_GUTTER),
                values.getValue(VcsColorCategory.CONFLICT_MARKERS),
                values.getValue(VcsColorCategory.BLAME_GUTTER),
            ),
            requested.enabled,
            true,
        )
    }

    fun plugins(
        appearance: AppearanceSnapshot,
        shared: SharedPreferences,
    ): PluginsPreviewView {
        val input =
            PluginsPreviewInput(
                StudioPluginsEditing.settings(shared.plugins),
                accent(appearance, shared),
                context.accent.variant,
                context.editor.ignore?.copy(request = syntaxRequest(appearance)),
            )
        pluginsSample?.takeIf { it.first == input }?.let { return it.second }
        return PluginsPreview.prepare(input, syntaxColors::compute).also { pluginsSample = input to it }
    }
}

/** The editor observation owns detached scheme bytes and is replaced on native scheme refresh. */
private data class SyntaxSampleSource(
    val editor: StudioEditorContext,
    val variant: AyuVariant,
    val request: SyntaxIntensityApplicator.Request,
)

/** Shares the latest pure color calculation between the Syntax and Plugins samples of one Settings view. */
internal class StudioSyntaxColors {
    private var prepared: Pair<SyntaxIntensityApplicator.Request, Map<TextAttributesKey, TextAttributes>>? = null

    fun compute(request: SyntaxIntensityApplicator.Request): Map<TextAttributesKey, TextAttributes> {
        prepared?.takeIf { it.first == request }?.let { return it.second }
        return SyntaxIntensityApplicator.compute(request).also { prepared = request to it }
    }
}
