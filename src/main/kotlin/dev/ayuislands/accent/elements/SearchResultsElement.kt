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
            UIManager.put(selection.key, Color(color.red, color.green, color.blue, selection.alpha))
        }
    }

    override fun revert() {
        for (selection in selectionKeys) {
            UIManager.put(selection.key, null)
        }
    }
}
