package dev.ayuislands.settings

import com.intellij.ui.ColorUtil
import dev.ayuislands.accent.AccentColor
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.JPanel

/** A single-row grid of rounded color swatches with a checkmark on the selected color. */
class ColorSwatchPanel(
    colors: List<AccentColor>,
    private val onColorSelected: (AccentColor) -> Unit,
) : JPanel(GridLayout(1, colors.size, GRID_GAP, 0)) {
    var selectedColor: String = ""
        set(value) {
            field = value
            repaint()
        }

    init {
        isOpaque = false
        for (accent in colors) {
            add(SwatchComponent(accent))
        }
    }

    private inner class SwatchComponent(
        private val accent: AccentColor,
    ) : JComponent() {
        init {
            preferredSize = Dimension(SWATCH_SIZE, SWATCH_SIZE)
            toolTipText = accent.name
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(event: MouseEvent) {
                        if (!isEnabled) return
                        selectedColor = accent.hex
                        onColorSelected(accent)
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
                val isSelected = accent.hex.equals(selectedColor, ignoreCase = true)

                val shape =
                    RoundRectangle2D.Float(
                        0f,
                        0f,
                        width.toFloat(),
                        height.toFloat(),
                        ARC_RADIUS,
                        ARC_RADIUS,
                    )

                g2.color = color
                g2.fill(shape)

                if (!isEnabled) {
                    g2.composite =
                        AlphaComposite.getInstance(
                            AlphaComposite.SRC_OVER,
                            DISABLED_OVERLAY_ALPHA,
                        )
                    g2.color = javax.swing.UIManager.getColor("Panel.background") ?: background
                    g2.fill(shape)
                    g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f)
                }

                if (isSelected) {
                    drawCheckmark(g2, color)
                }
            } finally {
                g2.dispose()
            }
        }

        private fun drawCheckmark(
            g2: Graphics2D,
            background: Color,
        ) {
            val checkColor =
                if (ColorUtil.isDark(background)) {
                    Color.WHITE
                } else {
                    Color(DARK_TEXT_R, DARK_TEXT_G, DARK_TEXT_B)
                }
            g2.color = checkColor
            g2.stroke = BasicStroke(CHECKMARK_STROKE, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

            val centerX = width / 2
            val centerY = height / 2
            val size = CHECKMARK_SIZE

            g2.drawLine(centerX - size, centerY, centerX - 1, centerY + size)
            g2.drawLine(centerX - 1, centerY + size, centerX + size, centerY - size + 1)
        }
    }

    companion object {
        private const val GRID_GAP = 6
        private const val SWATCH_SIZE = 28
        private const val ARC_RADIUS = 6f
        private const val CHECKMARK_STROKE = 2f
        private const val CHECKMARK_SIZE = 4
        private const val DARK_TEXT_R = 0x1F
        private const val DARK_TEXT_G = 0x24
        private const val DARK_TEXT_B = 0x30
        private const val DISABLED_OVERLAY_ALPHA = 0.45f
    }
}
