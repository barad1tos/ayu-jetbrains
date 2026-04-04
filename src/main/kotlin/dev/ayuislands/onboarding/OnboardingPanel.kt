@file:Suppress("DialogTitleCapitalization")

package dev.ayuislands.onboarding

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
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
 * Onboarding wizard panel with full-tab SVG background, preset cards, and
 * action buttons on a frosted glass backdrop.
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

    /** Background SVG — loaded once, rescaled on resize. */
    private val heroIcon: Icon? =
        try {
            IconLoader.getIcon("/onboarding/welcome_board.svg", OnboardingPanel::class.java)
        } catch (exception: RuntimeException) {
            LOG.warn("Failed to load onboarding hero image", exception)
            null
        }

    private var cachedBackgroundIcon: Icon? = null
    private var cachedBackgroundSize: Dimension? = null

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

    override fun paintComponent(graphics: Graphics) {
        val g2 = graphics as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        paintBackground(g2)
        paintScrim(g2)

        val glowColor = activeGlowColor
        if (glowColor != null) {
            paintGlowBorder(g2, glowColor)
        }

        super.paintComponent(graphics)
    }

    /** Render SVG as full-tab background with "cover" scaling. */
    private fun paintBackground(g2: Graphics2D) {
        val icon = heroIcon ?: return
        val currentSize = Dimension(width, height)

        if (cachedBackgroundIcon == null || cachedBackgroundSize != currentSize) {
            val scale =
                maxOf(
                    width.toDouble() / icon.iconWidth,
                    height.toDouble() / icon.iconHeight,
                )
            cachedBackgroundIcon = IconUtil.scale(icon, null, scale.toFloat())
            cachedBackgroundSize = currentSize
        }

        val scaled = cachedBackgroundIcon ?: return
        val drawX = (width - scaled.iconWidth) / 2
        val drawY = (height - scaled.iconHeight) / 2
        scaled.paintIcon(this, g2, drawX, drawY)
    }

    /** Bottom gradient scrim for text readability over the image. */
    private fun paintScrim(g2: Graphics2D) {
        val scrimHeight = (height * SCRIM_FRACTION).toInt()
        g2.paint =
            GradientPaint(
                0f,
                (height - scrimHeight).toFloat(),
                SCRIM_TOP_COLOR,
                0f,
                height.toFloat(),
                SCRIM_BOTTOM_COLOR,
            )
        g2.fillRect(0, height - scrimHeight, width, scrimHeight)
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

        content.add(buildPresetCardsRow())
        content.add(Box.createVerticalStrut(JBUI.scale(GAP_LARGE)))
        content.add(buildBottomButtons())
        content.add(Box.createVerticalStrut(JBUI.scale(BOTTOM_MARGIN)))

        content.alignmentX = CENTER_ALIGNMENT

        val bottomRow = JPanel()
        bottomRow.layout = BoxLayout(bottomRow, BoxLayout.X_AXIS)
        bottomRow.isOpaque = false
        bottomRow.add(Box.createHorizontalGlue())
        bottomRow.add(content)
        bottomRow.add(Box.createHorizontalGlue())

        val wrapper = JPanel(BorderLayout())
        wrapper.isOpaque = false
        wrapper.add(bottomRow, BorderLayout.SOUTH)
        add(wrapper, BorderLayout.CENTER)
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
        val outerPanel = this

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
                                outerPanel.setGlowPreview(card.glowPreset)
                                repaint()
                            }

                            override fun mouseExited(event: MouseEvent) {
                                hovered = false
                                outerPanel.setGlowPreview(null)
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

                    // Drop shadow
                    val shadowOffset = JBUI.scale(SHADOW_OFFSET_Y)
                    for (i in SHADOW_LAYERS downTo 1) {
                        g2.color = Color(0, 0, 0, SHADOW_BASE_ALPHA * i)
                        g2.fillRoundRect(
                            i,
                            i + shadowOffset,
                            width - 2 * i,
                            height - 2 * i,
                            arc,
                            arc,
                        )
                    }

                    // Card fill
                    if (hovered) {
                        val topColor =
                            Color(
                                glowColor.red,
                                glowColor.green,
                                glowColor.blue,
                                CARD_HOVER_TOP_ALPHA,
                            )
                        val bottomColor =
                            Color(
                                glowColor.red,
                                glowColor.green,
                                glowColor.blue,
                                CARD_HOVER_BOTTOM_ALPHA,
                            )
                        g2.paint =
                            GradientPaint(
                                0f,
                                0f,
                                topColor,
                                0f,
                                height.toFloat(),
                                bottomColor,
                            )
                    } else {
                        g2.color = CARD_BG_COLOR
                    }
                    g2.fillRoundRect(0, 0, width, height, arc, arc)

                    // Glass highlight
                    val highlightHeight = JBUI.scale(HIGHLIGHT_HEIGHT)
                    val clip =
                        RoundRectangle2D.Float(
                            1f,
                            1f,
                            (width - 2).toFloat(),
                            (height - 2).toFloat(),
                            arc.toFloat(),
                            arc.toFloat(),
                        )
                    val oldClip = g2.clip
                    g2.clip(clip)
                    g2.paint =
                        GradientPaint(
                            0f,
                            1f,
                            HIGHLIGHT_TOP_COLOR,
                            0f,
                            highlightHeight.toFloat(),
                            HIGHLIGHT_BOTTOM_COLOR,
                        )
                    g2.fillRect(0, 0, width, highlightHeight)
                    g2.clip = oldClip

                    // Border
                    g2.color =
                        if (hovered) {
                            Color(
                                glowColor.red,
                                glowColor.green,
                                glowColor.blue,
                                CARD_BORDER_HOVER_ALPHA,
                            )
                        } else {
                            CARD_BORDER_COLOR
                        }
                    g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)

                    // Color dot
                    g2.color = glowColor
                    val dotSize = JBUI.scale(CARD_DOT_SIZE)
                    val dotMargin = JBUI.scale(CARD_DOT_MARGIN)
                    g2.fillOval(width - dotMargin - dotSize, dotMargin, dotSize, dotSize)

                    super.paintComponent(graphics)
                }
            }
        cardPanel.isOpaque = false

        val nameLabel = JBLabel(card.name)
        nameLabel.font =
            nameLabel.font.deriveFont(
                Font.BOLD,
                JBUI.scale(CARD_NAME_SIZE).toFloat(),
            )
        nameLabel.foreground = Color.WHITE
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
        fontLabel.font =
            fontLabel.font.deriveFont(
                Font.ITALIC,
                JBUI.scale(CARD_DESC_SIZE).toFloat(),
            )
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
        row.add(Box.createHorizontalStrut(JBUI.scale(BTN_GAP)))
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
                private var pressed = false

                init {
                    layout = BorderLayout()
                    isOpaque = false
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    border = JBUI.Borders.empty(BTN_PADDING_V, BTN_PADDING_H)
                    val btnSize = Dimension(JBUI.scale(BTN_WIDTH), JBUI.scale(BTN_HEIGHT))
                    preferredSize = btnSize
                    maximumSize = btnSize
                    minimumSize = btnSize
                    val label = JBLabel(text)
                    label.horizontalAlignment = JBLabel.CENTER
                    label.foreground = if (isAccent) ACCENT_TEXT_COLOR else BTN_SECONDARY_TEXT
                    label.font =
                        label.font.deriveFont(
                            if (isAccent) Font.BOLD else Font.PLAIN,
                            JBUI.scale(BTN_FONT_SIZE).toFloat(),
                        )
                    add(label, BorderLayout.CENTER)

                    addMouseListener(
                        object : MouseAdapter() {
                            override fun mouseEntered(event: MouseEvent) {
                                hovered = true
                                repaint()
                            }

                            override fun mouseExited(event: MouseEvent) {
                                hovered = false
                                pressed = false
                                repaint()
                            }

                            override fun mousePressed(event: MouseEvent) {
                                pressed = true
                                repaint()
                            }

                            override fun mouseReleased(event: MouseEvent) {
                                pressed = false
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
                        paintAccentButton(g2, arc)
                    } else {
                        paintSecondaryButton(g2, arc)
                    }
                }

                private fun paintAccentButton(
                    g2: Graphics2D,
                    arc: Int,
                ) {
                    val fill =
                        when {
                            pressed -> BTN_ACCENT_PRESSED
                            hovered -> ACCENT_HOVER_COLOR
                            else -> ACCENT_COLOR
                        }
                    g2.color = fill
                    g2.fillRoundRect(0, 0, width, height, arc, arc)
                    g2.color = BTN_ACCENT_BORDER
                    g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)

                    val oldClip = g2.clip
                    g2.clipRect(1, 1, width - 2, arc)
                    g2.color = BTN_ACCENT_HIGHLIGHT
                    g2.drawRoundRect(
                        HIGHLIGHT_INSET,
                        HIGHLIGHT_INSET,
                        width - HIGHLIGHT_INSET * 2 - 1,
                        height - HIGHLIGHT_INSET * 2 - 1,
                        arc - HIGHLIGHT_INSET,
                        arc - HIGHLIGHT_INSET,
                    )
                    g2.clip = oldClip
                }

                private fun paintSecondaryButton(
                    g2: Graphics2D,
                    arc: Int,
                ) {
                    val fill =
                        when {
                            pressed -> BTN_SECONDARY_PRESSED
                            hovered -> BTN_SECONDARY_HOVER
                            else -> BTN_SECONDARY_BG
                        }
                    g2.color = fill
                    g2.fillRoundRect(0, 0, width, height, arc, arc)
                    g2.color = BTN_SECONDARY_BORDER
                    g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)

                    val oldClip = g2.clip
                    g2.clipRect(1, 1, width - 2, arc)
                    g2.color = BTN_SECONDARY_HIGHLIGHT
                    g2.drawRoundRect(
                        HIGHLIGHT_INSET,
                        HIGHLIGHT_INSET,
                        width - HIGHLIGHT_INSET * 2 - 1,
                        height - HIGHLIGHT_INSET * 2 - 1,
                        arc - HIGHLIGHT_INSET,
                        arc - HIGHLIGHT_INSET,
                    )
                    g2.clip = oldClip
                }
            }
        return button
    }

    private fun closeWizard() {
        if (project.isDisposed) return
        FileEditorManager.getInstance(project).closeFile(virtualFile)
    }

    private fun logIncompletePreset(
        presetName: String,
        field: String,
    ) {
        LOG.warn("Preset $presetName missing $field, skipping apply")
    }

    private fun applyPreset(
        glowPreset: GlowPreset,
        fontPreset: FontPreset,
    ) {
        val presetName = glowPreset.name
        val style = glowPreset.style ?: return logIncompletePreset(presetName, "style")
        val intensity = glowPreset.intensity ?: return logIncompletePreset(presetName, "intensity")
        val width = glowPreset.width ?: return logIncompletePreset(presetName, "width")
        val animation = glowPreset.animation ?: return logIncompletePreset(presetName, "animation")
        val state = AyuIslandsSettings.getInstance().state

        state.glowEnabled = true
        state.glowStyle = style.name
        state.glowPreset = glowPreset.name
        state.setIntensityForStyle(style, intensity)
        state.setWidthForStyle(style, width)
        state.glowAnimation = animation.name

        state.fontPresetEnabled = true
        state.fontPresetName = fontPreset.name
        try {
            FontPresetApplicator.apply(
                FontSettings
                    .decode(null, fontPreset)
                    .copy(applyToConsole = state.fontApplyToConsole),
            )
        } catch (exception: RuntimeException) {
            LOG.warn("Failed to apply font preset ${fontPreset.name}", exception)
        }

        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                try {
                    GlowOverlayManager.getInstance(project).initialize()
                    GlowOverlayManager.syncGlowForAllProjects()
                } catch (exception: RuntimeException) {
                    LOG.warn("Failed to sync glow after onboarding preset", exception)
                }
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

        // Button highlight
        private const val HIGHLIGHT_INSET = 1

        // Typography
        private const val CARD_NAME_SIZE = 15
        private const val CARD_DESC_SIZE = 11
        private const val BTN_FONT_SIZE = 13

        // Cards
        private const val CARD_ARC = 14
        private const val CARD_PADDING = 16
        private const val CARD_WIDTH = 155
        private const val CARD_HEIGHT = 130
        private const val CARD_GAP = 12

        // Card dot indicator
        private const val CARD_DOT_SIZE = 8
        private const val CARD_DOT_MARGIN = 12

        // Card shadow
        private const val SHADOW_LAYERS = 4
        private const val SHADOW_OFFSET_Y = 2
        private const val SHADOW_BASE_ALPHA = 12

        // Card glass highlight
        private const val HIGHLIGHT_HEIGHT = 3
        private val HIGHLIGHT_TOP_COLOR = Color(255, 255, 255, 8)
        private val HIGHLIGHT_BOTTOM_COLOR = Color(255, 255, 255, 0)

        // Buttons
        private const val BTN_ARC = 10
        private const val BTN_PADDING_V = 8
        private const val BTN_PADDING_H = 20
        private const val BTN_GAP = 16
        private const val BTN_WIDTH = 160
        private const val BTN_HEIGHT = 36

        // Scrim
        private const val SCRIM_FRACTION = 0.65
        private val SCRIM_TOP_COLOR = Color(0x0B, 0x0E, 0x14, 0)
        private val SCRIM_BOTTOM_COLOR = Color(0x0B, 0x0E, 0x14, 220)

        // Gaps
        private const val BOTTOM_MARGIN = 120
        private const val GAP_LARGE = 24
        private const val GAP_TINY = 8
        private const val GAP_MICRO = 2

        // Glow border
        private const val GLOW_INSET = 4
        private const val GLOW_ARC = 16
        private const val GLOW_DEFAULT_WIDTH = 6
        private const val GLOW_DEFAULT_ALPHA = 80f
        private const val MAX_ALPHA = 255

        // Per-preset glow params
        private val PRESET_GLOW_PARAMS =
            mapOf(
                GlowPreset.WHISPER to (4 to 50f),
                GlowPreset.AMBIENT to (7 to 70f),
                GlowPreset.NEON to (10 to 110f),
                GlowPreset.CYBERPUNK to (14 to 140f),
            )

        // Colors (Ayu Mirage palette)
        private val ACCENT_COLOR = Color(0xFF, 0xCC, 0x66)
        private val ACCENT_HOVER_COLOR = Color(0xFF, 0xD8, 0x80)
        private val ACCENT_TEXT_COLOR = Color(0x0B, 0x0E, 0x14)
        private val CARD_BG_COLOR = Color(0x17, 0x1B, 0x24)
        private val CARD_BORDER_COLOR = Color(0x2A, 0x2F, 0x3A)
        private val CARD_DESC_COLOR = Color(0x70, 0x76, 0x80)

        // Card hover alpha
        private const val CARD_HOVER_TOP_ALPHA = 40
        private const val CARD_HOVER_BOTTOM_ALPHA = 10
        private const val CARD_BORDER_HOVER_ALPHA = 100

        // Accent button
        private val BTN_ACCENT_BORDER = Color(0xC0, 0x96, 0x30)
        private val BTN_ACCENT_HIGHLIGHT = Color(0xFF, 0xE0, 0x99, 60)
        private val BTN_ACCENT_PRESSED = Color(0xD9, 0xAD, 0x50)

        // Secondary button
        private val BTN_SECONDARY_BG = Color(0x18, 0x1C, 0x25)
        private val BTN_SECONDARY_HOVER = Color(0x1E, 0x23, 0x2E)
        private val BTN_SECONDARY_PRESSED = Color(0x14, 0x18, 0x20)
        private val BTN_SECONDARY_BORDER = Color(0x3A, 0x40, 0x4C)
        private val BTN_SECONDARY_HIGHLIGHT = Color(0xFF, 0xFF, 0xFF, 10)
        private val BTN_SECONDARY_TEXT = Color(0xB0, 0xB8, 0xC4)

        // Preset glow colors
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
