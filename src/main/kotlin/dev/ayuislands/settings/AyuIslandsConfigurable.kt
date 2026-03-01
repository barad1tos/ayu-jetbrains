package dev.ayuislands.settings

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.GotItTooltip
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.licensing.LicenseChecker

/** Settings page at Appearance > Ayu Islands composing panel sections. */
class AyuIslandsConfigurable : BoundConfigurable("Ayu Islands") {

    private val log = logger<AyuIslandsConfigurable>()

    private val accentPanel = AyuIslandsAccentPanel()
    private val elementsPanel = AyuIslandsElementsPanel()
    private val previewPanel = AyuIslandsPreviewPanel()
    private val effectsPanel = AyuIslandsEffectsPanel()

    private val panels: List<AyuIslandsSettingsPanel> = listOf(
        accentPanel,
        elementsPanel,
        previewPanel,
        effectsPanel,
    )

    companion object {
        private const val EFFECTS_TAB_INDEX = 1
    }

    override fun createPanel(): com.intellij.openapi.ui.DialogPanel {
        val pluginVersion = PluginManagerCore
            .getPlugin(PluginId.getId("com.ayuislands.theme"))
            ?.version ?: "unknown"

        val variant = AyuVariant.detect()

        if (variant == null) {
            return panel {
                row {
                    label("Ayu Islands v$pluginVersion")
                        .applyToComponent { font = JBUI.Fonts.label(13f).asBold() }
                }
                row {
                    comment("Activate an Ayu Islands theme to configure accent colors")
                }
            }
        }

        // Build tab content panels eagerly via DSL
        val accentTab = panel {
            accentPanel.buildPanel(this@panel, variant)
            elementsPanel.buildPanel(this@panel, variant)
        }

        val effectsTab = panel {
            effectsPanel.buildPanel(this@panel, variant)
        }

        // Top-level tab container
        val outerTabs = JBTabbedPane()
        outerTabs.addTab("Accent", accentTab)
        outerTabs.addTab("Effects", effectsTab)

        // Stop animation preview when switching away from Effects tab
        outerTabs.addChangeListener {
            if (outerTabs.selectedIndex != EFFECTS_TAB_INDEX) {
                previewPanel.stopAnimationPreview()
            }
        }

        // Wire cross-panel callbacks AFTER both tabs are built (all UI components exist)
        wireCallbacks(variant)

        // Initialize preview state from current settings
        initializePreviewState(variant)

        // GotItTooltip onboarding
        val settings = AyuIslandsSettings.getInstance()
        showOnboardingTooltipIfNeeded(settings)

        return panel {
            // Header
            row {
                label("Ayu Islands v$pluginVersion")
                    .applyToComponent { font = JBUI.Fonts.label(13f).asBold() }
            }
            row {
                val status = if (LicenseChecker.isLicensedOrGrace()) "Licensed" else ""
                comment("${variant.name} variant $status".trim())
            }

            // Compact preview strip (persistent, above tabs)
            previewPanel.buildPanel(this@panel, variant)

            // Tab container
            row {
                cell(outerTabs)
                    .resizableColumn()
                    .align(Align.FILL)
            }

            // Footer
            row {
                button("Reset All Settings") {
                    resetAllSettings(variant)
                }
            }
        }
    }

    private fun wireCallbacks(variant: AyuVariant) {
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
            previewPanel.previewGlowStyle = effectsPanel.getCurrentStyle()
            previewPanel.previewGlowIntensity = effectsPanel.getCurrentIntensity()
            previewPanel.previewGlowWidth = effectsPanel.getCurrentWidth()
            previewPanel.updatePreview()
        }
        effectsPanel.onStyleChanged = {
            previewPanel.previewGlowStyle = effectsPanel.getCurrentStyle()
            previewPanel.previewGlowIntensity = effectsPanel.getCurrentIntensity()
            previewPanel.previewGlowWidth = effectsPanel.getCurrentWidth()
            previewPanel.previewEffectsTabIndex = effectsPanel.getActiveTabIndex()
            previewPanel.previewIslandToggles = effectsPanel.getIslandToggles()
            previewPanel.updatePreview()
        }
        effectsPanel.onAnimationChanged = {
            val animation = effectsPanel.getCurrentAnimation()
            if (animation != GlowAnimation.NONE) {
                previewPanel.startAnimationPreview(animation)
            } else {
                previewPanel.stopAnimationPreview()
            }
        }
    }

    private fun initializePreviewState(variant: AyuVariant) {
        val settings = AyuIslandsSettings.getInstance()
        previewPanel.previewAccentHex = settings.getAccentForVariant(variant)
        previewPanel.previewToggles = elementsPanel.currentToggles()
        previewPanel.previewGlowEnabled = effectsPanel.isGlowEnabled()

        val style = GlowStyle.fromName(settings.state.glowStyle ?: GlowStyle.SOFT.name)
        previewPanel.previewGlowStyle = style
        previewPanel.previewGlowIntensity = settings.state.getIntensityForStyle(style)
        previewPanel.previewGlowWidth = settings.state.getWidthForStyle(style)
    }

    private fun resetAllSettings(variant: AyuVariant) {
        // Accent: use resetToDefault() which updates pendingAccent, swatch color,
        // AND fires onAccentChanged -- so the preview also updates.
        accentPanel.resetToDefault(variant)

        // Elements + Effects: reset() restores stored (last-applied) state.
        elementsPanel.reset()
        effectsPanel.reset()

        // Update preview to reflect all resets
        previewPanel.updatePreview()
    }

    private fun showOnboardingTooltipIfNeeded(settings: AyuIslandsSettings) {
        val state = settings.state
        if (!state.glowOnboardingShown && LicenseChecker.isLicensedOrGrace()) {
            javax.swing.SwingUtilities.invokeLater {
                effectsPanel.getEffectsTabbedPane()?.let { tabs ->
                    if (!tabs.isShowing) return@let
                    val tooltip = GotItTooltip(
                        "ayu.islands.glow.onboarding",
                        "Customize the neon glow effect with different styles, intensity, and animation. " +
                            "Try the 'Balanced' preset to get started.",
                    )
                    tooltip.show(tabs, GotItTooltip.BOTTOM_MIDDLE)
                }
                state.glowOnboardingShown = true
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

        // Trigger glow overlay update for all open projects
        val glowEnabled = AyuIslandsSettings.getInstance().state.glowEnabled

        // Zen Mode: skip glow activation in presentation/distraction-free mode
        val inZenMode = com.intellij.ide.ui.UISettings.getInstance().presentationMode
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
            } catch (exception: Exception) {
                log.warn("Failed to update glow for project: ${openProject.name}", exception)
            }
        }

        // Stop animation preview on apply (leaving settings mode)
        previewPanel.stopAnimationPreview()
    }

    override fun reset() {
        super.reset()
        for (section in panels) {
            section.reset()
        }
    }
}
