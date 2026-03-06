package dev.ayuislands.accent.elements

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ex.EditorGutterComponentEx
import com.intellij.openapi.editor.markup.LineMarkerRendererEx
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle

class BracketScopeRenderer(
    private val color: Color,
    private val startLine: Int,
    private val endLine: Int,
) : LineMarkerRendererEx {
    override fun getPosition(): LineMarkerRendererEx.Position = LineMarkerRendererEx.Position.CUSTOM

    override fun paint(
        editor: Editor,
        g: Graphics,
        r: Rectangle,
    ) {
        val gutter = editor.gutter as? EditorGutterComponentEx ?: return
        val g2 = g.create() as Graphics2D
        try {
            val lineMarkerAreaOffset = gutter.lineMarkerAreaOffset
            val lineHeight = editor.lineHeight

            val totalLines = endLine - startLine
            val halfDepth = totalLines / 2

            val visibleArea = editor.scrollingModel.visibleArea
            val visibleStartY = visibleArea.y
            val visibleEndY = visibleArea.y + visibleArea.height

            for (line in startLine..endLine) {
                val y = editor.logicalPositionToXY(LogicalPosition(line, 0)).y
                if (y + lineHeight < visibleStartY) continue
                if (y > visibleEndY) break

                val alpha = alphaForDepth(line, halfDepth)
                g2.color = Color(color.red, color.green, color.blue, alpha)
                g2.fillRect(0, y, lineMarkerAreaOffset, lineHeight)
            }
        } finally {
            g2.dispose()
        }
    }

    private fun alphaForDepth(
        line: Int,
        halfDepth: Int,
    ): Int {
        if (halfDepth == 0) return EDGE_ALPHA
        val depth = minOf(line - startLine, endLine - line)
        if (depth == 0) return EDGE_ALPHA

        val ratio = depth.toFloat() / halfDepth
        return (EDGE_ALPHA - ratio * (EDGE_ALPHA - CENTER_ALPHA))
            .toInt()
            .coerceIn(CENTER_ALPHA, EDGE_ALPHA)
    }

    companion object {
        private const val EDGE_ALPHA = 45
        private const val CENTER_ALPHA = 12
    }
}
