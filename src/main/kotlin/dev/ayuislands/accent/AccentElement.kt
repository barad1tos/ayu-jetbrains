package dev.ayuislands.accent

import java.awt.Color

enum class AccentGroup { VISUAL, INTERACTIVE, CHROME }

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

    // Chrome tinting targets (phase 40). CHROME-group elements are NOT rendered in the
    // Elements panel's per-element accent toggles — they are surfaced via a separate
    // Chrome tinting UI and driven by the dedicated `chrome*` properties on
    // AyuIslandsState. The EP-backed AccentElement implementations for these ids live
    // in the chrome-tinting subsystem; AccentElementsPanel filters by group to keep
    // VISUAL/INTERACTIVE presentation unchanged.
    STATUS_BAR(AccentGroup.CHROME, "Status bar"),
    MAIN_TOOLBAR(AccentGroup.CHROME, "Main toolbar"),
    TOOL_WINDOW_STRIPE(AccentGroup.CHROME, "Tool window stripe"),
    NAV_BAR(AccentGroup.CHROME, "Navigation bar"),
    PANEL_BORDER(AccentGroup.CHROME, "Panel border"),
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
