package dev.ayuislands.accent

import java.awt.Color

enum class AccentGroup { VISUAL, INTERACTIVE, CHROME }

enum class AccentElementId(
    val group: AccentGroup,
    val displayName: String,
    val settingName: String,
) {
    INLAY_HINTS(AccentGroup.VISUAL, "Inlay hints", "inlayHints"),
    CARET_ROW(AccentGroup.VISUAL, "Caret row", "caretRow"),
    PROGRESS_BAR(AccentGroup.VISUAL, "Progress bar", "progressBar"),
    SCROLLBAR(AccentGroup.VISUAL, "Scrollbar", "scrollbar"),
    LINKS(AccentGroup.INTERACTIVE, "Links", "links"),
    BRACKET_MATCH(AccentGroup.INTERACTIVE, "Bracket match", "bracketMatch"),
    SEARCH_RESULTS(AccentGroup.INTERACTIVE, "Search results", "searchResults"),
    MATCHING_TAG(AccentGroup.INTERACTIVE, "Matching tag", "matchingTag"),

    // Chrome tinting targets (phase 40). CHROME-group elements are NOT rendered in the
    // Elements panel's per-element accent toggles — they are surfaced via a separate
    // Chrome tinting UI and driven by the dedicated `chrome*` properties on
    // AyuIslandsState. The EP-backed AccentElement implementations for these ids live
    // in the chrome-tinting subsystem; AccentElementsPanel filters by group to keep
    // VISUAL/INTERACTIVE presentation unchanged.
    STATUS_BAR(AccentGroup.CHROME, "Status bar", "chromeStatusBar"),
    MAIN_TOOLBAR(AccentGroup.CHROME, "Main toolbar", "chromeMainToolbar"),
    TOOL_WINDOW_STRIPE(AccentGroup.CHROME, "Tool window stripe", "chromeToolWindowStripe"),
    NAV_BAR(AccentGroup.CHROME, "Navigation bar", "chromeNavBar"),
    PANEL_BORDER(AccentGroup.CHROME, "Panel border", "chromePanelBorder"),
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
