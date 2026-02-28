package dev.ayuislands.settings

import com.intellij.openapi.components.BaseState
import dev.ayuislands.accent.AccentElementId

class AyuIslandsState : BaseState() {
    // Per-variant accent colors
    var mirageAccent by string("#FFCC66")
    var darkAccent by string("#E6B450")
    var lightAccent by string("#F29718")
    var trialExpiredNotified by property(false)

    // Per-element accent toggles (all ON by default)
    var tabUnderlines by property(true)
    var caretRow by property(true)
    var progressBar by property(true)
    var scrollbar by property(true)
    var links by property(true)
    var bracketMatch by property(true)
    var searchResults by property(true)
    var checkboxes by property(true)

    // Glow effect
    var glowEnabled by property(true)

    // CodeGlancePro integration (opt-in, default OFF)
    var cgpIntegrationEnabled by property(false)

    // Force overrides for conflicting elements (element ID names)
    var forceOverrides by stringSet()

    fun isToggleEnabled(id: AccentElementId): Boolean {
        return when (id) {
            AccentElementId.TAB_UNDERLINES -> tabUnderlines
            AccentElementId.CARET_ROW -> caretRow
            AccentElementId.PROGRESS_BAR -> progressBar
            AccentElementId.SCROLLBAR -> scrollbar
            AccentElementId.LINKS -> links
            AccentElementId.BRACKET_MATCH -> bracketMatch
            AccentElementId.SEARCH_RESULTS -> searchResults
            AccentElementId.CHECKBOXES -> checkboxes
        }
    }

    fun setToggle(id: AccentElementId, enabled: Boolean) {
        when (id) {
            AccentElementId.TAB_UNDERLINES -> tabUnderlines = enabled
            AccentElementId.CARET_ROW -> caretRow = enabled
            AccentElementId.PROGRESS_BAR -> progressBar = enabled
            AccentElementId.SCROLLBAR -> scrollbar = enabled
            AccentElementId.LINKS -> links = enabled
            AccentElementId.BRACKET_MATCH -> bracketMatch = enabled
            AccentElementId.SEARCH_RESULTS -> searchResults = enabled
            AccentElementId.CHECKBOXES -> checkboxes = enabled
        }
    }
}
