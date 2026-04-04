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
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.licensing.LicenseChecker
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
        const val DISCUSSIONS_SHOW_SETUP =
            "https://github.com/barad1tos/ayu-jetbrains/discussions/categories/show-your-setup"
        const val DISCUSSIONS_FEATURE_REQUESTS =
            "https://github.com/barad1tos/ayu-jetbrains/discussions/categories/feature-requests"
    }

    private val activeTimers = mutableListOf<Timer>()

    private val appearancePanel = AyuIslandsAppearancePanel()
    private val accentPanel = AyuIslandsAccentPanel()
    private val elementsPanel = AyuIslandsElementsPanel()
    private val workspacePanel = WorkspacePanel()
    private val pluginsPanel = PluginsPanel()
    private val fontPresetPanel = FontPresetPanel()
    private val effectsPanel = AyuIslandsEffectsPanel()

    private val panels: List<AyuIslandsSettingsPanel> =
        listOf(
            appearancePanel,
            accentPanel,
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

        // Wire accent color changes to elements preview
        accentPanel.onAccentChanged = { hex -> elementsPanel.updatePreviewAccent(hex) }

        // Build tab content panels eagerly via DSL
        val accentTab =
            panel {
                appearancePanel.buildPanel(this@panel, variant)
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
            } catch (_: NumberFormatException) {
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
        var expandTimer: Timer? = null
        var collapseTimer: Timer? = null

        label.addMouseListener(
            object : MouseAdapter() {
                override fun mouseEntered(event: MouseEvent) {
                    collapseTimer?.stop()
                    label.foreground = accentColor
                    expandTimer = animateText(label, label.text.length, fullText.length, fullText)
                }

                override fun mouseExited(event: MouseEvent) {
                    expandTimer?.stop()
                    label.foreground = defaultColor
                    collapseTimer = animateText(label, label.text.length, shortText.length, fullText)
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
        elementsPanel.reset()
        fontPresetPanel.reset()
        effectsPanel.reset()
        workspacePanel.reset()
        pluginsPanel.reset()
    }

    override fun disposeUIResources() {
        activeTimers.forEach { it.stop() }
        activeTimers.clear()
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
