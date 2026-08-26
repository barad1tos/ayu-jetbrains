package dev.ayuislands.settings

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import dev.ayuislands.AyuPlugin
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
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

internal fun SettingsApplyResult.Failed.toConfigurationException(): ConfigurationException {
    val causeSuffix =
        cause.localizedMessage
            ?.takeIf { it.isNotBlank() }
            ?.let { " Cause: $it." }
            .orEmpty()
    val skippedSuffix =
        if (skipped.isEmpty()) {
            ""
        } else {
            " Skipped: ${skipped.joinToString()}."
        }
    val message =
        "Failed to apply $failed settings.$causeSuffix$skippedSuffix " +
            "Review the IDE log and retry Apply."
    return ConfigurationException(message).also {
        it.initCause(cause)
    }
}

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

    private var session: SettingsSession? = null

    override fun createPanel(): DialogPanel {
        closeSession()
        val pluginVersion = resolvePluginVersion()
        val variant = AyuVariant.detect()
        val nextSession = SettingsSession(::refreshGlow)
        val contentTabs =
            AyuSettingsComposition(
                variant = variant,
                session = nextSession,
                contextProject = AccentApplicator.resolveFocusedProject(),
            ).buildContentTabs()
        session = nextSession

        val settings = AyuIslandsSettings.getInstance()
        val state = settings.state
        val accentColor = resolveTabAccentColor(settings, variant)
        val tabs =
            createSettingsTabs(
                contentTabs = contentTabs,
                accentColor = accentColor,
                selectedIndex = state.settingsSelectedTab,
            ) { selectedIndex ->
                AyuIslandsSettings.getInstance().state.settingsSelectedTab = selectedIndex
            }
        val badges = installSettingsBadges(tabs, contentTabs.map { it.first }, accentColor)

        return buildRootPanel(pluginVersion, variant, tabs, badges)
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
        badges: SettingsBadgeController?,
    ): DialogPanel =
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

            badges?.let { controller ->
                row {
                    comment(controller.headerText)
                    link("Review") { controller.jumpToFirstPending() }
                }.visibleIf(controller.headerVisible)
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

    override fun disposeUIResources() {
        try {
            activeTimers.forEach { it.stop() }
            activeTimers.clear()
            closeSession()
        } finally {
            SettingsBadges.clearSessionWiring()
            super.disposeUIResources()
        }
    }

    override fun isModified(): Boolean = session?.isModified() ?: false

    override fun apply() {
        super.apply()
        when (val result = session?.apply()) {
            null,
            SettingsApplyResult.Applied,
            -> Unit
            is SettingsApplyResult.Failed -> {
                log.warn("Settings apply failed in ${result.failed}", result.cause)
                throw result.toConfigurationException()
            }
        }
    }

    override fun reset() {
        super.reset()
        session?.reset()
    }

    private fun refreshGlow() {
        val glowEnabled = AyuIslandsSettings.getInstance().state.glowEnabled
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

    private fun closeSession() {
        session?.cancel()?.forEach { failure ->
            log.warn("Settings cancel failed for ${failure.participant}", failure.cause)
        }
        session?.close()?.forEach { failure ->
            log.warn("Settings cleanup failed for ${failure.participant}", failure.cause)
        }
        session = null
    }
}
