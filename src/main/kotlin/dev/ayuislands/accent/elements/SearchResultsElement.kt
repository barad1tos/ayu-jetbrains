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
        val backgroundKey: String,
    )

    private val selectionKeys =
        listOf(
            SelectionKey("List.selectionBackground", ACTIVE_ALPHA, "List.background"),
            SelectionKey("List.selectionInactiveBackground", INACTIVE_ALPHA, "List.background"),
            SelectionKey("Tree.selectionBackground", ACTIVE_ALPHA, "Tree.background"),
            SelectionKey("Tree.selectionInactiveBackground", INACTIVE_ALPHA, "Tree.background"),
            SelectionKey("Table.selectionBackground", ACTIVE_ALPHA, "Table.background"),
            SelectionKey("Table.selectionInactiveBackground", INACTIVE_ALPHA, "Table.background"),
        )

    companion object {
        private const val ACTIVE_ALPHA = 0x26
        private const val INACTIVE_ALPHA = 0x1A
    }

    override fun apply(color: Color) {
        for (selection in selectionKeys) {
            UIManager.put(selection.key, blendWithBackground(color, selection.alpha, selection.backgroundKey))
        }
    }

    private fun blendWithBackground(accent: Color, alpha: Int, backgroundKey: String): Color {
        val bg = UIManager.getColor(backgroundKey)
            ?: UIManager.getColor("Panel.background")
            ?: Color.BLACK
        val ratio = alpha / 255f
        return Color(
            (bg.red + (accent.red - bg.red) * ratio + 0.5f).toInt(),
            (bg.green + (accent.green - bg.green) * ratio + 0.5f).toInt(),
            (bg.blue + (accent.blue - bg.blue) * ratio + 0.5f).toInt(),
        )
    }

    override fun revert() {
        for (selection in selectionKeys) {
            UIManager.put(selection.key, null)
        }
    }
}
