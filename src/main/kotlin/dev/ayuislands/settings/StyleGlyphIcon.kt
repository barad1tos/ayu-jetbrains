package dev.ayuislands.settings

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon

/**
 * Text-glyph [Icon] for the per-category Bold / Italic style toggles in the
 * Ayu Islands syntax Custom drill-down. [com.intellij.icons.AllIcons] ships no
 * stable Bold / Italic vector, so the cue is a single drawn glyph: `"B"` in
 * [Font.BOLD] or `"I"` in [Font.ITALIC], centered in a square chip.
 */
class StyleGlyphIcon(
    private val glyph: String,
    private val glyphStyle: Int,
    private val foreground: Color,
    private val background: Color? = null,
    private val border: Color? = null,
    private val cell: Int = JBUI.scale(ICON_CELL),
    private val glyphSize: Int = JBUI.scale(ICON_GLYPH),
) : Icon {
    override fun getIconWidth(): Int = cell

    override fun getIconHeight(): Int = cell

    override fun paintIcon(
        component: Component?,
        graphics: Graphics,
        x: Int,
        y: Int,
    ) {
        val canvas = graphics.create() as? Graphics2D ?: return
        try {
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            canvas.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
            )
            val shape =
                RoundRectangle2D.Float(
                    x + BORDER_INSET,
                    y + BORDER_INSET,
                    cell - BORDER_INSET * 2,
                    cell - BORDER_INSET * 2,
                    JBUI.scale(ARC).toFloat(),
                    JBUI.scale(ARC).toFloat(),
                )
            background?.let { fill ->
                canvas.color = fill
                canvas.fill(shape)
            }
            border?.let { strokeColor ->
                canvas.color = strokeColor
                canvas.stroke = BasicStroke(1f)
                canvas.draw(shape)
            }
            canvas.color = foreground
            val baseFont = component?.font ?: UIUtil.getLabelFont()
            canvas.font = baseFont.deriveFont(glyphStyle, glyphSize.toFloat())
            val metrics = canvas.fontMetrics
            val glyphWidth = metrics.stringWidth(glyph)
            val drawX = x + (cell - glyphWidth) / 2
            val drawY = y + (cell - metrics.height) / 2 + metrics.ascent
            canvas.drawString(glyph, drawX, drawY)
        } finally {
            canvas.dispose()
        }
    }

    companion object {
        const val ICON_CELL = 20
        const val ICON_GLYPH = 16

        private const val ARC = 6
        private const val BORDER_INSET = 0.5f
    }
}
