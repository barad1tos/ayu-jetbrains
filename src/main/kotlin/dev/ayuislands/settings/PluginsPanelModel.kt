package dev.ayuislands.settings

import dev.ayuislands.accent.AccentDefaults
import dev.ayuislands.accent.ExternalAccentSource
import dev.ayuislands.indent.IndentPreset

internal data class ExternalFeatures(
    val quickSwitcher: Boolean = true,
    val glow: Boolean = false,
    val codeGlance: Boolean = true,
    val indentRainbow: Boolean = true,
    val chromeTint: Boolean = false,
)

internal data class ExternalThemeSettings(
    val enabled: Boolean = false,
    val overrideAccent: Boolean = true,
    val features: ExternalFeatures = ExternalFeatures(),
    val accentSource: String? = ExternalAccentSource.AUTOMATIC.name,
    val accent: String? = AccentDefaults.MIRAGE_HEX,
)

internal data class IntegrationSettings(
    val codeGlanceEnabled: Boolean = false,
    val indentEnabled: Boolean = false,
    val indentPreset: String? = IndentPreset.AMBIENT.name,
    val indentAlpha: Int = IndentPreset.DEFAULT_ALPHA,
    val highlightErrors: Boolean = true,
    val ignoreColorsEnabled: Boolean = true,
)

/** Raw shared preferences; capability and entitlement never rewrite these requested values. */
internal data class PluginsSettings(
    val external: ExternalThemeSettings = ExternalThemeSettings(),
    val integrations: IntegrationSettings = IntegrationSettings(),
)

internal data class PluginsAvailability(
    val isLicensed: Boolean,
    val hasCodeGlance: Boolean,
    val hasIndentRainbow: Boolean,
)

internal data class PluginsPanelView(
    val settings: PluginsSettings,
    val availability: PluginsAvailability,
    val isEditable: Boolean,
    val pendingBadges: Set<String>,
)

internal enum class PluginsToggle(
    val title: String,
    val premium: Boolean = false,
) {
    EXTERNAL_ENABLED("Enable Ayu enhancements on other themes"),
    EXTERNAL_QUICK_SWITCHER("Quick switcher"),
    EXTERNAL_GLOW("Glow"),
    EXTERNAL_CODE_GLANCE("CodeGlance Pro"),
    EXTERNAL_INDENT_RAINBOW("Indent Rainbow"),
    EXTERNAL_CHROME_TINT("Chrome tint", true),
    OVERRIDE_ACCENT("Override native theme accent"),
    CODE_GLANCE("CodeGlance Pro viewport", true),
    INDENT_RAINBOW("Indent Rainbow guides", true),
    INDENT_ERRORS("Highlight indent errors", true),
    IGNORE_COLORS(".ignore syntax colors"),
}

internal enum class PluginTarget(
    val url: String,
) {
    CODE_GLANCE("https://plugins.jetbrains.com/plugin/18824-codeglance-pro"),
    INDENT_RAINBOW("https://plugins.jetbrains.com/plugin/13308-indent-rainbow"),
}

internal sealed interface PluginsPanelEvent {
    data class Toggle(
        val target: PluginsToggle,
        val value: Boolean,
    ) : PluginsPanelEvent

    data class AccentSource(
        val value: ExternalAccentSource,
    ) : PluginsPanelEvent

    data class Accent(
        val hex: String,
    ) : PluginsPanelEvent

    data class Preset(
        val value: IndentPreset,
    ) : PluginsPanelEvent

    data class Alpha(
        val value: Int,
    ) : PluginsPanelEvent

    data class PluginPage(
        val target: PluginTarget,
    ) : PluginsPanelEvent

    data object CustomAccent : PluginsPanelEvent

    data object Unlock : PluginsPanelEvent
}

/** One edit rule shared by legacy Settings and the Studio shared-field adapter. */
internal object PluginsEditing {
    fun update(
        settings: PluginsSettings,
        event: PluginsPanelEvent,
    ): PluginsSettings =
        when (event) {
            is PluginsPanelEvent.Toggle -> {
                toggle(settings, event)
            }

            is PluginsPanelEvent.AccentSource -> {
                settings.copy(external = settings.external.copy(accentSource = event.value.name))
            }

            is PluginsPanelEvent.Accent -> {
                settings.copy(
                    external =
                        settings.external.copy(
                            accent = event.hex,
                            accentSource = ExternalAccentSource.MANUAL.name,
                        ),
                )
            }

            is PluginsPanelEvent.Preset -> {
                settings.copy(integrations = settings.integrations.copy(indentPreset = event.value.name))
            }

            is PluginsPanelEvent.Alpha -> {
                settings.copy(integrations = settings.integrations.copy(indentAlpha = event.value))
            }

            is PluginsPanelEvent.PluginPage, PluginsPanelEvent.CustomAccent, PluginsPanelEvent.Unlock -> {
                settings
            }
        }

    fun selected(
        settings: PluginsSettings,
        target: PluginsToggle,
    ): Boolean =
        when (target) {
            PluginsToggle.EXTERNAL_ENABLED -> settings.external.enabled
            PluginsToggle.EXTERNAL_QUICK_SWITCHER -> settings.external.features.quickSwitcher
            PluginsToggle.EXTERNAL_GLOW -> settings.external.features.glow
            PluginsToggle.EXTERNAL_CODE_GLANCE -> settings.external.features.codeGlance
            PluginsToggle.EXTERNAL_INDENT_RAINBOW -> settings.external.features.indentRainbow
            PluginsToggle.EXTERNAL_CHROME_TINT -> settings.external.features.chromeTint
            PluginsToggle.OVERRIDE_ACCENT -> settings.external.overrideAccent
            PluginsToggle.CODE_GLANCE -> settings.integrations.codeGlanceEnabled
            PluginsToggle.INDENT_RAINBOW -> settings.integrations.indentEnabled
            PluginsToggle.INDENT_ERRORS -> settings.integrations.highlightErrors
            PluginsToggle.IGNORE_COLORS -> settings.integrations.ignoreColorsEnabled
        }

    private fun toggle(
        settings: PluginsSettings,
        event: PluginsPanelEvent.Toggle,
    ): PluginsSettings {
        val external = settings.external
        val features = external.features
        val integrations = settings.integrations
        val value = event.value
        return when (event.target) {
            PluginsToggle.EXTERNAL_ENABLED -> {
                settings.copy(external = external.copy(enabled = value))
            }

            PluginsToggle.EXTERNAL_QUICK_SWITCHER -> {
                settings.copy(
                    external = external.copy(features = features.copy(quickSwitcher = value)),
                )
            }

            PluginsToggle.EXTERNAL_GLOW -> {
                settings.copy(
                    external = external.copy(features = features.copy(glow = value)),
                )
            }

            PluginsToggle.EXTERNAL_CODE_GLANCE -> {
                settings.copy(
                    external = external.copy(features = features.copy(codeGlance = value)),
                )
            }

            PluginsToggle.EXTERNAL_INDENT_RAINBOW -> {
                settings.copy(
                    external = external.copy(features = features.copy(indentRainbow = value)),
                )
            }

            PluginsToggle.EXTERNAL_CHROME_TINT -> {
                settings.copy(
                    external = external.copy(features = features.copy(chromeTint = value)),
                )
            }

            PluginsToggle.OVERRIDE_ACCENT -> {
                settings.copy(external = external.copy(overrideAccent = value))
            }

            PluginsToggle.CODE_GLANCE -> {
                settings.copy(integrations = integrations.copy(codeGlanceEnabled = value))
            }

            PluginsToggle.INDENT_RAINBOW -> {
                settings.copy(integrations = integrations.copy(indentEnabled = value))
            }

            PluginsToggle.INDENT_ERRORS -> {
                settings.copy(integrations = integrations.copy(highlightErrors = value))
            }

            PluginsToggle.IGNORE_COLORS -> {
                settings.copy(integrations = integrations.copy(ignoreColorsEnabled = value))
            }
        }
    }
}
