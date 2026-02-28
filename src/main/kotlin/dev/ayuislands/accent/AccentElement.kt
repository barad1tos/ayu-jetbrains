package dev.ayuislands.accent

import java.awt.Color

enum class AccentGroup { VISUAL, INTERACTIVE }

enum class AccentElementId(val group: AccentGroup) {
    TAB_UNDERLINES(AccentGroup.VISUAL),
    CARET_ROW(AccentGroup.VISUAL),
    PROGRESS_BAR(AccentGroup.VISUAL),
    SCROLLBAR(AccentGroup.VISUAL),
    LINKS(AccentGroup.INTERACTIVE),
    BRACKET_MATCH(AccentGroup.INTERACTIVE),
    SEARCH_RESULTS(AccentGroup.INTERACTIVE),
    CHECKBOXES(AccentGroup.INTERACTIVE),
}

interface AccentElement {
    val id: AccentElementId
    val displayName: String
    fun apply(color: Color)
    fun revert()
}
