package dev.ayuislands.settings

import com.intellij.ide.BrowserUtil
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
import dev.ayuislands.accent.runCatchingPreservingCancellation
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.onboarding.OnboardingUrls
import java.awt.Color
import java.awt.Cursor
import java.awt.Image
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.ImageIcon
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Timer

/** Settings page at Appearance > Ayu Islands with Accent / Glow tabs. */
class AyuIslandsConfigurable : BoundConfigurable("Ayu Islands") {
    private val log = logger<AyuIslandsConfigurable>()

    private companion object {
        const val LOGO_HEIGHT = 28
        const val MIN_TABS_WIDTH = 600
        const val EXPAND_FRAME_MS = 12
        const val EXPAND_MS_PER_CHAR = 35
        const val DISCUSSIONS_SHOW_SETUP = OnboardingUrls.DISCUSSIONS_SHOW_SETUP
        const val DISCUSSIONS_FEATURE_REQUESTS = OnboardingUrls.DISCUSSIONS_FEATURE_REQUESTS
    }

    private val activeTimers = mutableListOf<Timer>()

    private val appearancePanel = AyuIslandsAppearancePanel()
    private val accentPanel = AyuIslandsAccentPanel()
    private val chromePanel = AyuIslandsChromePanel()
    private val elementsPanel = AyuIslandsElementsPanel()
    private val workspacePanel = WorkspacePanel()
    private val pluginsPanel = PluginsPanel()
    private val fontPresetPanel = FontPresetPanel()
    private val effectsPanel = AyuIslandsEffectsPanel()

    private val panels: List<AyuIslandsSettingsPanel> =
        listOf(
            appearancePanel,
            accentPanel,
            chromePanel,
            elementsPanel,
            fontPresetPanel,
            effectsPanel,
            workspacePanel,
            pluginsPanel,
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

        // Wire accent color changes to the element preview
        accentPanel.onAccentChanged = { hex -> elementsPanel.updatePreviewAccent(hex) }

        // Visual order: Accent Color → System → Overrides → Chrome Tinting → Rotation → Elements.
        // AccentPanel renders Accent Color → (beforeOverrides) → Overrides →
        // (afterOverrides) → Rotation. The beforeOverrides hook hosts
        // AppearancePanel's "System" collapsibleGroup; the afterOverrides hook hosts
        // the Phase 40 Chrome Tinting collapsible.
        //
        // Chrome Tinting renders AFTER Overrides and BEFORE Rotation because it
        // consumes the *resolved* accent produced by override rules — mirroring the
        // data-flow dependency gives users a top-to-bottom reading order: choose
        // accent → system integrations → scope overrides → chrome surface toggles →
        // rotation schedule → per-element toggles. The two parallel injection hooks
        // keep AccentPanel composition-friendly without turning the order into a
        // free-form list (see PATTERNS.md 349-362).
        accentPanel.beforeOverridesInjection = { injectionPanel ->
            appearancePanel.buildPanel(injectionPanel, variant)
        }
        accentPanel.afterOverridesInjection = { injectionPanel ->
            chromePanel.buildPanel(injectionPanel, variant)
        }
        appearancePanel.systemAccentRowInstaller = { rowHostPanel ->
            accentPanel.installSystemAccentCheckbox(rowHostPanel)
        }

        // Build tab content panels eagerly via DSL
        val accentTab =
            panel {
                accentPanel.buildPanel(this@panel, variant)
                elementsPanel.buildPanel(this@panel, variant)

                // "Reset all settings..." link at the bottom of the Accent tab
                row {
                    link("Reset all Ayu settings\u2026") {
                        val result =
                            Messages.showYesNoDialog(
                                "Reset all Ayu Islands settings to defaults?\n\n" +
                                    "This will reset accent color, element toggles, and all glow settings.",
                                "Reset All Settings",
                                Messages.getWarningIcon(),
                            )
                        if (result == Messages.YES) {
                            resetAllSettings()
                        }
                    }
                }
            }

        fontPresetPanel.initState()

        val fontTab =
            panel {
                fontPresetPanel.buildFontTab(this@panel)
            }

        val glowTab =
            panel {
                effectsPanel.buildGlowPanel(this@panel)
            }

        val workspaceTab =
            panel {
                workspacePanel.buildPanel(this@panel, variant)
            }

        val pluginsTab =
            panel {
                pluginsPanel.buildPanel(this@panel, variant)
            }

        // Single-level tab container
        val settings = AyuIslandsSettings.getInstance()
        val state = settings.state
        val accentColor =
            try {
                Color.decode(settings.getAccentForVariant(variant))
            } catch (exception: NumberFormatException) {
                log.warn("Invalid accent color for ${variant.name}, using theme default", exception)
                JBUI.CurrentTheme.Link.Foreground.ENABLED
            }

        val tabs = JBTabbedPane()
        tabs.minimumSize = java.awt.Dimension(MIN_TABS_WIDTH, 0)
        tabs.addTab("Accent", accentTab)
        tabs.addTab("Font", fontTab)
        tabs.addTab("Glow", glowTab)
        tabs.addTab("Workspace", workspaceTab)
        tabs.addTab("Plugins", pluginsTab)

        val contentTabCount = tabs.tabCount

        // Community link tabs — disabled for selection, click opens browser via label
        tabs.addTab("", JPanel())
        tabs.setTabComponentAt(
            contentTabCount,
            createLinkTab("Share", "Share Your Setup", accentColor, DISCUSSIONS_SHOW_SETUP),
        )
        tabs.setEnabledAt(contentTabCount, false)
        tabs.addTab("", JPanel())
        tabs.setTabComponentAt(
            contentTabCount + 1,
            createLinkTab("Feature", "Request a Feature", accentColor, DISCUSSIONS_FEATURE_REQUESTS),
        )
        tabs.setEnabledAt(contentTabCount + 1, false)

        tabs.selectedIndex = state.settingsSelectedTab.coerceIn(0, contentTabCount - 1)
        tabs.addChangeListener {
            AyuIslandsSettings.getInstance().state.settingsSelectedTab = tabs.selectedIndex
        }

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

    private fun createLinkTab(
        shortText: String,
        fullText: String,
        accentColor: Color,
        url: String,
    ): JLabel {
        val label = JLabel(shortText)
        label.font = JBUI.Fonts.label()
        label.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val defaultColor = label.foreground
        val timerHolder = arrayOfNulls<Timer>(1)

        label.addMouseListener(
            object : MouseAdapter() {
                override fun mouseEntered(event: MouseEvent) {
                    timerHolder[0]?.stop()
                    label.foreground = accentColor
                    timerHolder[0] = animateText(label, label.text.length, fullText.length, fullText)
                }

                override fun mouseExited(event: MouseEvent) {
                    timerHolder[0]?.stop()
                    label.foreground = defaultColor
                    timerHolder[0] = animateText(label, label.text.length, shortText.length, fullText)
                }

                override fun mouseClicked(event: MouseEvent) {
                    BrowserUtil.browse(url)
                }
            },
        )
        return label
    }

    private fun animateText(
        label: JLabel,
        startLength: Int,
        targetLength: Int,
        fullText: String,
    ): Timer? {
        if (startLength == targetLength) return null
        val startTime = System.currentTimeMillis()
        val charDelta = kotlin.math.abs(targetLength - startLength)
        val duration = (charDelta * EXPAND_MS_PER_CHAR).toLong()
        val timer =
            Timer(EXPAND_FRAME_MS) {
                if (!label.isDisplayable) {
                    (it.source as Timer).stop()
                    return@Timer
                }
                val elapsed = System.currentTimeMillis() - startTime
                val progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                val chars = startLength + ((targetLength - startLength) * progress).toInt()
                label.text = fullText.substring(0, chars)
                if (progress >= 1f) {
                    label.text = fullText.substring(0, targetLength)
                    (it.source as Timer).stop()
                }
            }
        activeTimers.add(timer)
        timer.start()
        return timer
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

    private fun resetAllSettings() {
        accentPanel.resetToDefault()
        chromePanel.reset()
        elementsPanel.reset()
        fontPresetPanel.reset()
        effectsPanel.reset()
        workspacePanel.reset()
        pluginsPanel.reset()
    }

    override fun disposeUIResources() {
        activeTimers.forEach { it.stop() }
        activeTimers.clear()
        // Drive platform-owned subscriptions shut down through the
        // AyuIslandsSettingsPanel.dispose() default no-op; panels that
        // hold platform lifecycle state override it. AyuIslandsAccentPanel
        // additionally owns OverridesGroupBuilder whose detection-Topic
        // MessageBus connection needs explicit disconnect — reach it
        // directly via the `internal val overrides` field rather than
        // layering a second dispose override, which would bump
        // AccentPanel past detekt's 25-function class budget.
        //
        // Each dispose call is individually wrapped so a throwing panel
        // (or a future override with a platform-API failure mode) does
        // not prevent the remaining panels or the mandatory super call
        // from running. The MessageBus subscription in overrides is the
        // load-bearing cleanup — a skipped super would leak the
        // BoundConfigurable binding too.
        // Cancellation-preserving wrap keeps structured-concurrency
        // semantics if a future refactor moves this dispose chain into
        // a coroutine scope; today disposeUIResources is a plain EDT
        // callback but the sibling disconnect wraps in
        // OverridesGroupBuilder use the same variant, so we stay
        // consistent across the module.
        panels.forEach { panel ->
            runCatchingPreservingCancellation { panel.dispose() }
                .onFailure { log.warn("Panel dispose threw for ${panel.javaClass.simpleName}", it) }
        }
        runCatchingPreservingCancellation { accentPanel.overrides.dispose() }
            .onFailure { log.warn("OverridesGroupBuilder dispose threw", it) }
        super.disposeUIResources()
    }

    override fun isModified(): Boolean = super.isModified() || panels.any { it.isModified() }

    override fun apply() {
        super.apply()

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
