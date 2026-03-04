package dev.ayuislands.accent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccentElementIdTest {
    @Test
    fun `exhaustive element count`() {
        assertEquals(8, AccentElementId.entries.size)
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
        assertTrue(AccentElementId.CHECKBOXES in interactive)
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
        assertEquals("Checkboxes", AccentElementId.CHECKBOXES.displayName)
    }

    @Test
    fun `display names are unique`() {
        val names = AccentElementId.entries.map { it.displayName }
        assertEquals(names.size, names.toSet().size, "Display names should be unique")
    }

    @Test
    fun `AccentGroup has exactly 2 entries`() {
        assertEquals(2, AccentGroup.entries.size)
    }

    @Test
    fun `every element belongs to a group`() {
        val allGroups = AccentGroup.entries.toSet()
        for (id in AccentElementId.entries) {
            assertTrue(id.group in allGroups, "${id.name} should belong to a valid group")
        }
    }
}
