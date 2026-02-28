package dev.ayuislands.settings

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker

/** Settings page at Appearance > Ayu Islands composing panel sections. */
class AyuIslandsConfigurable : BoundConfigurable("Ayu Islands") {

    private val accentPanel = AyuIslandsAccentPanel()
    private val elementsPanel = AyuIslandsElementsPanel()
    private val previewPanel = AyuIslandsPreviewPanel()
    private val effectsPanel = AyuIslandsEffectsPanel()
    private val licenseFooter = AyuIslandsLicenseFooter()

    private val panels: List<AyuIslandsSettingsPanel> = listOf(
        accentPanel,
        elementsPanel,
        previewPanel,
        effectsPanel,
        licenseFooter,
    )

    override fun createPanel(): com.intellij.openapi.ui.DialogPanel {
        val pluginVersion = PluginManagerCore
            .getPlugin(PluginId.getId("com.ayuislands.theme"))
            ?.version ?: "unknown"

        val variant = AyuVariant.detect()

        return panel {
            row {
                label("Ayu Islands v$pluginVersion")
                    .applyToComponent { font = JBUI.Fonts.label(13f).asBold() }
            }

            if (LicenseChecker.isLicensedOrGrace()) {
                row {
                    comment("Licensed")
                }
            }

            if (variant == null) {
                row {
                    comment("Activate an Ayu Islands theme to configure accent colors")
                }
            } else {
                row {
                    comment("${variant.name} variant")
                }

                for (section in panels) {
                    section.buildPanel(this@panel, variant)
                }

                // Initialize preview state from current settings
                val settings = AyuIslandsSettings.getInstance()
                previewPanel.previewAccentHex = settings.getAccentForVariant(variant)
                previewPanel.previewToggles = elementsPanel.currentToggles()
                previewPanel.previewGlowEnabled = effectsPanel.isGlowEnabled()

                // Wire callbacks for cross-panel preview updates
                accentPanel.onAccentChanged = { hex ->
                    previewPanel.previewAccentHex = hex
                    previewPanel.updatePreview()
                }
                elementsPanel.onToggleChanged = {
                    previewPanel.previewToggles = elementsPanel.currentToggles()
                    previewPanel.updatePreview()
                }
                effectsPanel.onGlowChanged = {
                    previewPanel.previewGlowEnabled = effectsPanel.isGlowEnabled()
                    previewPanel.updatePreview()
                }
            }
        }
    }

    override fun isModified(): Boolean =
        super.isModified() || panels.any { it.isModified() }

    override fun apply() {
        super.apply()
        for (section in panels) {
            section.apply()
        }
    }

    override fun reset() {
        super.reset()
        for (section in panels) {
            section.reset()
        }
    }
}
