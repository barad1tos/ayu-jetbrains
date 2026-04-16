package dev.ayuislands.whatsnew

import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Wraps [child] in an X-AXIS BoxLayout flanked by horizontal glue so the inner
 * component renders centered within whatever width the parent column provides.
 *
 * The wrapper's `alignmentX` controls how the wrapper itself sits inside its
 * own Y-axis BoxLayout parent — pass `LEFT_ALIGNMENT` when the surrounding
 * column uses left-aligned content (e.g. text labels above), `CENTER_ALIGNMENT`
 * when the wrapper itself should center within a wider container.
 *
 * Shared by [WhatsNewPanel] (centers slide cards in the scroll viewport) and
 * [WhatsNewSlideCard] (centers the image inside a left-aligned text card).
 */
internal fun centerInRow(
    child: JComponent,
    wrapperAlignment: Float,
): JPanel {
    val row = JPanel()
    row.layout = BoxLayout(row, BoxLayout.X_AXIS)
    row.isOpaque = false
    row.alignmentX = wrapperAlignment
    row.add(Box.createHorizontalGlue())
    row.add(child)
    row.add(Box.createHorizontalGlue())
    return row
}
