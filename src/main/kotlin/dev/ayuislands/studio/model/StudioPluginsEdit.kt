package dev.ayuislands.studio.model

import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.ExternalAccentSource
import dev.ayuislands.indent.IndentPreset

internal enum class StudioPluginsToggle(
    val field: String,
) {
    EXTERNAL_ENHANCEMENTS("externalThemeEnhancementsEnabled"),
    EXTERNAL_QUICK_SWITCHER("externalThemeQuickSwitcherEnabled"),
    EXTERNAL_GLOW("externalThemeGlowEnabled"),
    EXTERNAL_CODE_GLANCE("externalThemeCodeGlanceProEnabled"),
    EXTERNAL_INDENT_RAINBOW("externalThemeIndentRainbowEnabled"),
    EXTERNAL_CHROME_TINT("externalThemeChromeTintEnabled"),
    OVERRIDE_NATIVE_ACCENT("externalThemeAccentOverrideEnabled"),
    CODE_GLANCE_INTEGRATION("cgpIntegrationEnabled"),
    INDENT_RAINBOW_INTEGRATION("irIntegrationEnabled"),
    INDENT_ERRORS("irErrorHighlightEnabled"),
    IGNORE_SYNTAX_COLORS("ignorePluginSyntaxColorsEnabled"),
}

internal sealed interface StudioPluginsEdit {
    data class Toggle(
        val target: StudioPluginsToggle,
        val enabled: Boolean,
    ) : StudioPluginsEdit

    data class AccentSource(
        val value: ExternalAccentSource,
    ) : StudioPluginsEdit

    data class Accent(
        val value: AccentHex,
    ) : StudioPluginsEdit

    data class IndentPresetChoice(
        val value: IndentPreset,
    ) : StudioPluginsEdit

    data class IndentAlpha(
        val value: Int,
    ) : StudioPluginsEdit
}
