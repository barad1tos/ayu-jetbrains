package dev.ayuislands.accent.elements

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
    }

    override fun apply(color: Color) {
        for (selection in selectionKeys) {
            val backgroundKey = selection.key.substringBefore(".selection") + ".background"
            UIManager.put(selection.key, blendWithBackground(color, selection.alpha, backgroundKey))
        }
    }

    private fun blendWithBackground(accent: Color, alpha: Int, backgroundKey: String): Color {
        val bg = UIManager.getColor(backgroundKey)
            ?: UIManager.getColor("Panel.background")
            ?: accent
        val ratio = alpha / 255f
        return Color(
            (bg.red + (accent.red - bg.red) * ratio + 0.5f).toInt().coerceIn(0, 255),
            (bg.green + (accent.green - bg.green) * ratio + 0.5f).toInt().coerceIn(0, 255),
            (bg.blue + (accent.blue - bg.blue) * ratio + 0.5f).toInt().coerceIn(0, 255),
        )
    }

    override fun revert() {
        for (selection in selectionKeys) {
            UIManager.put(selection.key, null)
        }
    }
}
