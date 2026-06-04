package dev.ayuislands.settings

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import dev.ayuislands.AyuPlugin
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.runCatchingPreservingCancellation
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.onboarding.OnboardingUrls
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Image
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.Timer

/** Settings page at Appearance > Ayu Islands with Accent / Glow tabs. */
private fun resolvePluginVersion(): String =
    AyuPlugin
        .findLoadedPlugin(AyuPlugin.ID)
        ?.version ?: "unknown"

class AyuIslandsConfigurable : BoundConfigurable("Ayu Islands") {
    private val log = logger<AyuIslandsConfigurable>()

    private companion object {
        const val LOGO_HEIGHT = 28
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
    private val vcsColorPanel = VcsColorPanel()
    private val syntaxPanel = AyuIslandsSyntaxPanel()
    private var builtPanels: List<AyuIslandsSettingsPanel> = emptyList()

    private val panels: List<AyuIslandsSettingsPanel> =
        listOf(
            appearancePanel,
            accentPanel,
            chromePanel,
            elementsPanel,
            fontPresetPanel,
            effectsPanel,
            vcsColorPanel,
            syntaxPanel,
            workspacePanel,
            pluginsPanel,
        )

    override fun createPanel(): com.intellij.openapi.ui.DialogPanel {
        val pluginVersion = resolvePluginVersion()
        val variant = AyuVariant.detect()
        val effectiveVariant = variant ?: AyuVariant.MIRAGE
        val activePanels = mutableListOf<AyuIslandsSettingsPanel>()

        if (variant != null) {
            configureAyuPanelComposition(variant)
        }

        val contentTabs = buildContentTabs(variant, effectiveVariant, activePanels)
        builtPanels = activePanels

        val settings = AyuIslandsSettings.getInstance()
        val state = settings.state
        val tabs =
            createSettingsTabs(
                contentTabs = contentTabs,
                accentColor = resolveTabAccentColor(settings, variant),
                selectedIndex = state.settingsSelectedTab,
            ) { selectedIndex ->
                AyuIslandsSettings.getInstance().state.settingsSelectedTab = selectedIndex
            }

        return buildRootPanel(pluginVersion, variant, tabs)
    }

    private fun configureAyuPanelComposition(variant: AyuVariant) {
        // Wire accent color changes to the element preview.
        accentPanel.onAccentChanged = { hex -> elementsPanel.updatePreviewAccent(hex) }

        // Visual order: Accent Color -> System -> Overrides -> Chrome Tinting -> Rotation -> Elements.
        // AccentPanel renders Accent Color -> (beforeOverrides) -> Overrides ->
        // (afterOverrides) -> Rotation. The beforeOverrides hook hosts
        // AppearancePanel's "System" collapsibleGroup; the afterOverrides hook hosts
        // the Chrome Tinting collapsible.
        accentPanel.beforeOverridesInjection = { injectionPanel ->
            appearancePanel.buildPanel(injectionPanel, variant)
        }
        accentPanel.afterOverridesInjection = { injectionPanel ->
            chromePanel.buildPanel(injectionPanel, variant)
        }
        appearancePanel.systemAccentRowInstaller = { rowHostPanel ->
            accentPanel.installSystemAccentCheckbox(rowHostPanel)
        }
    }

    private fun buildContentTabs(
        variant: AyuVariant?,
        effectiveVariant: AyuVariant,
        activePanels: MutableList<AyuIslandsSettingsPanel>,
    ): List<Pair<String, JComponent>> {
        fontPresetPanel.initState()
        return listOf(
            "Accent" to buildAccentTab(variant, activePanels),
            "Font" to buildFontTab(activePanels),
            "Glow" to buildGlowTab(activePanels),
            // Syntax slots between Glow and VCS so rendering-related tabs cluster
            // before source-control and workspace tabs.
            "Syntax" to buildAyuOnlyTab(variant, "syntax intensity", syntaxPanel, activePanels),
            "VCS" to buildAyuOnlyTab(variant, "VCS colors", vcsColorPanel, activePanels),
            "Workspace" to buildAlwaysAvailableTab(workspacePanel, effectiveVariant, activePanels),
            "Plugins" to buildAlwaysAvailableTab(pluginsPanel, effectiveVariant, activePanels),
        )
    }

    private fun buildAccentTab(
        variant: AyuVariant?,
        activePanels: MutableList<AyuIslandsSettingsPanel>,
    ): JComponent =
        panel {
            if (variant == null) {
                buildAyuThemeRequiredMessage("accent colors")
            } else {
                accentPanel.buildPanel(this@panel, variant)
                elementsPanel.buildPanel(this@panel, variant)
                activePanels += accentPanel
                activePanels += elementsPanel
                buildResetAllSettingsRow()
            }
        }

    private fun buildFontTab(activePanels: MutableList<AyuIslandsSettingsPanel>): JComponent =
        panel {
            fontPresetPanel.buildFontTab(this@panel)
            activePanels += fontPresetPanel
        }

    private fun buildGlowTab(activePanels: MutableList<AyuIslandsSettingsPanel>): JComponent =
        panel {
            effectsPanel.buildGlowPanel(this@panel)
            activePanels += effectsPanel
        }

    private fun buildAyuOnlyTab(
        variant: AyuVariant?,
        sectionName: String,
        settingsPanel: AyuIslandsSettingsPanel,
        activePanels: MutableList<AyuIslandsSettingsPanel>,
    ): JComponent =
        panel {
            if (variant == null) {
                buildAyuThemeRequiredMessage(sectionName)
            } else {
                settingsPanel.buildPanel(this@panel, variant)
                activePanels += settingsPanel
            }
        }

    private fun buildAlwaysAvailableTab(
        settingsPanel: AyuIslandsSettingsPanel,
        variant: AyuVariant,
        activePanels: MutableList<AyuIslandsSettingsPanel>,
    ): JComponent =
        panel {
            settingsPanel.buildPanel(this@panel, variant)
            activePanels += settingsPanel
        }

    private fun Panel.buildResetAllSettingsRow() {
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

    private fun resolveTabAccentColor(
        settings: AyuIslandsSettings,
        variant: AyuVariant?,
    ): Color =
        if (variant == null) {
            JBUI.CurrentTheme.Link.Foreground.ENABLED
        } else {
            decodeAccentColor(settings, variant)
        }

    private fun decodeAccentColor(
        settings: AyuIslandsSettings,
        variant: AyuVariant,
    ): Color =
        try {
            Color.decode(settings.getAccentForVariant(variant))
        } catch (exception: NumberFormatException) {
            log.warn("Invalid accent color for ${variant.name}, using theme default", exception)
            JBUI.CurrentTheme.Link.Foreground.ENABLED
        }

    private fun buildRootPanel(
        pluginVersion: String,
        variant: AyuVariant?,
        tabs: JBTabbedPane,
    ): com.intellij.openapi.ui.DialogPanel =
        panel {
            row {
                scaleIcon()?.let { icon(it) }
                label("v$pluginVersion")
                    .applyToComponent { font = JBUI.Fonts.smallFont() }
            }
            row {
                val status = if (LicenseChecker.isLicensedOrGrace()) "Licensed" else ""
                val themeStatus = variant?.let { "${it.name} variant" } ?: "External theme"
                comment("$themeStatus $status".trim())
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

            row {
                cell(tabs)
                    .resizableColumn()
                    .align(Align.FILL)
            }
        }

    private fun Panel.buildAyuThemeRequiredMessage(sectionName: String) {
        row {
            comment("Activate an Ayu Islands theme to configure $sectionName.")
        }
    }

    private fun configureSettingsTabsForResize(tabs: JBTabbedPane) {
        tabs.minimumSize = Dimension(0, 0)
    }

    private fun createSettingsTabs(
        contentTabs: List<Pair<String, JComponent>>,
        accentColor: Color,
        selectedIndex: Int,
        onSelectedIndexChanged: (Int) -> Unit,
    ): JBTabbedPane {
        val tabs = JBTabbedPane()
        configureSettingsTabsForResize(tabs)
        for ((title, content) in contentTabs) {
            tabs.addTab(title, createScrollableTabContent(content))
        }

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

        tabs.selectedIndex = selectedIndex.coerceIn(0, contentTabCount - 1)
        tabs.addChangeListener {
            onSelectedIndexChanged(tabs.selectedIndex)
        }
        return tabs
    }

    private fun createScrollableTabContent(content: JComponent): JComponent =
        JBScrollPane(WidthTrackingTabContent(content)).apply {
            border = JBUI.Borders.empty()
            viewportBorder = JBUI.Borders.empty()
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            minimumSize = Dimension(0, 0)
        }

    private class WidthTrackingTabContent(
        content: JComponent,
    ) : JPanel(BorderLayout()),
        Scrollable {
        init {
            isOpaque = false
            minimumSize = Dimension(0, 0)
            add(content, BorderLayout.CENTER)
        }

        override fun getPreferredScrollableViewportSize(): Dimension = Dimension(0, preferredSize.height)

        override fun getScrollableUnitIncrement(
            visibleRect: Rectangle,
            orientation: Int,
            direction: Int,
        ): Int = JBUI.scale(SCROLL_UNIT_INCREMENT)

        override fun getScrollableBlockIncrement(
            visibleRect: Rectangle,
            orientation: Int,
            direction: Int,
        ): Int =
            if (orientation == SwingConstants.VERTICAL) {
                (visibleRect.height - JBUI.scale(SCROLL_UNIT_INCREMENT))
                    .coerceAtLeast(JBUI.scale(SCROLL_UNIT_INCREMENT))
            } else {
                (visibleRect.width - JBUI.scale(SCROLL_UNIT_INCREMENT))
                    .coerceAtLeast(JBUI.scale(SCROLL_UNIT_INCREMENT))
            }

        override fun getScrollableTracksViewportWidth(): Boolean = true

        override fun getScrollableTracksViewportHeight(): Boolean = false

        private companion object {
            const val SCROLL_UNIT_INCREMENT = 16
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
        vcsColorPanel.reset()
        syntaxPanel.reset()
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

    override fun isModified(): Boolean = builtPanels.any { it.isModified() }

    override fun apply() {
        super.apply()

        for (section in builtPanels) {
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
        for (section in builtPanels) {
            section.reset()
        }
    }
}
