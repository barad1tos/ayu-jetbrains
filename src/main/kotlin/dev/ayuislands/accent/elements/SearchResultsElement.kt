package dev.ayuislands.accent.elements

import dev.ayuislands.accent.AccentElement
import dev.ayuislands.accent.AccentElementId
import java.awt.Color
import javax.swing.UIManager

class SearchResultsElement : AccentElement {

    override val id = AccentElementId.SEARCH_RESULTS
    override val displayName = "Search Results"

    private data class SelectionKey(val key: String, val alpha: Int)

    private val selectionKeys = listOf(
        SelectionKey("List.selectionBackground", 0x26),
        SelectionKey("List.selectionInactiveBackground", 0x1A),
        SelectionKey("Tree.selectionBackground", 0x26),
        SelectionKey("Tree.selectionInactiveBackground", 0x1A),
        SelectionKey("Table.selectionBackground", 0x26),
        SelectionKey("Table.selectionInactiveBackground", 0x1A),
    )

    override fun apply(color: Color) {
        for (selection in selectionKeys) {
            UIManager.put(selection.key, Color(color.red, color.green, color.blue, selection.alpha))
        }
    }

    override fun revert() {
        for (selection in selectionKeys) {
            UIManager.put(selection.key, null)
        }
    }
}
