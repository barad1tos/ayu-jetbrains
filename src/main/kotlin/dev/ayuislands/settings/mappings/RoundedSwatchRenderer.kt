package dev.ayuislands.settings.mappings

import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AYU_ACCENT_PRESETS
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.JTable
import javax.swing.UIManager
import javax.swing.table.DefaultTableCellRenderer

/**
 * JBTable cell renderer that paints a rounded color swatch next to its hex code.
 *
 * Accepts a `String` hex value ("#RRGGBB"); invalid or empty values render as blank.
 * The swatch sits on the left, the hex text to the right. Intended for the `Color`
 * column of the project / language mappings tables.
 */
class RoundedSwatchRenderer : DefaultTableCellRenderer() {
    private var hexValue: String? = null

    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int,
    ): Component {
        super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        hexValue = value as? String
        text = formatLabel(hexValue)
        border =
            JBUI.Borders.empty(
                BORDER_VERTICAL,
                LEFT_INSET + SWATCH_SIZE + TEXT_GAP,
                BORDER_VERTICAL,
                BORDER_TEXT_RIGHT,
            )
        return this
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val hex = hexValue ?: return
        val color = safeDecodeColor(hex) ?: return
        val graphics = g.create() as Graphics2D
        try {
            graphics.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON,
            )
            val y = (height - SWATCH_SIZE) / 2f
            val shape =
                RoundRectangle2D.Float(
                    LEFT_INSET.toFloat(),
                    y,
                    SWATCH_SIZE.toFloat(),
                    SWATCH_SIZE.toFloat(),
                    SWATCH_ARC,
                    SWATCH_ARC,
                )
            graphics.color = color
            graphics.fill(shape)
            graphics.color = UIManager.getColor("Separator.separatorColor") ?: Color(BORDER_RGB)
            graphics.draw(shape)
        } finally {
            graphics.dispose()
        }
    }

    companion object {
        private const val LEFT_INSET = 6
        private const val SWATCH_SIZE = 14
        private const val SWATCH_ARC = 4f
        private const val TEXT_GAP = 8
        private const val BORDER_RGB = 0x4E5A6E
        private const val BORDER_VERTICAL = 2
        private const val BORDER_TEXT_RIGHT = 4

        /**
         * Label shown next to the swatch: inherits the curated preset name when [hex]
         * matches one of [AYU_ACCENT_PRESETS] (e.g. "Amber (#F29E74)"), falls back to
         * plain hex for custom colors. Public so other renderers / dialogs can reuse
         * the same formatting and stay consistent.
         */
        fun formatLabel(hex: String?): String {
            if (hex.isNullOrBlank()) return ""
            val preset = AYU_ACCENT_PRESETS.firstOrNull { it.hex.equals(hex, ignoreCase = true) }
            val upper = hex.uppercase()
            return if (preset != null) "${preset.name} ($upper)" else upper
        }

        fun safeDecodeColor(hex: String): Color? =
            try {
                Color.decode(hex)
            } catch (_: NumberFormatException) {
                null
            }
    }
}
