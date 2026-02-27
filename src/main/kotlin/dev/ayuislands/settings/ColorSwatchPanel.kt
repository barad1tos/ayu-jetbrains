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

/** A 2x5 grid of rounded color swatches with checkmark on the selected color. */
class ColorSwatchPanel(
    private val colors: List<AccentColor>,
    private val onColorSelected: (AccentColor) -> Unit,
) : JPanel(GridLayout(2, 5, 6, 6)) {

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

    private inner class SwatchComponent(private val accent: AccentColor) : JComponent() {

        init {
            preferredSize = Dimension(28, 28)
            toolTipText = accent.name
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    selectedColor = accent.hex
                    onColorSelected(accent)
                }
            })
        }

        override fun paintComponent(graphics: Graphics) {
            val g2 = graphics.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                val color = Color.decode(accent.hex)
                g2.color = color
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 6f, 6f))

                if (accent.hex.equals(selectedColor, ignoreCase = true)) {
                    drawCheckmark(g2, color)
                }
            } finally {
                g2.dispose()
            }
        }

        private fun drawCheckmark(g2: Graphics2D, background: Color) {
            val checkColor = if (ColorUtil.isDark(background)) Color.WHITE else Color(0x1F, 0x24, 0x30)
            g2.color = checkColor
            g2.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

            val centerX = width / 2
            val centerY = height / 2
            val size = 4

            // Check path: short left stroke then longer right stroke
            g2.drawLine(centerX - size, centerY, centerX - 1, centerY + size)
            g2.drawLine(centerX - 1, centerY + size, centerX + size, centerY - size + 1)
        }
    }
}
