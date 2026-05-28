package dev.ayuislands.accent.elements

import com.intellij.ui.JBColor
import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import java.awt.Color
import javax.swing.UIManager

class SearchResultsElement : AccentElement {
    override val id = AccentElementId.SEARCH_RESULTS
    override val displayName = "Search Results"

    private data class SelectionKey(
        val key: String,
        val alpha: Int,
    )

    private val selectionKeys =
        listOf(
            SelectionKey("List.selectionBackground", ACTIVE_ALPHA),
            SelectionKey("List.selectionInactiveBackground", INACTIVE_ALPHA),
            SelectionKey("Tree.selectionBackground", ACTIVE_ALPHA),
            SelectionKey("Tree.selectionInactiveBackground", INACTIVE_ALPHA),
            SelectionKey("Table.selectionBackground", ACTIVE_ALPHA),
            SelectionKey("Table.selectionInactiveBackground", INACTIVE_ALPHA),
        )

    companion object {
        private const val ACTIVE_ALPHA = 0x26
        private const val INACTIVE_ALPHA = 0x1A
        private const val MAX_CHANNEL_VALUE = 255
        private const val ROUNDING_BIAS = 0.5f
        private const val RED_SHIFT = 16
        private const val GREEN_SHIFT = 8
    }

    override fun apply(color: Color) {
        for (selection in selectionKeys) {
            val backgroundKey = selection.key.substringBefore(".selection") + ".background"
            UIManager.put(selection.key, blendWithBackground(color, selection.alpha, backgroundKey))
        }
    }

    private fun blendWithBackground(
        accent: Color,
        alpha: Int,
        backgroundKey: String,
    ): Color {
        val bg =
            UIManager.getColor(backgroundKey)
                ?: UIManager.getColor("Panel.background")
                ?: accent
        val ratio = alpha.toFloat() / MAX_CHANNEL_VALUE
        val red = blendChannel(bg.red, accent.red, ratio)
        val green = blendChannel(bg.green, accent.green, ratio)
        val blue = blendChannel(bg.blue, accent.blue, ratio)
        val rgb = (red shl RED_SHIFT) or (green shl GREEN_SHIFT) or blue
        return JBColor(rgb, rgb)
    }

    private fun blendChannel(
        background: Int,
        accent: Int,
        ratio: Float,
    ): Int =
        (
            background +
                (accent - background) * ratio +
                ROUNDING_BIAS
        ).toInt().coerceIn(0, MAX_CHANNEL_VALUE)

    override fun revert() {
        for (selection in selectionKeys) {
            UIManager.put(selection.key, null)
        }
    }
}
