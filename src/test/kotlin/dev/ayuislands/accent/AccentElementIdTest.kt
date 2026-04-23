package dev.ayuislands.accent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccentElementIdTest {
    @Test
    fun `exhaustive element count`() {
        // 4 VISUAL + 4 INTERACTIVE + 5 CHROME (phase 40 chrome-tinting targets) = 13.
        assertEquals(13, AccentElementId.entries.size)
    }

    @Test
    fun `VISUAL group contains exactly 4 elements`() {
        val visual = AccentElementId.entries.filter { it.group == AccentGroup.VISUAL }
        assertEquals(4, visual.size)
        assertTrue(AccentElementId.INLAY_HINTS in visual)
        assertTrue(AccentElementId.CARET_ROW in visual)
        assertTrue(AccentElementId.PROGRESS_BAR in visual)
        assertTrue(AccentElementId.SCROLLBAR in visual)
    }

    @Test
    fun `INTERACTIVE group contains exactly 4 elements`() {
        val interactive = AccentElementId.entries.filter { it.group == AccentGroup.INTERACTIVE }
        assertEquals(4, interactive.size)
        assertTrue(AccentElementId.LINKS in interactive)
        assertTrue(AccentElementId.BRACKET_MATCH in interactive)
        assertTrue(AccentElementId.SEARCH_RESULTS in interactive)
        assertTrue(AccentElementId.MATCHING_TAG in interactive)
    }

    @Test
    fun `CHROME group contains exactly 5 elements`() {
        val chrome = AccentElementId.entries.filter { it.group == AccentGroup.CHROME }
        assertEquals(5, chrome.size)
        assertTrue(AccentElementId.STATUS_BAR in chrome)
        assertTrue(AccentElementId.MAIN_TOOLBAR in chrome)
        assertTrue(AccentElementId.TOOL_WINDOW_STRIPE in chrome)
        assertTrue(AccentElementId.NAV_BAR in chrome)
        assertTrue(AccentElementId.PANEL_BORDER in chrome)
    }

    @Test
    fun `all elements have non-empty display names`() {
        for (id in AccentElementId.entries) {
            assertTrue(id.displayName.isNotBlank(), "${id.name} should have a non-blank display name")
        }
    }

    @Test
    fun `display names are human-readable`() {
        assertEquals("Inlay hints", AccentElementId.INLAY_HINTS.displayName)
        assertEquals("Caret row", AccentElementId.CARET_ROW.displayName)
        assertEquals("Progress bar", AccentElementId.PROGRESS_BAR.displayName)
        assertEquals("Scrollbar", AccentElementId.SCROLLBAR.displayName)
        assertEquals("Links", AccentElementId.LINKS.displayName)
        assertEquals("Bracket match", AccentElementId.BRACKET_MATCH.displayName)
        assertEquals("Search results", AccentElementId.SEARCH_RESULTS.displayName)
        assertEquals("Matching tag", AccentElementId.MATCHING_TAG.displayName)
        assertEquals("Status bar", AccentElementId.STATUS_BAR.displayName)
        assertEquals("Main toolbar", AccentElementId.MAIN_TOOLBAR.displayName)
        assertEquals("Tool window stripe", AccentElementId.TOOL_WINDOW_STRIPE.displayName)
        assertEquals("Navigation bar", AccentElementId.NAV_BAR.displayName)
        assertEquals("Panel border", AccentElementId.PANEL_BORDER.displayName)
    }

    @Test
    fun `display names are unique`() {
        val names = AccentElementId.entries.map { it.displayName }
        assertEquals(names.size, names.toSet().size, "Display names should be unique")
    }

    @Test
    fun `AccentGroup has exactly 3 entries`() {
        // VISUAL + INTERACTIVE + CHROME (phase 40).
        assertEquals(3, AccentGroup.entries.size)
    }

    @Test
    fun `every element belongs to a group`() {
        val allGroups = AccentGroup.entries.toSet()
        for (id in AccentElementId.entries) {
            assertTrue(id.group in allGroups, "${id.name} should belong to a valid group")
        }
    }
}
