package dev.ayuislands.settings

import com.intellij.ui.ColorUtil
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AccentColor
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.UIManager

/**
 * Accent color selection panel with rectangular preset grid, custom-color trigger, and reset link.
 *
 * Layout:
 * ```
 * [  +  ]  |  [1] [2] [3] [4] [5] [6]
 *  Reset   |  [7] [8] [9] [10][11][12]
 * ```
 */
class AccentColorPanel(
    presets: List<AccentColor>,
    private val onPresetSelected: (AccentColor) -> Unit,
    private val onCustomTrigger: () -> Unit,
    private val onReset: () -> Unit,
) : JPanel(BorderLayout(0, 0)) {
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

    private val presetPanels: List<PresetComponent>
    private val customTriggerPanel: CustomTriggerComponent
    private val resetLabel: ResetLabel
    private val presetGrid: JPanel

    init {
        isOpaque = false

        presetPanels = presets.map { PresetComponent(it) }
        customTriggerPanel = CustomTriggerComponent()
        resetLabel = ResetLabel()

        presetGrid = JPanel(GridLayout(GRID_ROWS, GRID_COLUMNS, GRID_GAP, GRID_GAP))
        presetGrid.isOpaque = false
        for (panel in presetPanels) {
            presetGrid.add(panel)
        }

        val leftColumn = JPanel()
        leftColumn.layout = BoxLayout(leftColumn, BoxLayout.Y_AXIS)
        leftColumn.isOpaque = false
        leftColumn.add(customTriggerPanel)
        leftColumn.add(Box.createVerticalStrut(GRID_GAP))

        val resetWrapper = JPanel(FlowLayout(FlowLayout.CENTER, 0, 0))
        resetWrapper.isOpaque = false
        resetWrapper.add(resetLabel)
        leftColumn.add(resetWrapper)

        val separator = SeparatorLine()

        val leftWithSeparator = JPanel(BorderLayout(SEPARATOR_GAP, 0))
        leftWithSeparator.isOpaque = false
        leftWithSeparator.add(leftColumn, BorderLayout.CENTER)
        leftWithSeparator.add(separator, BorderLayout.EAST)

        add(leftWithSeparator, BorderLayout.WEST)
        add(Box.createHorizontalStrut(SEPARATOR_GAP), BorderLayout.CENTER)
        add(presetGrid, BorderLayout.EAST)
    }

    override fun doLayout() {
        val gridHeight = PANEL_HEIGHT * GRID_ROWS + GRID_GAP
        val triggerHeight = gridHeight
        customTriggerPanel.preferredSize = Dimension(customTriggerPanel.preferredSize.width, triggerHeight)
        super.doLayout()
    }

    private inner class PresetComponent(
        private val accent: AccentColor,
    ) : JComponent() {
        init {
            preferredSize = Dimension(PRESET_WIDTH, PANEL_HEIGHT)
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

                val shape =
                    RoundRectangle2D.Float(
                        0f,
                        0f,
                        width.toFloat(),
                        height.toFloat(),
                        PANEL_ARC,
                        PANEL_ARC,
                    )

                g2.color = color
                g2.fill(shape)

                if (!isEnabled) {
                    paintDisabledOverlay(g2, shape)
                }

                if (isSelected) {
                    drawCheckmark(g2, color, width, height)
                }
            } finally {
                g2.dispose()
            }
        }
    }

    private inner class CustomTriggerComponent : JComponent() {
        init {
            preferredSize = Dimension(PRESET_WIDTH, PANEL_HEIGHT * GRID_ROWS + GRID_GAP)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Custom color"
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(event: MouseEvent) {
                        if (!isEnabled) return
                        onCustomTrigger()
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

                val shape =
                    RoundRectangle2D.Float(
                        0f,
                        0f,
                        width.toFloat(),
                        height.toFloat(),
                        PANEL_ARC,
                        PANEL_ARC,
                    )

                val currentCustom = customColor
                val isCustomActive = currentCustom != null && selectedPreset == null

                if (isCustomActive) {
                    val color = Color.decode(currentCustom)
                    g2.color = color
                    g2.fill(shape)

                    if (!isEnabled) {
                        paintDisabledOverlay(g2, shape)
                    }

                    drawCheckmark(g2, color, width, height)
                } else {
                    val borderColor =
                        UIManager.getColor("Component.borderColor")
                            ?: UIManager.getColor("Separator.foreground")
                            ?: Color.GRAY
                    g2.color = borderColor
                    g2.stroke = BasicStroke(1f)
                    g2.draw(shape)

                    if (!isEnabled) {
                        paintDisabledOverlay(g2, shape)
                    }

                    val foreground =
                        UIManager.getColor("Label.foreground") ?: Color.GRAY
                    g2.color = foreground
                    g2.font = g2.font.deriveFont(Font.PLAIN, PLUS_FONT_SIZE)
                    val metrics = g2.fontMetrics
                    val plusText = "+"
                    val textX = (width - metrics.stringWidth(plusText)) / 2
                    val textY = (height - metrics.height) / 2 + metrics.ascent
                    g2.drawString(plusText, textX, textY)
                }
            } finally {
                g2.dispose()
            }
        }
    }

    private inner class ResetLabel : JComponent() {
        private var isHovered = false

        init {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(PRESET_WIDTH, PANEL_HEIGHT)
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(event: MouseEvent) {
                        if (!isEnabled) return
                        onReset()
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

        override fun paintComponent(graphics: Graphics) {
            val g2 = graphics.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

                val linkColor =
                    JBUI.CurrentTheme.Link.Foreground.ENABLED
                g2.color =
                    if (isEnabled) {
                        linkColor
                    } else {
                        UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
                    }
                g2.font = g2.font.deriveFont(Font.PLAIN, RESET_FONT_SIZE)

                val metrics = g2.fontMetrics
                val resetText = "Reset"
                val textX = (width - metrics.stringWidth(resetText)) / 2
                val textY = (height - metrics.height) / 2 + metrics.ascent
                g2.drawString(resetText, textX, textY)

                if (isHovered && isEnabled) {
                    val lineY = textY + 1
                    g2.drawLine(textX, lineY, textX + metrics.stringWidth(resetText), lineY)
                }
            } finally {
                g2.dispose()
            }
        }
    }

    private inner class SeparatorLine : JComponent() {
        init {
            preferredSize = Dimension(1, 0)
        }

        override fun paintComponent(graphics: Graphics) {
            val g2 = graphics.create() as Graphics2D
            try {
                val separatorColor =
                    UIManager.getColor("Separator.foreground")
                        ?: UIManager.getColor("Component.borderColor")
                        ?: Color.GRAY
                g2.color = separatorColor
                g2.drawLine(0, 0, 0, height)
            } finally {
                g2.dispose()
            }
        }
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

    private fun drawCheckmark(
        g2: Graphics2D,
        background: Color,
        componentWidth: Int,
        componentHeight: Int,
    ) {
        val checkColor =
            if (ColorUtil.isDark(background)) {
                Color.WHITE
            } else {
                Color(DARK_TEXT_RGB)
            }
        g2.color = checkColor
        g2.stroke = BasicStroke(CHECKMARK_STROKE, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

        val centerX = componentWidth / 2
        val centerY = componentHeight / 2
        val size = CHECKMARK_SIZE

        g2.drawLine(centerX - size, centerY, centerX - 1, centerY + size)
        g2.drawLine(centerX - 1, centerY + size, centerX + size, centerY - size + 1)
    }

    companion object {
        private const val PANEL_HEIGHT = 28
        private const val PRESET_WIDTH = 56
        private const val PANEL_ARC = 8f
        private const val GRID_GAP = 6
        private const val GRID_ROWS = 2
        private const val GRID_COLUMNS = 6
        private const val SEPARATOR_GAP = 12
        private const val CHECKMARK_STROKE = 2f
        private const val CHECKMARK_SIZE = 4
        private const val DARK_TEXT_RGB = 0x1F2430
        private const val DISABLED_OVERLAY_ALPHA = 0.45f
        private const val PLUS_FONT_SIZE = 16f
        private const val RESET_FONT_SIZE = 12f
    }
}
