package dev.ayuislands.accent

import java.awt.Color

enum class AccentGroup { VISUAL, INTERACTIVE }

enum class AccentElementId(
    val group: AccentGroup,
    val displayName: String,
) {
    INLAY_HINTS(AccentGroup.VISUAL, "Inlay hints"),
    CARET_ROW(AccentGroup.VISUAL, "Caret row"),
    PROGRESS_BAR(AccentGroup.VISUAL, "Progress bar"),
    SCROLLBAR(AccentGroup.VISUAL, "Scrollbar"),
    LINKS(AccentGroup.INTERACTIVE, "Links"),
    BRACKET_MATCH(AccentGroup.INTERACTIVE, "Bracket match"),
    SEARCH_RESULTS(AccentGroup.INTERACTIVE, "Search results"),
    MATCHING_TAG(AccentGroup.INTERACTIVE, "Matching tag"),
}

interface AccentElement {
    val id: AccentElementId
    val displayName: String

    fun apply(color: Color)

    fun revert()

    fun applyNeutral(variant: AyuVariant) {
        revert()
    }
}
