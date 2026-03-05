package dev.ayuislands.settings

import com.intellij.ui.ColorUtil
import dev.ayuislands.accent.AccentColor
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

/** A 2x6 grid of rounded color swatches with a checkmark on the selected color. */
class ColorSwatchPanel(
    colors: List<AccentColor>,
    private val onColorSelected: (AccentColor) -> Unit,
) : JPanel(GridLayout(GRID_ROWS, GRID_COLS, GRID_GAP, GRID_GAP)) {
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
                        selectedColor = accent.hex
                        onColorSelected(accent)
                    }
                },
            )
        }

        override fun paintComponent(graphics: Graphics) {
            val g2 = graphics.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                val color = Color.decode(accent.hex)
                g2.color = color
                g2.fill(
                    RoundRectangle2D.Float(
                        0f,
                        0f,
                        width.toFloat(),
                        height.toFloat(),
                        ARC_RADIUS,
                        ARC_RADIUS,
                    ),
                )

                if (accent.hex.equals(selectedColor, ignoreCase = true)) {
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
                if (ColorUtil.isDark(background)) Color.WHITE else Color(DARK_TEXT_R, DARK_TEXT_G, DARK_TEXT_B)
            g2.color = checkColor
            g2.stroke = BasicStroke(STROKE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

            val centerX = width / 2
            val centerY = height / 2
            val size = CHECKMARK_SIZE

            // Check path: short left stroke then longer right stroke
            g2.drawLine(centerX - size, centerY, centerX - 1, centerY + size)
            g2.drawLine(centerX - 1, centerY + size, centerX + size, centerY - size + 1)
        }
    }

    companion object {
        private const val GRID_ROWS = 2
        private const val GRID_COLS = 6
        private const val GRID_GAP = 6
        private const val SWATCH_SIZE = 28
        private const val ARC_RADIUS = 6f
        private const val STROKE_WIDTH = 2f
        private const val CHECKMARK_SIZE = 4
        private const val DARK_TEXT_R = 0x1F
        private const val DARK_TEXT_G = 0x24
        private const val DARK_TEXT_B = 0x30
    }
}
