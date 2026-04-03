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
import java.awt.Image
import javax.swing.ImageIcon

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

        // Wire accent color changes to element's preview
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
                java.awt.Color.decode(settings.getAccentForVariant(variant))
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

        // Community link tabs — click opens browser, hover expands label
        tabs.addTab("", javax.swing.JPanel())
        tabs.setTabComponentAt(
            contentTabCount,
            createLinkTab("Share", "Share Your Setup", accentColor, DISCUSSIONS_SHOW_SETUP),
        )
        tabs.addTab("", javax.swing.JPanel())
        tabs.setTabComponentAt(
            contentTabCount + 1,
            createLinkTab("Feature", "Request a Feature", accentColor, DISCUSSIONS_FEATURE_REQUESTS),
        )

        var previousTab = state.settingsSelectedTab.coerceIn(0, contentTabCount - 1)
        tabs.selectedIndex = previousTab
        tabs.addChangeListener {
            val selected = tabs.selectedIndex
            if (selected >= contentTabCount) {
                tabs.selectedIndex = previousTab
            } else {
                previousTab = selected
                AyuIslandsSettings.getInstance().state.settingsSelectedTab = selected
            }
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
        accentColor: java.awt.Color,
        url: String,
    ): javax.swing.JLabel {
        val label = javax.swing.JLabel(shortText)
        label.font = JBUI.Fonts.label()
        label.cursor =
            java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        val defaultColor = label.foreground
        var expandTimer: javax.swing.Timer? = null
        var collapseTimer: javax.swing.Timer? = null

        label.addMouseListener(
            object : java.awt.event.MouseAdapter() {
                override fun mouseEntered(event: java.awt.event.MouseEvent) {
                    collapseTimer?.stop()
                    label.foreground = accentColor
                    val startLength = label.text.length
                    val targetLength = fullText.length
                    if (startLength >= targetLength) return
                    val startTime = System.currentTimeMillis()
                    val duration = ((targetLength - startLength) * EXPAND_MS_PER_CHAR).toLong()
                    expandTimer =
                        javax.swing.Timer(EXPAND_FRAME_MS) {
                            val elapsed = System.currentTimeMillis() - startTime
                            val progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                            val chars = startLength + ((targetLength - startLength) * progress).toInt()
                            label.text = fullText.substring(0, chars)
                            if (progress >= 1f) {
                                label.text = fullText
                                expandTimer?.stop()
                            }
                        }
                    expandTimer.start()
                }

                override fun mouseExited(event: java.awt.event.MouseEvent) {
                    expandTimer?.stop()
                    label.foreground = defaultColor
                    val startLength = label.text.length
                    val targetLength = shortText.length
                    if (startLength <= targetLength) return
                    val startTime = System.currentTimeMillis()
                    val duration = ((startLength - targetLength) * EXPAND_MS_PER_CHAR).toLong()
                    collapseTimer =
                        javax.swing.Timer(EXPAND_FRAME_MS) {
                            val elapsed = System.currentTimeMillis() - startTime
                            val progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                            val chars = startLength - ((startLength - targetLength) * progress).toInt()
                            label.text = fullText.substring(0, chars)
                            if (progress >= 1f) {
                                label.text = shortText
                                collapseTimer?.stop()
                            }
                        }
                    collapseTimer.start()
                }

                override fun mouseClicked(event: java.awt.event.MouseEvent) {
                    BrowserUtil.browse(url)
                }
            },
        )
        return label
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
