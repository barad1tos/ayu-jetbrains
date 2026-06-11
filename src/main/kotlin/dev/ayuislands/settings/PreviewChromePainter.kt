package dev.ayuislands.settings

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Rectangle

internal data class PreviewChromeLayout(
    val padding: Int,
    val contentHeight: Int,
    val projectWidth: Int,
    val editorX: Int,
    val editorWidth: Int,
)

internal data class PreviewChromeProjectRow(
    val markerColor: Color,
    val text: String,
)

internal data class PreviewChromeProjectPanel(
    val bounds: Rectangle,
    val surface: Color,
    val rows: List<PreviewChromeProjectRow>,
    val markerShape: PreviewChromeMarkerShape,
    val textColor: Color? = null,
)

internal enum class PreviewChromeMarkerShape {
    ROUND,
    SQUARE,
}

internal object PreviewChromePainter {
    fun layout(
        width: Int,
        height: Int,
    ): PreviewChromeLayout {
        val padding = JBUI.scale(PADDING)
        val projectWidth = JBUI.scale(PROJECT_WIDTH)
        val columnGap = JBUI.scale(COLUMN_GAP)
        val editorX = padding + projectWidth + columnGap
        return PreviewChromeLayout(
            padding = padding,
            contentHeight = (height - padding * 2).coerceAtLeast(1),
            projectWidth = projectWidth,
            editorX = editorX,
            editorWidth = (width - editorX - padding).coerceAtLeast(1),
        )
    }

    fun paintOuterPanel(
        g2: Graphics2D,
        width: Int,
        height: Int,
        surface: Color,
    ) {
        paintRoundedSurface(g2, Rectangle(0, 0, width, height), JBUI.scale(ARC), surface)
    }

    fun paintPanelFrame(
        g2: Graphics2D,
        bounds: Rectangle,
        surface: Color,
    ) {
        paintRoundedSurface(g2, bounds, JBUI.scale(INNER_ARC), surface)
    }

    fun paintProjectPanel(
        g2: Graphics2D,
        panel: PreviewChromeProjectPanel,
    ) {
        paintPanelFrame(g2, panel.bounds, panel.surface)

        g2.font = JBUI.Fonts.smallFont()
        val rowHeight = JBUI.scale(PROJECT_ROW_HEIGHT)
        var rowY = panel.bounds.y + JBUI.scale(PROJECT_TOP_PADDING)
        for (row in panel.rows) {
            val baseline = rowY + (rowHeight - g2.fontMetrics.height) / 2 + g2.fontMetrics.ascent
            g2.color = row.markerColor
            paintMarker(g2, panel.bounds.x + JBUI.scale(FILE_DOT_X), rowY + JBUI.scale(FILE_DOT_Y), panel.markerShape)
            g2.color = panel.textColor ?: row.markerColor
            g2.drawString(row.text, panel.bounds.x + JBUI.scale(FILE_TEXT_X), baseline)
            rowY += rowHeight
        }
    }

    private fun paintRoundedSurface(
        g2: Graphics2D,
        bounds: Rectangle,
        arc: Int,
        surface: Color,
    ) {
        g2.color = surface
        g2.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, arc, arc)
        g2.color = JBColor.border()
        g2.drawRoundRect(bounds.x, bounds.y, bounds.width - 1, bounds.height - 1, arc, arc)
    }

    private fun paintMarker(
        g2: Graphics2D,
        x: Int,
        y: Int,
        shape: PreviewChromeMarkerShape,
    ) {
        val size = JBUI.scale(FILE_DOT)
        when (shape) {
            PreviewChromeMarkerShape.ROUND -> g2.fillOval(x, y, size, size)
            PreviewChromeMarkerShape.SQUARE -> g2.fillRect(x, y, size, size)
        }
    }

    private const val PADDING = 10
    private const val COLUMN_GAP = 10
    private const val PROJECT_WIDTH = 154
    private const val PROJECT_TOP_PADDING = 12
    private const val PROJECT_ROW_HEIGHT = 22
    private const val FILE_DOT_X = 11
    private const val FILE_DOT_Y = 8
    private const val FILE_DOT = 7
    private const val FILE_TEXT_X = 26
    private const val ARC = 8
    private const val INNER_ARC = 6
}
