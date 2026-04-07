@file:Suppress("DialogTitleCapitalization")

package dev.ayuislands.onboarding

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.ui.LafManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AYU_ACCENT_PRESETS
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentColor
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Free onboarding wizard panel with theme variant cards, accent preset
 * swatches, community links, and free-vs-premium messaging.
 *
 * Constructor is intentionally lightweight -- content loads via [Timer]
 * after the editor tab opens, avoiding the EDT freeze from
 * FileEditorManager.openFile's internal `waitBlockingAndPumpEdt`.
 */
internal class FreeOnboardingPanel(
    private val project: Project,
) : JPanel(BorderLayout()) {
    private var selectedVariant: AyuVariant? = AyuVariant.detect()
    private var selectedAccentHex: String? = resolveCurrentAccent()

    init {
        isOpaque = false
        Timer(CONTENT_LOAD_DELAY_MS) {
            if (!isDisplayable || project.isDisposed) return@Timer
            loadContent()
            revalidate()
            repaint()
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun resolveCurrentAccent(): String? {
        val variant = AyuVariant.detect() ?: return null
        val settings = AyuIslandsSettings.getInstance()
        return settings.getAccentForVariant(variant)
    }

    private fun loadContent() {
        val column = JPanel()
        column.layout = BoxLayout(column, BoxLayout.Y_AXIS)
        column.isOpaque = false
        column.border = JBUI.Borders.empty(MARGIN_V, MARGIN_H)

        column.add(buildHeaderSection())
        column.add(Box.createVerticalStrut(JBUI.scale(GAP_SECTION)))
        column.add(buildVariantCardsSection())
        column.add(Box.createVerticalStrut(JBUI.scale(GAP_SECTION)))
        column.add(buildAccentSwatchesSection())
        column.add(Box.createVerticalStrut(JBUI.scale(GAP_SECTION)))
        column.add(buildCommunityLinksSection())
        column.add(Box.createVerticalStrut(JBUI.scale(GAP_SECTION)))
        column.add(buildPremiumTeasersSection())
        column.add(Box.createVerticalStrut(JBUI.scale(GAP_SECTION)))
        column.add(buildMessagingSection())

        val centered = JPanel()
        centered.layout = BoxLayout(centered, BoxLayout.X_AXIS)
        centered.isOpaque = false
        centered.add(Box.createHorizontalGlue())
        centered.add(column)
        centered.add(Box.createHorizontalGlue())

        add(centered, BorderLayout.NORTH)
    }

    // -- Header --

    private fun buildHeaderSection(): JPanel {
        val section = createSection()

        val title = JBLabel("Welcome to Ayu Islands")
        title.font = JBFont.h1().asBold()
        title.foreground = JBColor.foreground()
        title.alignmentX = LEFT_ALIGNMENT
        section.add(title)

        section.add(Box.createVerticalStrut(JBUI.scale(GAP_SMALL)))

        val subtitle = JBLabel("Choose your look, pick an accent, join the community")
        subtitle.font = JBFont.regular()
        subtitle.foreground = JBColor(SUBTITLE_LIGHT, SUBTITLE_DARK)
        subtitle.alignmentX = LEFT_ALIGNMENT
        section.add(subtitle)

        return section
    }

    // -- Variant Cards --

    private fun buildVariantCardsSection(): JPanel {
        val section = createSection()

        val label = createSectionLabel("Theme Variant")
        section.add(label)
        section.add(Box.createVerticalStrut(JBUI.scale(GAP_SMALL)))

        val row = JPanel()
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.isOpaque = false
        row.alignmentX = LEFT_ALIGNMENT

        for ((index, variant) in AyuVariant.entries.withIndex()) {
            row.add(VariantCard(variant))
            if (index < AyuVariant.entries.lastIndex) {
                row.add(Box.createHorizontalStrut(JBUI.scale(CARD_GAP)))
            }
        }

        section.add(row)
        return section
    }

    @Suppress("UnstableApiUsage")
    private fun switchToVariant(variant: AyuVariant) {
        val lafManager = LafManager.getInstance()
        val targetName =
            variant.themeNames.firstOrNull { it.contains("Islands UI") }
                ?: variant.themeNames.first()
        val target = lafManager.installedThemes.firstOrNull { it.name == targetName } ?: return
        selectedVariant = variant
        SwingUtilities.invokeLater {
            lafManager.setCurrentLookAndFeel(target, true)
            lafManager.updateUI()
        }
    }

    // -- Accent Swatches --

    private fun buildAccentSwatchesSection(): JPanel {
        val section = createSection()

        val label = createSectionLabel("Accent Color")
        section.add(label)
        section.add(Box.createVerticalStrut(JBUI.scale(GAP_SMALL)))

        val swatchPanel = SwatchRow()
        swatchPanel.alignmentX = LEFT_ALIGNMENT
        section.add(swatchPanel)

        return section
    }

    private fun applyAccentPreset(preset: AccentColor) {
        val settings = AyuIslandsSettings.getInstance()
        val variant = selectedVariant ?: AyuVariant.detect() ?: return
        when (variant) {
            AyuVariant.MIRAGE -> settings.state.mirageAccent = preset.hex
            AyuVariant.DARK -> settings.state.darkAccent = preset.hex
            AyuVariant.LIGHT -> settings.state.lightAccent = preset.hex
        }
        selectedAccentHex = preset.hex
        AccentApplicator.apply(preset.hex)
    }

    // -- Community Links --

    private fun buildCommunityLinksSection(): JPanel {
        val section = createSection()

        val label = createSectionLabel("Community")
        section.add(label)
        section.add(Box.createVerticalStrut(JBUI.scale(GAP_SMALL)))

        val row = JPanel()
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.isOpaque = false
        row.alignmentX = LEFT_ALIGNMENT

        row.add(
            createLinkButton("Share Your Setup") {
                BrowserUtil.browse(OnboardingUrls.DISCUSSIONS_SHOW_SETUP)
            },
        )
        row.add(Box.createHorizontalStrut(JBUI.scale(CARD_GAP)))
        row.add(
            createLinkButton("Feature Requests") {
                BrowserUtil.browse(OnboardingUrls.DISCUSSIONS_FEATURE_REQUESTS)
            },
        )

        section.add(row)
        return section
    }

    // -- Premium Teasers --

    private fun buildPremiumTeasersSection(): JPanel {
        val section = createSection()

        val label = createSectionLabel("Try Premium")
        section.add(label)
        section.add(Box.createVerticalStrut(JBUI.scale(GAP_SMALL)))

        val row = JPanel()
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.isOpaque = false
        row.alignmentX = LEFT_ALIGNMENT

        row.add(
            PremiumTeaserCard(
                title = "Glow effects",
                subtitle = "Soft / Sharp / Gradient",
                message = "Unlock glow effects",
            ),
        )
        row.add(Box.createHorizontalStrut(JBUI.scale(CARD_GAP)))
        row.add(
            PremiumTeaserCard(
                title = "Font presets",
                subtitle = "Whisper / Ambient / Neon / Cyberpunk",
                message = "Unlock font presets",
            ),
        )

        section.add(row)
        return section
    }

    // -- Free vs Premium Messaging --

    private fun buildMessagingSection(): JPanel {
        val section = createSection()

        val freeLabel = JBLabel("\u2713  Free forever: theme variants, accent colors, community")
        freeLabel.font = JBFont.regular()
        freeLabel.foreground = JBColor(FREE_TEXT_LIGHT, FREE_TEXT_DARK)
        freeLabel.alignmentX = LEFT_ALIGNMENT
        section.add(freeLabel)

        section.add(Box.createVerticalStrut(JBUI.scale(GAP_SMALL)))

        val premiumLabel = JBLabel("\uD83D\uDD12  Premium trial: glow effects, font presets, and more")
        premiumLabel.font = JBFont.regular()
        premiumLabel.foreground = JBColor(PREMIUM_TEXT_LIGHT, PREMIUM_TEXT_DARK)
        premiumLabel.alignmentX = LEFT_ALIGNMENT
        section.add(premiumLabel)

        return section
    }

    // -- Helpers --

    private fun createSection(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.isOpaque = false
        panel.alignmentX = LEFT_ALIGNMENT
        return panel
    }

    private fun createSectionLabel(text: String): JBLabel {
        val label = JBLabel(text)
        label.font = JBFont.medium().asBold()
        label.foreground = JBColor.foreground()
        label.alignmentX = LEFT_ALIGNMENT
        return label
    }

    private fun createLinkButton(
        text: String,
        onClick: () -> Unit,
    ): JPanel {
        val button =
            object : JPanel() {
                private var hovered = false

                init {
                    layout = BorderLayout()
                    isOpaque = false
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    border = JBUI.Borders.empty(BTN_PADDING_V, BTN_PADDING_H)
                    val size = Dimension(JBUI.scale(LINK_BTN_WIDTH), JBUI.scale(BTN_HEIGHT))
                    preferredSize = size
                    maximumSize = size
                    minimumSize = size

                    val label = JBLabel(text)
                    label.horizontalAlignment = JBLabel.CENTER
                    label.foreground = JBColor.foreground()
                    label.font = label.font.deriveFont(Font.PLAIN, JBUI.scale(BTN_FONT_SIZE).toFloat())
                    add(label, BorderLayout.CENTER)

                    addMouseListener(
                        object : MouseAdapter() {
                            override fun mouseEntered(event: MouseEvent) {
                                hovered = true
                                repaint()
                            }

                            override fun mouseExited(event: MouseEvent) {
                                hovered = false
                                repaint()
                            }

                            override fun mouseClicked(event: MouseEvent) {
                                onClick()
                            }
                        },
                    )
                }

                override fun paintComponent(graphics: Graphics) {
                    val g2 = graphics as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val arc = JBUI.scale(BTN_ARC)
                    val borderColor = JBColor(LINK_BORDER_LIGHT, LINK_BORDER_DARK)

                    if (hovered) {
                        g2.color = JBColor(LINK_HOVER_LIGHT, LINK_HOVER_DARK)
                        g2.fillRoundRect(0, 0, width, height, arc, arc)
                    }
                    g2.color = borderColor
                    g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)

                    super.paintComponent(graphics)
                }
            }
        return button
    }

    // -- Inner component: Variant Card --

    private inner class VariantCard(
        private val variant: AyuVariant,
    ) : JPanel() {
        private var hovered = false

        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(CARD_PADDING)
            val size = Dimension(JBUI.scale(CARD_WIDTH), JBUI.scale(CARD_HEIGHT))
            preferredSize = size
            minimumSize = size
            maximumSize = size

            val nameLabel = JBLabel(variant.name.lowercase().replaceFirstChar { it.uppercase() })
            nameLabel.font = nameLabel.font.deriveFont(Font.BOLD, JBUI.scale(CARD_NAME_SIZE).toFloat())
            nameLabel.foreground = JBColor.foreground()
            nameLabel.alignmentX = LEFT_ALIGNMENT
            add(nameLabel)
            add(Box.createVerticalGlue())

            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseEntered(event: MouseEvent) {
                        hovered = true
                        repaint()
                    }

                    override fun mouseExited(event: MouseEvent) {
                        hovered = false
                        repaint()
                    }

                    override fun mouseClicked(event: MouseEvent) {
                        switchToVariant(variant)
                        this@FreeOnboardingPanel.repaint()
                    }
                },
            )
        }

        override fun paintComponent(graphics: Graphics) {
            val g2 = graphics as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(CARD_ARC)

            // Card fill with variant tint
            val tint = Color.decode(variant.neutralGray)
            g2.color = Color(tint.red, tint.green, tint.blue, CARD_BG_ALPHA)
            g2.fillRoundRect(0, 0, width, height, arc, arc)

            // Accent indicator strip at top
            val accent = Color.decode(variant.defaultAccent)
            val stripHeight = JBUI.scale(INDICATOR_STRIP_HEIGHT)
            g2.color = accent
            g2.fillRoundRect(0, 0, width, stripHeight + arc, arc, arc)
            // Cover the bottom rounded corners of the strip
            g2.fillRect(0, stripHeight, width, arc)

            // Selected border
            val isSelected = selectedVariant == variant
            if (isSelected) {
                g2.color = accent
                g2.stroke = BasicStroke(SELECTED_BORDER_WIDTH)
                g2.drawRoundRect(1, 1, width - 2, height - 2, arc, arc)
            } else if (hovered) {
                g2.color = Color(accent.red, accent.green, accent.blue, HOVER_BORDER_ALPHA)
                g2.stroke = BasicStroke(1f)
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            } else {
                val borderColor = JBColor(CARD_BORDER_LIGHT, CARD_BORDER_DARK)
                g2.color = borderColor
                g2.stroke = BasicStroke(1f)
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            }

            super.paintComponent(graphics)
        }
    }

    // -- Inner component: Premium Teaser Card --

    private inner class PremiumTeaserCard(
        private val title: String,
        private val subtitle: String,
        private val message: String,
    ) : JPanel() {
        private var hovered = false

        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(CARD_PADDING)
            val size = Dimension(JBUI.scale(CARD_WIDTH), JBUI.scale(CARD_HEIGHT))
            preferredSize = size
            minimumSize = size
            maximumSize = size

            val titleLabel = JBLabel(title)
            titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, JBUI.scale(CARD_NAME_SIZE).toFloat())
            titleLabel.foreground = JBColor(TEASER_TITLE_LIGHT, TEASER_TITLE_DARK)
            titleLabel.alignmentX = LEFT_ALIGNMENT
            add(titleLabel)
            add(Box.createVerticalStrut(JBUI.scale(TEASER_TEXT_GAP)))

            val subtitleLabel = JBLabel(subtitle)
            subtitleLabel.font = subtitleLabel.font.deriveFont(Font.PLAIN, JBUI.scale(TEASER_SUBTITLE_SIZE).toFloat())
            subtitleLabel.foreground = JBColor(TEASER_SUBTITLE_LIGHT, TEASER_SUBTITLE_DARK)
            subtitleLabel.alignmentX = LEFT_ALIGNMENT
            add(subtitleLabel)
            add(Box.createVerticalGlue())

            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseEntered(event: MouseEvent) {
                        hovered = true
                        repaint()
                    }

                    override fun mouseExited(event: MouseEvent) {
                        hovered = false
                        repaint()
                    }

                    override fun mouseClicked(event: MouseEvent) {
                        LicenseChecker.requestLicense(message)
                    }
                },
            )
        }

        override fun paintComponent(graphics: Graphics) {
            val g2 = graphics as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(CARD_ARC)

            // Greyed fill
            val fill = JBColor(TEASER_BG_LIGHT, TEASER_BG_DARK)
            g2.color = Color(fill.red, fill.green, fill.blue, TEASER_BG_ALPHA)
            g2.fillRoundRect(0, 0, width, height, arc, arc)

            // Border
            val borderColor =
                if (hovered) {
                    JBColor(TEASER_BORDER_HOVER_LIGHT, TEASER_BORDER_HOVER_DARK)
                } else {
                    JBColor(CARD_BORDER_LIGHT, CARD_BORDER_DARK)
                }
            g2.color = borderColor
            g2.stroke = BasicStroke(1f)
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)

            super.paintComponent(graphics)

            // Lock icon in top-right corner
            val lockIcon = AllIcons.Nodes.Padlock
            val iconX = width - lockIcon.iconWidth - JBUI.scale(TEASER_LOCK_INSET)
            val iconY = JBUI.scale(TEASER_LOCK_INSET)
            lockIcon.paintIcon(this, g2, iconX, iconY)
        }
    }

    // -- Inner component: Swatch Row --

    private inner class SwatchRow : JPanel() {
        init {
            isOpaque = false
            val totalWidth = AYU_ACCENT_PRESETS.size * (JBUI.scale(SWATCH_SIZE) + JBUI.scale(SWATCH_GAP))
            val size = Dimension(totalWidth, JBUI.scale(SWATCH_SIZE + SWATCH_TOOLTIP_MARGIN))
            preferredSize = size
            maximumSize = size
            minimumSize = size
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(event: MouseEvent) {
                        val index = swatchIndexFromX(event.x)
                        if (index in AYU_ACCENT_PRESETS.indices) {
                            applyAccentPreset(AYU_ACCENT_PRESETS[index])
                            repaint()
                        }
                    }
                },
            )
        }

        private fun swatchIndexFromX(mouseX: Int): Int {
            val step = JBUI.scale(SWATCH_SIZE) + JBUI.scale(SWATCH_GAP)
            return mouseX / step
        }

        override fun paintComponent(graphics: Graphics) {
            val g2 = graphics as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val size = JBUI.scale(SWATCH_SIZE)
            val gap = JBUI.scale(SWATCH_GAP)

            for ((index, preset) in AYU_ACCENT_PRESETS.withIndex()) {
                val x = index * (size + gap)
                val y = 0

                // Swatch fill
                g2.color = Color.decode(preset.hex)
                g2.fillOval(x, y, size, size)

                // Selected ring
                val isSelected = selectedAccentHex.equals(preset.hex, ignoreCase = true)
                if (isSelected) {
                    g2.color = JBColor.foreground()
                    g2.stroke = BasicStroke(SELECTED_RING_WIDTH)
                    val inset = SELECTED_RING_INSET
                    g2.drawOval(x - inset, y - inset, size + inset * 2, size + inset * 2)
                }
            }
        }
    }

    companion object {
        // Timing
        private const val CONTENT_LOAD_DELAY_MS = 100

        // Layout margins
        private const val MARGIN_V = 40
        private const val MARGIN_H = 40
        private const val GAP_SECTION = 28
        private const val GAP_SMALL = 8

        // Card dimensions
        private const val CARD_WIDTH = 140
        private const val CARD_HEIGHT = 90
        private const val CARD_ARC = 12
        private const val CARD_PADDING = 12
        private const val CARD_GAP = 12
        private const val CARD_NAME_SIZE = 14
        private const val CARD_BG_ALPHA = 60
        private const val INDICATOR_STRIP_HEIGHT = 4

        // Card borders
        private val CARD_BORDER_LIGHT = Color(0xCC, 0xCC, 0xCC)
        private val CARD_BORDER_DARK = Color(0x3A, 0x3F, 0x4B)
        private const val HOVER_BORDER_ALPHA = 120
        private const val SELECTED_BORDER_WIDTH = 2f

        // Swatch dimensions
        private const val SWATCH_SIZE = 28
        private const val SWATCH_GAP = 8
        private const val SWATCH_TOOLTIP_MARGIN = 4
        private const val SELECTED_RING_WIDTH = 2f
        private const val SELECTED_RING_INSET = 2

        // Button dimensions
        private const val BTN_ARC = 10
        private const val BTN_PADDING_V = 6
        private const val BTN_PADDING_H = 16
        private const val BTN_HEIGHT = 32
        private const val BTN_FONT_SIZE = 12
        private const val LINK_BTN_WIDTH = 160

        // Link button colors
        private val LINK_BORDER_LIGHT = Color(0xCC, 0xCC, 0xCC)
        private val LINK_BORDER_DARK = Color(0x3A, 0x3F, 0x4B)
        private val LINK_HOVER_LIGHT = Color(0xEE, 0xEE, 0xEE)
        private val LINK_HOVER_DARK = Color(0x2A, 0x2F, 0x3A)

        // Text colors
        private val SUBTITLE_LIGHT = Color(0x6B, 0x6B, 0x6B)
        private val SUBTITLE_DARK = Color(0x8A, 0x91, 0x99)
        private val FREE_TEXT_LIGHT = Color(0x2A, 0x8A, 0x3A)
        private val FREE_TEXT_DARK = Color(0x7E, 0xC9, 0x8B)
        private val PREMIUM_TEXT_LIGHT = Color(0x99, 0x99, 0x99)
        private val PREMIUM_TEXT_DARK = Color(0x5C, 0x63, 0x6E)

        // Premium teaser card colors
        private const val TEASER_BG_ALPHA = 40
        private const val TEASER_TEXT_GAP = 4
        private const val TEASER_SUBTITLE_SIZE = 11
        private const val TEASER_LOCK_INSET = 8
        private val TEASER_BG_LIGHT = Color(0xB0, 0xB0, 0xB0)
        private val TEASER_BG_DARK = Color(0x55, 0x5C, 0x68)
        private val TEASER_TITLE_LIGHT = Color(0x80, 0x80, 0x80)
        private val TEASER_TITLE_DARK = Color(0x9A, 0xA0, 0xAA)
        private val TEASER_SUBTITLE_LIGHT = Color(0xA0, 0xA0, 0xA0)
        private val TEASER_SUBTITLE_DARK = Color(0x70, 0x77, 0x82)
        private val TEASER_BORDER_HOVER_LIGHT = Color(0x99, 0x99, 0x99)
        private val TEASER_BORDER_HOVER_DARK = Color(0x5A, 0x60, 0x6C)
    }
}
