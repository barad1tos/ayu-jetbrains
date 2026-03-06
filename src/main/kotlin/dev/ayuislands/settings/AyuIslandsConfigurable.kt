package dev.ayuislands.settings

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.licensing.LicenseChecker
import java.awt.Image
import javax.swing.ImageIcon

/** Settings page at Appearance > Ayu Islands with Accent / Glow tabs. */
class AyuIslandsConfigurable : BoundConfigurable("Ayu Islands") {
    private val log = logger<AyuIslandsConfigurable>()

    private companion object {
        const val LOGO_HEIGHT = 28
    }

    private var pendingSelectedTab: Int =
        AyuIslandsSettings.getInstance().state.settingsSelectedTab

    private val appearancePanel = AyuIslandsAppearancePanel()
    private val accentPanel = AyuIslandsAccentPanel()
    private val elementsPanel = AyuIslandsElementsPanel()
    private val indentRainbowPanel = IndentRainbowPanel()
    private val effectsPanel = AyuIslandsEffectsPanel()

    private val panels: List<AyuIslandsSettingsPanel> =
        listOf(
            appearancePanel,
            accentPanel,
            elementsPanel,
            indentRainbowPanel,
            effectsPanel,
        )

    override fun createPanel(): com.intellij.openapi.ui.DialogPanel {
        val pluginVersion =
            PluginManagerCore
                .getPlugin(PluginId.getId("com.ayuislands.theme"))
                ?.version ?: "unknown"

        val variant =
            AyuVariant.detect() ?: return panel {
                row {
                    scaleIcon()?.let { icon(it) }
                    label("v$pluginVersion")
                        .applyToComponent { font = JBUI.Fonts.smallFont() }
                }
                row {
                    comment("Activate an Ayu Islands theme to configure accent colors")
                }
            }

        // Wire accent color changes to elements preview
        accentPanel.onAccentChanged = { hex -> elementsPanel.updatePreviewAccent(hex) }

        // Build tab content panels eagerly via DSL
        val accentTab =
            panel {
                appearancePanel.buildPanel(this@panel, variant)
                accentPanel.buildPanel(this@panel, variant)
                elementsPanel.buildPanel(this@panel, variant)
                indentRainbowPanel.buildPanel(this@panel, variant)

                // "Reset all settings..." link at the bottom of the Accent tab
                row {
                    link("Reset all settings\u2026") {
                        val result =
                            Messages.showYesNoDialog(
                                "Reset all Ayu Islands settings to defaults?\n\n" +
                                    "This will reset accent color, element toggles, and all glow settings.",
                                "Reset All Settings",
                                Messages.getWarningIcon(),
                            )
                        if (result == Messages.YES) {
                            resetAllSettings(variant)
                        }
                    }
                }
            }

        val glowTab =
            panel {
                effectsPanel.buildGlowPanel(this@panel)
            }

        // Single-level tab container
        val state = AyuIslandsSettings.getInstance().state
        val tabs = JBTabbedPane()
        tabs.addTab("Accent", accentTab)
        tabs.addTab("Glow", glowTab)
        tabs.selectedIndex = state.settingsSelectedTab.coerceIn(0, tabs.tabCount - 1)
        tabs.addChangeListener { pendingSelectedTab = tabs.selectedIndex }

        return panel {
            // Header
            row {
                scaleIcon()?.let { icon(it) }
                label("v$pluginVersion")
                    .applyToComponent { font = JBUI.Fonts.smallFont() }
            }
            row {
                val status = if (LicenseChecker.isLicensedOrGrace()) "Licensed" else ""
                comment("${variant.name} variant $status".trim())
            }

            if (!LicenseChecker.isLicensedOrGrace()) {
                row {
                    link("Get Ayu Islands Pro — unlock element toggles and glow effects") {
                        LicenseChecker.requestLicense(
                            "Unlock per-element accent toggles and neon glow effects",
                        )
                    }
                }
            }

            // Tab container
            row {
                cell(tabs)
                    .resizableColumn()
                    .align(Align.FILL)
            }
        }
    }

    private fun scaleIcon(): ImageIcon? {
        val logoUrl = AyuIslandsConfigurable::class.java.getResource("/assets/logo.png") ?: return null
        val originalIcon = ImageIcon(logoUrl)
        val scaledHeight = JBUI.scale(LOGO_HEIGHT)
        val scaledWidth =
            (originalIcon.iconWidth.toDouble() / originalIcon.iconHeight * scaledHeight).toInt()
        val scaledImage =
            originalIcon.image.getScaledInstance(scaledWidth, scaledHeight, Image.SCALE_SMOOTH)
        return ImageIcon(scaledImage)
    }

    private fun resetAllSettings(variant: AyuVariant) {
        accentPanel.resetToDefault(variant)
        elementsPanel.reset()
        effectsPanel.reset()
    }

    override fun isModified(): Boolean = super.isModified() || panels.any { it.isModified() }

    override fun apply() {
        super.apply()

        // Persist tab selection only on Apply/OK (not on Cancel)
        AyuIslandsSettings.getInstance().state.settingsSelectedTab = pendingSelectedTab

        for (section in panels) {
            section.apply()
        }

        // Trigger glow overlay update for all open projects
        val glowEnabled = AyuIslandsSettings.getInstance().state.glowEnabled

        // Zen Mode: skip glow activation in presentation/distraction-free mode
        val inZenMode =
            com.intellij.ide.ui.UISettings
                .getInstance()
                .presentationMode
        if (inZenMode && glowEnabled) {
            log.info("Zen Mode active, skipping glow activation")
        }

        for (openProject in ProjectManager.getInstance().openProjects) {
            try {
                val manager = GlowOverlayManager.getInstance(openProject)
                if (glowEnabled && !inZenMode) {
                    manager.initialize()
                    manager.updateGlow()
                } else {
                    manager.updateGlow()
                }
            } catch (exception: RuntimeException) {
                log.warn("Failed to update glow for project: ${openProject.name}", exception)
            }
        }
    }

    override fun reset() {
        super.reset()
        for (section in panels) {
            section.reset()
        }
    }
}
