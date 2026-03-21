package dev.ayuislands.settings

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AccentColor
import dev.ayuislands.glow.GlowRenderer
import dev.ayuislands.glow.GlowStyle
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.UIManager
import kotlin.math.exp
import kotlin.math.sin

/**
 * Accent color selection panel with three visual columns: links (left), shuffle (center), preset grid (right).
 *
 * Layout:
 * ```
 *   Lavender                    [swatch][swatch][swatch][swatch]
 *   Custom     🎲 Shuffle       [swatch][swatch][swatch][swatch]
 *   Reset                       [swatch][swatch][swatch][swatch]
 * ```
 *
 * Left column stacks vertically: shade name (row 1), Custom link (row 2), Reset link (row 3).
 * Shuffle column contains a dice icon and "Shuffle" text, both clickable.
 * The right side is the 4x3 preset color grid.
 */
class AccentColorPanel(
    presets: List<AccentColor>,
    private val onPresetSelected: (AccentColor) -> Unit,
    private val onCustomTrigger: () -> Unit,
    private val onReset: () -> Unit,
    private val onShuffleTrigger: (() -> Unit)? = null,
    private val onThirteenthSwatchClicked: ((String) -> Unit)? = null,
) : JPanel(BorderLayout(COLUMN_GAP, 0)) {
    private val presetList: List<AccentColor> = presets
    private val heroGlowRenderer = GlowRenderer()

    var selectedPreset: String? = null
        set(value) {
            field = value
            repaint()
        }

    var customColor: String? = null
        set(value) {
            field = value
            repaint()
        }

    /** Hex color of the swatch that should have a hero glow border (set by rotation). */
    var heroGlowHex: String? = null
        set(value) {
            field = value
            repaint()
        }

    /** Whether the hero glow is active (only during preset rotation mode). */
    var heroGlowActive: Boolean = false
        set(value) {
            field = value
            repaint()
        }

    private val presetPanels: List<PresetComponent>
    private val shadeNameLabel: ShadeNameLabel
    private val customLink: CustomLink
    private val resetLabel: ResetLabel
    private val shuffleColumn: ShuffleColumn?
    private val thirteenthSwatch: ThirteenthSwatch?
    private val presetGrid: JPanel

    /** Hex color currently shown in the 13th swatch, or null if hidden. */
    val thirteenthSwatchColor: String?
        get() = thirteenthSwatch?.colorHex

    init {
        isOpaque = false

        presetPanels = presets.map { PresetComponent(it) }
        shadeNameLabel = ShadeNameLabel()
        customLink = CustomLink()
        resetLabel = ResetLabel()
        shuffleColumn =
            if (onShuffleTrigger != null) ShuffleColumn() else null
        thirteenthSwatch =
            if (onShuffleTrigger != null) ThirteenthSwatch() else null

        presetGrid = JPanel(GridLayout(GRID_ROWS, GRID_COLUMNS, GRID_GAP, GRID_GAP))
        presetGrid.isOpaque = false
        for (panel in presetPanels) {
            presetGrid.add(panel)
        }

        val leftColumn = JPanel()
        leftColumn.layout = BoxLayout(leftColumn, BoxLayout.Y_AXIS)
        leftColumn.isOpaque = false
        leftColumn.add(shadeNameLabel)
        leftColumn.add(Box.createVerticalStrut(LEFT_COLUMN_GAP))
        leftColumn.add(customLink)
        leftColumn.add(Box.createVerticalStrut(LEFT_COLUMN_GAP))
        leftColumn.add(resetLabel)

        add(leftColumn, BorderLayout.WEST)

        if (shuffleColumn != null) {
            val contentWrapper = JPanel(BorderLayout(SHUFFLE_COLUMN_GAP, 0))
            contentWrapper.isOpaque = false
            contentWrapper.add(shuffleColumn, BorderLayout.WEST)

            if (thirteenthSwatch != null) {
                thirteenthSwatch.isVisible = false

                val gridArea = JPanel(BorderLayout(GRID_GAP, 0))
                gridArea.isOpaque = false
                gridArea.add(thirteenthSwatch, BorderLayout.WEST)
                gridArea.add(presetGrid, BorderLayout.CENTER)
                contentWrapper.add(gridArea, BorderLayout.CENTER)
            } else {
                contentWrapper.add(presetGrid, BorderLayout.CENTER)
            }

            add(contentWrapper, BorderLayout.CENTER)
        } else {
            add(presetGrid, BorderLayout.CENTER)
        }
    }

    fun showThirteenthSwatch(hex: String) {
        thirteenthSwatch?.slideIn(hex)
    }

    fun showThirteenthSwatchImmediate(hex: String) {
        thirteenthSwatch?.showImmediate(hex)
    }

    fun hideThirteenthSwatch() {
        thirteenthSwatch?.hideSwatch()
    }

    private fun getSelectedAccentHex(): String? = selectedPreset ?: customColor

    private fun getSelectedShadeName(): String {
        val presetHex = selectedPreset
        if (presetHex != null) {
            val preset = presetList.firstOrNull { it.hex.equals(presetHex, ignoreCase = true) }
            return preset?.name ?: presetHex
        }
        val custom = customColor
        if (custom != null) return custom.uppercase()
        return ""
    }

    private fun resolveEditorFontFamily(): String =
        try {
            EditorColorsManager
                .getInstance()
                .globalScheme
                .fontPreferences
                .fontFamily
        } catch (_: IllegalStateException) {
            Font.MONOSPACED
        }

    private inner class PresetComponent(
        private val accent: AccentColor,
    ) : JComponent() {
        init {
            preferredSize = Dimension(0, PANEL_HEIGHT)
            toolTipText = accent.name
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(event: MouseEvent) {
                        if (!isEnabled) return
                        selectedPreset = accent.hex
                        onPresetSelected(accent)
                    }
                },
            )
        }

        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            cursor =
                if (enabled) {
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                } else {
                    Cursor.getDefaultCursor()
                }
            repaint()
        }

        override fun paintComponent(graphics: Graphics) {
            val g2 = graphics.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                val color = Color.decode(accent.hex)
                val isSelected = accent.hex.equals(selectedPreset, ignoreCase = true)
                val borderColor = Color(BORDER_RGB)

                if (heroGlowActive && accent.hex.equals(heroGlowHex, ignoreCase = true)) {
                    heroGlowRenderer.ensureCache(color, GlowStyle.SOFT, HERO_GLOW_INTENSITY, HERO_GLOW_WIDTH)
                    heroGlowRenderer.paintGlow(g2, Rectangle(0, 0, width, height), HERO_GLOW_WIDTH, PANEL_ARC.toInt())
                }

                val shape =
                    RoundRectangle2D.Float(
                        BORDER_INSET,
                        BORDER_INSET,
                        width.toFloat() - BORDER_INSET * 2,
                        height.toFloat() - BORDER_INSET * 2,
                        PANEL_ARC,
                        PANEL_ARC,
                    )

                g2.color = color
                g2.fill(shape)

                if (!isEnabled) {
                    paintDisabledOverlay(g2, shape)
                }

                if (isSelected) {
                    g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, SELECTED_OVERLAY_ALPHA)
                    g2.color = borderColor
                    g2.fill(shape)
                    g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f)
                }

                g2.color = borderColor
                g2.stroke = BasicStroke(1f)
                g2.draw(shape)
            } finally {
                g2.dispose()
            }
        }
    }

    private inner class ShadeNameLabel : JComponent() {
        init {
            preferredSize = Dimension(LEFT_COLUMN_WIDTH, PANEL_HEIGHT)
        }

        override fun paintComponent(graphics: Graphics) {
            val shadeName = getSelectedShadeName()
            if (shadeName.isEmpty()) return

            val accentHex = getSelectedAccentHex() ?: return

            val g2 = graphics.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

                val accentColor = Color.decode(accentHex)
                val editorFamily = resolveEditorFontFamily()
                g2.font = Font(editorFamily, Font.ITALIC, SPECIMEN_FONT_SIZE)
                g2.color = accentColor

                val metrics = g2.fontMetrics
                val textY = (height - metrics.height) / 2 + metrics.ascent
                g2.drawString(shadeName, 0, textY)
            } finally {
                g2.dispose()
            }
        }
    }

    private abstract inner class LinkLabel(
        private val text: String,
        private val onClick: () -> Unit,
    ) : JComponent() {
        private var isHovered = false

        init {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(event: MouseEvent) {
                        if (!isEnabled) return
                        onClick()
                    }

                    override fun mouseEntered(event: MouseEvent) {
                        isHovered = true
                        repaint()
                    }

                    override fun mouseExited(event: MouseEvent) {
                        isHovered = false
                        repaint()
                    }
                },
            )
        }

        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            cursor =
                if (enabled) {
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                } else {
                    Cursor.getDefaultCursor()
                }
            repaint()
        }

        override fun getPreferredSize(): Dimension {
            val metrics = getFontMetrics(font.deriveFont(Font.PLAIN, LINK_FONT_SIZE))
            return Dimension(metrics.stringWidth(text) + 1, PANEL_HEIGHT)
        }

        override fun paintComponent(graphics: Graphics) {
            val g2 = graphics.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

                val linkColor = JBUI.CurrentTheme.Link.Foreground.ENABLED
                g2.color =
                    if (isEnabled) {
                        linkColor
                    } else {
                        UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
                    }
                g2.font = g2.font.deriveFont(Font.PLAIN, LINK_FONT_SIZE)

                val metrics = g2.fontMetrics
                val textY = (height - metrics.height) / 2 + metrics.ascent
                g2.drawString(text, 0, textY)

                if (isHovered && isEnabled) {
                    val lineY = textY + 1
                    g2.drawLine(0, lineY, metrics.stringWidth(text), lineY)
                }
            } finally {
                g2.dispose()
            }
        }
    }

    private inner class CustomLink : LinkLabel("Custom", onCustomTrigger)

    private inner class ResetLabel : LinkLabel("Reset", onReset)

    private inner class ShuffleColumn : JComponent() {
        private var glowAlpha = BREATHE_MIN_ALPHA
        private var breathePhase = 0.0
        private val breatheTimer =
            Timer(BREATHE_INTERVAL_MS) { updateBreathe() }
        private val diceIcon = DiceIcon()

        init {
            layout = null
            isOpaque = false
            preferredSize = Dimension(diceIcon.preferredSize.width + SHUFFLE_SIDE_PAD * 2, 0)

            add(diceIcon)

            breatheTimer.start()
        }

        override fun doLayout() {
            val iconPref = diceIcon.preferredSize
            val customCenterY = customLink.y + customLink.height / 2
            val targetY = customCenterY - iconPref.height / 2
            val targetX = (width - iconPref.width) / 2
            diceIcon.setBounds(targetX, targetY, iconPref.width, iconPref.height)
        }

        private fun updateBreathe() {
            breathePhase += BREATHE_STEP
            val sinNormalized =
                (sin(breathePhase).toFloat() + 1f) / 2f
            glowAlpha = BREATHE_MIN_ALPHA + BREATHE_RANGE * sinNormalized
            repaint()
        }

        fun triggerBounce() {
            diceIcon.triggerBounce()
        }

        fun dispose() {
            breatheTimer.stop()
            diceIcon.disposeBounce()
        }

        private inner class DiceIcon : JComponent() {
            private var bounceOffset = 0f
            private var bounceFrame = 0
            private val shuffleTextWidth: Int
            private val bounceTimer =
                Timer(ANIMATION_FRAME_MS) {
                    bounceFrame++
                    if (bounceFrame > BOUNCE_TOTAL_FRAMES) {
                        (it.source as Timer).stop()
                        bounceOffset = 0f
                    } else {
                        val t = bounceFrame / BOUNCE_TOTAL_FRAMES.toFloat()
                        val decay = exp(-BOUNCE_DECAY_RATE * t)
                        val oscillation = sin(t * Math.PI * BOUNCE_OSCILLATIONS)
                        bounceOffset = (-BOUNCE_MAX_PIXELS * decay * oscillation).toFloat()
                    }
                    repaint()
                }

            init {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                alignmentY = CENTER_ALIGNMENT

                val labelFont = Font(Font.DIALOG, Font.PLAIN, LINK_FONT_SIZE.toInt())
                val labelMetrics = getFontMetrics(labelFont)
                shuffleTextWidth = labelMetrics.stringWidth(SHUFFLE_LABEL)

                val totalWidth = DICE_ICON_SIZE + DICE_LABEL_GAP + shuffleTextWidth + 1
                val diceHeight = DICE_ICON_SIZE + BOUNCE_MAX_PIXELS * 2
                preferredSize = Dimension(totalWidth, diceHeight)
                maximumSize = Dimension(totalWidth, diceHeight)
                addMouseListener(
                    object : MouseAdapter() {
                        override fun mouseClicked(event: MouseEvent) {
                            if (!isEnabled) return
                            triggerBounce()
                            onShuffleTrigger?.invoke()
                        }
                    },
                )
            }

            fun triggerBounce() {
                bounceFrame = 0
                bounceOffset = 0f
                bounceTimer.restart()
            }

            fun disposeBounce() {
                bounceTimer.stop()
            }

            override fun paintComponent(graphics: Graphics) {
                val g2 = graphics.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

                    val accentHex =
                        selectedPreset ?: heroGlowHex ?: DEFAULT_ACCENT_HEX
                    val diceColor =
                        try {
                            Color.decode(accentHex)
                        } catch (_: NumberFormatException) {
                            Color(DEFAULT_ACCENT_RGB)
                        }

                    // Draw dice emoji with breathe glow
                    g2.composite = AlphaComposite.SrcOver.derive(glowAlpha)
                    g2.font = Font(Font.DIALOG, Font.PLAIN, DICE_FONT_SIZE)
                    g2.color = diceColor

                    val gv = g2.font.createGlyphVector(g2.fontRenderContext, DICE_TEXT)
                    val vb = gv.visualBounds
                    val emojiX = ((DICE_ICON_SIZE - vb.width) / 2.0 - vb.x).toFloat() + 2
                    val emojiY = ((height - vb.height) / 2.0 - vb.y).toFloat() + bounceOffset
                    g2.drawGlyphVector(gv, emojiX, emojiY)

                    // Draw "Shuffle" text at full opacity with link color
                    g2.composite = AlphaComposite.SrcOver.derive(1f)
                    val linkColor = JBUI.CurrentTheme.Link.Foreground.ENABLED
                    g2.color = linkColor
                    g2.font = Font(Font.DIALOG, Font.PLAIN, LINK_FONT_SIZE.toInt())

                    val labelMetrics = g2.fontMetrics
                    val labelX = DICE_ICON_SIZE + DICE_LABEL_GAP
                    val labelY = (height - labelMetrics.height) / 2 + labelMetrics.ascent
                    g2.drawString(SHUFFLE_LABEL, labelX, labelY)
                } finally {
                    g2.dispose()
                }
            }
        }
    }

    private inner class ThirteenthSwatch : JComponent() {
        private fun computeEqualSwatchWidth(): Int {
            val gridAreaWidth = presetGrid.parent?.width ?: return PANEL_HEIGHT
            if (gridAreaWidth <= 0) return PANEL_HEIGHT
            return (gridAreaWidth - GRID_COLUMNS * GRID_GAP) / (GRID_COLUMNS + 1)
        }

        var colorHex: String? = null
        private var slideProgress = 0f
        private val swatchGlowRenderer = GlowRenderer()
        private val slideTimer =
            Timer(ANIMATION_FRAME_MS) {
                slideProgress += 1f / SLIDE_TOTAL_FRAMES
                if (slideProgress >= 1f) {
                    slideProgress = 1f
                    (it.source as Timer).stop()
                }
                revalidate()
                repaint()
            }

        init {
            isOpaque = false
            isVisible = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(event: MouseEvent) {
                        if (!isEnabled) return
                        val hex = colorHex ?: return
                        onThirteenthSwatchClicked?.invoke(hex)
                    }
                },
            )
        }

        override fun getPreferredSize(): Dimension {
            if (colorHex == null || slideProgress <= 0f) {
                return Dimension(0, PANEL_HEIGHT)
            }
            val targetWidth = computeEqualSwatchWidth()
            val animatedWidth = (targetWidth * easeOut(slideProgress)).toInt()
            return Dimension(animatedWidth, PANEL_HEIGHT)
        }

        override fun getMinimumSize(): Dimension = Dimension(0, PANEL_HEIGHT)

        override fun getMaximumSize(): Dimension = Dimension(computeEqualSwatchWidth(), PANEL_HEIGHT)

        fun slideIn(hex: String) {
            colorHex = hex

            slideProgress = 0f
            isVisible = true

            slideTimer.restart()
        }

        fun showImmediate(hex: String) {
            colorHex = hex

            slideProgress = 1f
            isVisible = true

            slideTimer.stop()
            revalidate()
            repaint()
        }

        fun hideSwatch() {
            slideTimer.stop()
            colorHex = null
            slideProgress = 0f
            isVisible = false

            revalidate()
            parent?.repaint()
        }

        fun disposeTimers() {
            slideTimer.stop()
        }

        override fun paintComponent(graphics: Graphics) {
            val hex = colorHex ?: return
            if (slideProgress <= 0f) return

            val g2 = graphics.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                val color = Color.decode(hex)
                val borderColor = Color(BORDER_RGB)

                val swatchWidth = computeEqualSwatchWidth().coerceAtMost(width)
                val swatchX = (width - swatchWidth).coerceAtLeast(0)
                val row2Swatch = presetPanels.getOrNull(GRID_COLUMNS)
                val swatchY = row2Swatch?.y ?: (PANEL_HEIGHT + GRID_GAP)
                val swatchHeight = row2Swatch?.height ?: PANEL_HEIGHT

                // Hero glow
                swatchGlowRenderer.ensureCache(color, GlowStyle.SOFT, HERO_GLOW_INTENSITY, HERO_GLOW_WIDTH)
                swatchGlowRenderer.paintGlow(
                    g2,
                    Rectangle(swatchX, swatchY, swatchWidth, swatchHeight),
                    HERO_GLOW_WIDTH,
                    PANEL_ARC.toInt(),
                )

                val shape =
                    RoundRectangle2D.Float(
                        swatchX + BORDER_INSET,
                        swatchY + BORDER_INSET,
                        swatchWidth.toFloat() - BORDER_INSET * 2,
                        swatchHeight.toFloat() - BORDER_INSET * 2,
                        PANEL_ARC,
                        PANEL_ARC,
                    )

                g2.color = color
                g2.fill(shape)

                g2.color = borderColor
                g2.stroke = BasicStroke(1f)
                g2.draw(shape)
            } finally {
                g2.dispose()
            }
        }
    }

    override fun removeNotify() {
        super.removeNotify()
        shuffleColumn?.dispose()
        thirteenthSwatch?.disposeTimers()
    }

    private fun paintDisabledOverlay(
        g2: Graphics2D,
        shape: RoundRectangle2D.Float,
    ) {
        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, DISABLED_OVERLAY_ALPHA)
        g2.color = UIManager.getColor("Panel.background") ?: background
        g2.fill(shape)
        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f)
    }

    companion object {
        private const val PANEL_HEIGHT = 26
        private const val PANEL_ARC = 8f
        private const val GRID_GAP = 2
        private const val LEFT_COLUMN_GAP = 4
        private const val COLUMN_GAP = 6
        private const val LEFT_COLUMN_WIDTH = 120
        private const val GRID_ROWS = 3
        private const val GRID_COLUMNS = 4
        private const val SPECIMEN_FONT_SIZE = 15
        private const val BORDER_RGB = 0x4E5A6E
        private const val BORDER_INSET = 0.5f
        private const val SELECTED_OVERLAY_ALPHA = 0.55f
        private const val DISABLED_OVERLAY_ALPHA = 0.45f
        private const val LINK_FONT_SIZE = 12f

        private const val HERO_GLOW_INTENSITY = 30
        private const val HERO_GLOW_WIDTH = 4

        private const val BREATHE_INTERVAL_MS = 50
        private const val BREATHE_STEP = 0.05
        private const val BREATHE_MIN_ALPHA = 0.7f
        private const val BREATHE_RANGE = 0.3f
        private const val SHUFFLE_LABEL = "Shuffle"
        private const val DEFAULT_ACCENT_HEX = "#73D0FF"
        private const val DEFAULT_ACCENT_RGB = 0x73D0FF

        private const val SHUFFLE_COLUMN_GAP = 4
        private const val DICE_FONT_SIZE = 20
        private const val DICE_ICON_SIZE = 40
        private const val DICE_LABEL_GAP = 2
        private const val DICE_TEXT = "\uD83C\uDFB2"

        private const val SHUFFLE_SIDE_PAD = 6
        private const val ANIMATION_FRAME_MS = 16
        private const val SLIDE_TOTAL_FRAMES = 18
        private const val BOUNCE_MAX_PIXELS = 8
        private const val BOUNCE_TOTAL_FRAMES = 25
        private const val BOUNCE_DECAY_RATE = 4.0
        private const val BOUNCE_OSCILLATIONS = 3.0

        private fun easeOut(t: Float): Float {
            val complement = 1f - t
            return 1f - complement * complement * complement
        }
    }
}
