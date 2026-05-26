package dev.ayuislands.settings

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/**
 * Text-glyph [Icon] for the per-category Bold / Italic style toggles in the
 * Ayu Islands syntax Custom drill-down. [com.intellij.icons.AllIcons] ships no
 * stable Bold / Italic vector, so the cue is a single drawn glyph: `"B"` in
 * [Font.BOLD] or `"I"` in [Font.ITALIC], centered in a square cell and rendered
 * via [Graphics.drawString].
 *
 * The icon is render-state-immutable — the engaged / at-rest decision is taken
 * by the caller, which constructs one instance per state and hands them to the
 * [com.intellij.ui.InplaceButton] as it cycles. This class paints the glyph in
 * the supplied [foreground] at the supplied [glyphStyle], optionally on a filled
 * rounded rect when [background] is non-null (the engaged-state pressed-fill).
 * The cue is glyph weight / slant plus the pressed-fill, never color alone
 * (accessibility: a color-blind user still reads the bold vs. italic shape).
 *
 * @param glyph the single character to paint (`"B"` or `"I"`).
 * @param glyphStyle the [Font] style constant ([Font.BOLD] / [Font.ITALIC]).
 * @param foreground the glyph color — dimmed [UIUtil.getContextHelpForeground]
 *   at rest, full [UIUtil.getLabelForeground] when engaged.
 * @param background the filled-rounded-rect color behind the glyph for the
 *   engaged state (`JBUI.CurrentTheme.ActionButton.pressedBackground()`), or
 *   `null` for the transparent at-rest state.
 * @param cell the square icon side in DPI-scaled pixels.
 * @param glyphSize the glyph point size in DPI-scaled pixels.
 */
class StyleGlyphIcon(
    private val glyph: String,
    private val glyphStyle: Int,
    private val foreground: Color,
    private val background: Color? = null,
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
            background?.let { fill ->
                canvas.color = fill
                val arc = JBUI.scale(ARC)
                canvas.fillRoundRect(x, y, cell, cell, arc, arc)
            }
            canvas.color = foreground
            val baseFont = component?.font ?: UIUtil.getLabelFont()
            canvas.font = baseFont.deriveFont(glyphStyle, glyphSize.toFloat())
            val metrics = canvas.fontMetrics
            val glyphWidth = metrics.stringWidth(glyph)
            val drawX = x + (cell - glyphWidth) / 2
            // Baseline-center the glyph: ascent/descent split around the cell mid.
            val drawY = y + (cell - metrics.height) / 2 + metrics.ascent
            canvas.drawString(glyph, drawX, drawY)
        } finally {
            canvas.dispose()
        }
    }

    companion object {
        // Square toggle cell side (DPI-scaled). Matches the reset icon's cell so
        // the three trailing controls align on one baseline.
        const val ICON_CELL = 20

        // Glyph point size (DPI-scaled) — smaller than the cell so the drawn "B"
        // / "I" leaves a breathing margin inside the InplaceButton hover circle.
        const val ICON_GLYPH = 16

        // Corner radius (DPI-scaled) of the engaged-state pressed-fill rect.
        private const val ARC = 6
    }
}
