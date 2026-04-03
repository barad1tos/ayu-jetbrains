@file:Suppress("DialogTitleCapitalization")

package dev.ayuislands.onboarding

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontPresetApplicator
import dev.ayuislands.font.FontSettings
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.settings.AyuIslandsSettings
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.Timer

/**
 * Onboarding wizard panel with hero SVG, preset cards, and action buttons.
 *
 * Constructor is intentionally lightweight — content loads via [Timer]
 * after the editor tab opens, avoiding the EDT freeze from
 * [FileEditorManager.openFile]'s internal `waitBlockingAndPumpEdt`.
 */
internal class OnboardingPanel(
    private val project: Project,
    private val virtualFile: VirtualFile,
) : JPanel(BorderLayout()) {
    private var activeGlowColor: Color? = null
    private var activeGlowWidth: Int = 0
    private var activeGlowAlpha: Float = 0f

    init {
        isOpaque = false
        border = JBUI.Borders.empty(OUTER_PADDING)
        Timer(CONTENT_LOAD_DELAY_MS) {
            loadContent()
            revalidate()
            repaint()
        }.apply {
            isRepeats = false
            start()
        }
    }

    override fun paintComponent(graphics: Graphics) {
        val g2 = graphics as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val glowColor = activeGlowColor
        if (glowColor != null) {
            paintGlowBorder(g2, glowColor)
        }

        super.paintComponent(graphics)
    }

    private fun paintGlowBorder(
        g2: Graphics2D,
        color: Color,
    ) {
        val inset = JBUI.scale(GLOW_INSET)
        val arc = JBUI.scale(GLOW_ARC).toDouble()
        val scaledWidth = JBUI.scale(activeGlowWidth)
        val maxAlpha = activeGlowAlpha

        for (layer in scaledWidth downTo 1) {
            val alpha = (maxAlpha * layer.toFloat() / scaledWidth).toInt().coerceIn(0, MAX_ALPHA)
            g2.color = Color(color.red, color.green, color.blue, alpha)
            val offset = (scaledWidth - layer).toDouble()
            g2.draw(
                RoundRectangle2D.Double(
                    inset + offset,
                    inset + offset,
                    width - 2.0 * inset - 2 * offset,
                    height - 2.0 * inset - 2 * offset,
                    arc,
                    arc,
                ),
            )
        }
    }

    private fun setGlowPreview(preset: GlowPreset?) {
        if (preset == null) {
            activeGlowColor = null
            activeGlowWidth = 0
            activeGlowAlpha = 0f
        } else {
            val params = PRESET_GLOW_PARAMS[preset]
            activeGlowColor = PRESET_GLOW_COLORS[preset]
            activeGlowWidth = params?.first ?: GLOW_DEFAULT_WIDTH
            activeGlowAlpha = params?.second ?: GLOW_DEFAULT_ALPHA
        }
        repaint()
    }

    private fun loadContent() {
        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.isOpaque = false
        content.maximumSize = Dimension(JBUI.scale(CONTENT_MAX_WIDTH), Int.MAX_VALUE)

        content.add(buildHeroImage())
        content.add(Box.createVerticalStrut(JBUI.scale(GAP_MEDIUM)))
        content.add(buildTitle())
        content.add(Box.createVerticalStrut(JBUI.scale(GAP_SMALL)))
        content.add(buildSubtitle())
        content.add(Box.createVerticalStrut(JBUI.scale(GAP_XLARGE)))
        content.add(buildPresetCardsRow())
        content.add(Box.createVerticalStrut(JBUI.scale(GAP_LARGE)))
        content.add(buildBottomButtons())

        val wrapper = JPanel(BorderLayout())
        wrapper.isOpaque = false
        wrapper.add(content, BorderLayout.NORTH)
        add(wrapper, BorderLayout.CENTER)
    }

    private fun buildHeroImage(): JPanel {
        val holder = JPanel()
        holder.layout = BoxLayout(holder, BoxLayout.X_AXIS)
        holder.isOpaque = false
        holder.alignmentX = CENTER_ALIGNMENT

        val icon: Icon? =
            try {
                val raw =
                    IconLoader.getIcon(
                        "/onboarding/welcome_board.svg",
                        OnboardingPanel::class.java,
                    )
                val scaledWidth = JBUI.scale(HERO_DISPLAY_WIDTH)
                val scale = scaledWidth.toFloat() / raw.iconWidth
                IconUtil.scale(raw, null, scale)
            } catch (_: Exception) {
                null
            }

        if (icon != null) {
            val label = JBLabel(icon)
            label.alignmentX = CENTER_ALIGNMENT
            holder.add(Box.createHorizontalGlue())
            holder.add(label)
            holder.add(Box.createHorizontalGlue())
        }

        return holder
    }

    private fun buildTitle(): JBLabel {
        val label = JBLabel("Welcome to Ayu Islands")
        label.font = label.font.deriveFont(Font.BOLD, JBUI.scale(TITLE_FONT_SIZE).toFloat())
        label.alignmentX = CENTER_ALIGNMENT
        return label
    }

    private fun buildSubtitle(): JBLabel {
        val label = JBLabel("Pick a preset to set your glow and font in one click.")
        label.foreground = JBColor.GRAY
        label.alignmentX = CENTER_ALIGNMENT
        return label
    }

    private fun buildPresetCardsRow(): JPanel {
        val row = JPanel()
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.isOpaque = false
        row.alignmentX = CENTER_ALIGNMENT

        row.add(Box.createHorizontalGlue())
        for ((index, card) in PRESETS.withIndex()) {
            row.add(createCardPanel(card))
            if (index < PRESETS.lastIndex) {
                row.add(Box.createHorizontalStrut(JBUI.scale(CARD_GAP)))
            }
        }
        row.add(Box.createHorizontalGlue())
        return row
    }

    @Suppress("LongMethod")
    private fun createCardPanel(card: PresetCard): JPanel {
        val glowColor = PRESET_GLOW_COLORS[card.glowPreset] ?: ACCENT_COLOR
        val panel = this

        val cardPanel =
            object : JPanel() {
                private var hovered = false

                init {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    border = JBUI.Borders.empty(CARD_PADDING)
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    val size = Dimension(JBUI.scale(CARD_WIDTH), JBUI.scale(CARD_HEIGHT))
                    preferredSize = size
                    minimumSize = size
                    maximumSize = size

                    addMouseListener(
                        object : MouseAdapter() {
                            override fun mouseEntered(event: MouseEvent) {
                                hovered = true
                                panel.setGlowPreview(card.glowPreset)
                                repaint()
                            }

                            override fun mouseExited(event: MouseEvent) {
                                hovered = false
                                panel.setGlowPreview(null)
                                repaint()
                            }

                            override fun mouseClicked(event: MouseEvent) {
                                applyPreset(card.glowPreset, card.fontPreset)
                                closeWizard()
                            }
                        },
                    )
                }

                override fun paintComponent(graphics: Graphics) {
                    val g2 = graphics as Graphics2D
                    g2.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON,
                    )
                    val arc = JBUI.scale(CARD_ARC)

                    if (hovered) {
                        val topColor = Color(glowColor.red, glowColor.green, glowColor.blue, CARD_HOVER_TOP_ALPHA)
                        val bottomColor = Color(glowColor.red, glowColor.green, glowColor.blue, CARD_HOVER_BOTTOM_ALPHA)
                        g2.paint = GradientPaint(0f, 0f, topColor, 0f, height.toFloat(), bottomColor)
                    } else {
                        g2.color = CARD_BG_COLOR
                    }
                    g2.fillRoundRect(0, 0, width, height, arc, arc)

                    g2.color =
                        if (hovered) {
                            Color(glowColor.red, glowColor.green, glowColor.blue, CARD_BORDER_HOVER_ALPHA)
                        } else {
                            CARD_BORDER_COLOR
                        }
                    g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)

                    super.paintComponent(graphics)
                }
            }
        cardPanel.isOpaque = false

        val nameLabel = JBLabel(card.name)
        nameLabel.font = nameLabel.font.deriveFont(Font.BOLD, JBUI.scale(CARD_NAME_SIZE).toFloat())
        nameLabel.foreground = JBColor.lazy { Color.WHITE }
        nameLabel.alignmentX = LEFT_ALIGNMENT
        cardPanel.add(nameLabel)

        cardPanel.add(Box.createVerticalStrut(JBUI.scale(GAP_TINY)))

        val glowLabel = JBLabel(card.glowDescription)
        glowLabel.font = glowLabel.font.deriveFont(JBUI.scale(CARD_DESC_SIZE).toFloat())
        glowLabel.foreground = CARD_DESC_COLOR
        glowLabel.alignmentX = LEFT_ALIGNMENT
        cardPanel.add(glowLabel)

        cardPanel.add(Box.createVerticalStrut(JBUI.scale(GAP_MICRO)))

        val fontLabel = JBLabel(card.fontName)
        fontLabel.font = fontLabel.font.deriveFont(Font.ITALIC, JBUI.scale(CARD_DESC_SIZE).toFloat())
        fontLabel.foreground = CARD_DESC_COLOR
        fontLabel.alignmentX = LEFT_ALIGNMENT
        cardPanel.add(fontLabel)

        cardPanel.add(Box.createVerticalGlue())

        return cardPanel
    }

    private fun buildBottomButtons(): JPanel {
        val row = JPanel()
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.isOpaque = false
        row.alignmentX = CENTER_ALIGNMENT

        row.add(Box.createHorizontalGlue())
        row.add(createStyledButton("Keep defaults", isAccent = false) { closeWizard() })
        row.add(Box.createHorizontalStrut(JBUI.scale(CARD_GAP)))
        row.add(
            createStyledButton("Open Settings", isAccent = true) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "Ayu Islands")
                closeWizard()
            },
        )
        row.add(Box.createHorizontalGlue())
        return row
    }

    private fun createStyledButton(
        text: String,
        isAccent: Boolean,
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
                    val label = JBLabel(text)
                    label.horizontalAlignment = JBLabel.CENTER
                    label.foreground = if (isAccent) ACCENT_TEXT_COLOR else JBColor.lazy { Color.WHITE }
                    label.font = label.font.deriveFont(Font.BOLD, JBUI.scale(BTN_FONT_SIZE).toFloat())
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
                    g2.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON,
                    )
                    val arc = JBUI.scale(BTN_ARC)

                    if (isAccent) {
                        g2.color = if (hovered) ACCENT_HOVER_COLOR else ACCENT_COLOR
                    } else {
                        g2.color = if (hovered) BTN_SECONDARY_HOVER else BTN_SECONDARY_BG
                    }
                    g2.fillRoundRect(0, 0, width, height, arc, arc)

                    if (!isAccent) {
                        g2.color = CARD_BORDER_COLOR
                        g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                    }
                }
            }
        return button
    }

    private fun closeWizard() {
        FileEditorManager.getInstance(project).closeFile(virtualFile)
    }

    private fun applyPreset(
        glowPreset: GlowPreset,
        fontPreset: FontPreset,
    ) {
        val style = glowPreset.style ?: return
        val intensity = glowPreset.intensity ?: return
        val width = glowPreset.width ?: return
        val animation = glowPreset.animation ?: return
        val state = AyuIslandsSettings.getInstance().state

        state.glowEnabled = true
        state.glowStyle = style.name
        state.glowPreset = glowPreset.name
        state.setIntensityForStyle(style, intensity)
        state.setWidthForStyle(style, width)
        state.glowAnimation = animation.name

        state.fontPresetEnabled = true
        state.fontPresetName = fontPreset.name
        FontPresetApplicator.apply(
            FontSettings
                .decode(null, fontPreset)
                .copy(applyToConsole = state.fontApplyToConsole),
        )

        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                GlowOverlayManager.getInstance(project).initialize()
                GlowOverlayManager.syncGlowForAllProjects()
            }
        }

        LOG.info("Onboarding preset applied: ${glowPreset.name}")
    }

    private data class PresetCard(
        val name: String,
        val glowDescription: String,
        val fontName: String,
        val glowPreset: GlowPreset,
        val fontPreset: FontPreset,
    )

    companion object {
        private val LOG = logger<OnboardingPanel>()

        // Timing
        private const val CONTENT_LOAD_DELAY_MS = 100

        // Layout
        private const val OUTER_PADDING = 32
        private const val CONTENT_MAX_WIDTH = 720
        private const val HERO_DISPLAY_WIDTH = 600

        // Typography
        private const val TITLE_FONT_SIZE = 26
        private const val CARD_NAME_SIZE = 15
        private const val CARD_DESC_SIZE = 11
        private const val BTN_FONT_SIZE = 13

        // Cards
        private const val CARD_ARC = 14
        private const val CARD_PADDING = 14
        private const val CARD_WIDTH = 155
        private const val CARD_HEIGHT = 110
        private const val CARD_GAP = 12

        // Buttons
        private const val BTN_ARC = 10
        private const val BTN_PADDING_V = 8
        private const val BTN_PADDING_H = 20

        // Gaps
        private const val GAP_XLARGE = 32
        private const val GAP_LARGE = 24
        private const val GAP_MEDIUM = 16
        private const val GAP_SMALL = 8
        private const val GAP_TINY = 6
        private const val GAP_MICRO = 2

        // Glow border
        private const val GLOW_INSET = 4
        private const val GLOW_ARC = 16
        private const val GLOW_DEFAULT_WIDTH = 6
        private const val GLOW_DEFAULT_ALPHA = 80f
        private const val MAX_ALPHA = 255

        // Per-preset glow width (px) and max alpha — Whisper=subtle, Cyberpunk=intense
        private val PRESET_GLOW_PARAMS =
            mapOf(
                GlowPreset.WHISPER to (4 to 50f),
                GlowPreset.AMBIENT to (7 to 70f),
                GlowPreset.NEON to (10 to 110f),
                GlowPreset.CYBERPUNK to (14 to 140f),
            )

        // Card colors (Ayu Mirage palette)
        private val ACCENT_COLOR = Color(0xFF, 0xCC, 0x66)
        private val ACCENT_HOVER_COLOR = Color(0xFF, 0xD8, 0x80)
        private val ACCENT_TEXT_COLOR = Color(0x0B, 0x0E, 0x14)
        private val CARD_BG_COLOR = Color(0x17, 0x1B, 0x24)
        private val CARD_BORDER_COLOR = Color(0x2A, 0x2F, 0x3A)
        private val CARD_DESC_COLOR = Color(0x70, 0x76, 0x80)
        private val BTN_SECONDARY_BG = Color(0x1C, 0x21, 0x2B)
        private val BTN_SECONDARY_HOVER = Color(0x24, 0x2A, 0x36)

        // Card hover alpha
        private const val CARD_HOVER_TOP_ALPHA = 40
        private const val CARD_HOVER_BOTTOM_ALPHA = 10
        private const val CARD_BORDER_HOVER_ALPHA = 100

        // Preset glow preview colors
        private val PRESET_GLOW_COLORS =
            mapOf(
                GlowPreset.WHISPER to Color(0xFF, 0xCC, 0x66),
                GlowPreset.AMBIENT to Color(0x95, 0xE6, 0xCB),
                GlowPreset.NEON to Color(0x36, 0xA3, 0xD9),
                GlowPreset.CYBERPUNK to Color(0xF0, 0x71, 0x78),
            )

        private val PRESETS =
            listOf(
                PresetCard("Whisper", "Soft glow", "Victor Mono", GlowPreset.WHISPER, FontPreset.WHISPER),
                PresetCard("Ambient", "Gradient + Breathe", "Maple Mono", GlowPreset.AMBIENT, FontPreset.AMBIENT),
                PresetCard("Neon", "Sharp Neon", "Monaspace Neon", GlowPreset.NEON, FontPreset.NEON),
                PresetCard("Cyberpunk", "Sharp + Pulse", "Monaspace Xenon", GlowPreset.CYBERPUNK, FontPreset.CYBERPUNK),
            )
    }
}
