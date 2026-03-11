package dev.ayuislands.accent.conflict

import dev.ayuislands.accent.AccentElementId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConflictEntryTest {
    @Test
    fun `ConflictType has exactly 2 entries`() {
        assertEquals(2, ConflictType.entries.size)
    }

    @Test
    fun `ConflictType contains BLOCK and INTEGRATE`() {
        assertTrue(ConflictType.entries.any { it.name == "BLOCK" })
        assertTrue(ConflictType.entries.any { it.name == "INTEGRATE" })
    }

    @Test
    fun `ConflictEntry data class supports equality`() {
        val a = ConflictEntry("Test Plugin", "com.test", setOf(AccentElementId.LINKS), ConflictType.BLOCK)
        val b = ConflictEntry("Test Plugin", "com.test", setOf(AccentElementId.LINKS), ConflictType.BLOCK)
        assertEquals(a, b)
    }

    @Test
    fun `ConflictEntry preserves all fields`() {
        val elements = setOf(AccentElementId.MATCHING_TAG, AccentElementId.SCROLLBAR)
        val entry = ConflictEntry("My Plugin", "com.example.myplugin", elements, ConflictType.INTEGRATE)

        assertEquals("My Plugin", entry.pluginDisplayName)
        assertEquals("com.example.myplugin", entry.pluginId)
        assertEquals(elements, entry.affectedElements)
        assertEquals(ConflictType.INTEGRATE, entry.type)
    }

    @Test
    fun `ConflictEntry supports copy with modifications`() {
        val original = ConflictEntry("A", "com.a", setOf(AccentElementId.LINKS), ConflictType.BLOCK)
        val modified = original.copy(type = ConflictType.INTEGRATE)

        assertEquals(ConflictType.INTEGRATE, modified.type)
        assertEquals("A", modified.pluginDisplayName)
    }

    @Test
    fun `ConflictEntry supports empty affectedElements`() {
        val entry = ConflictEntry("Plugin", "com.p", emptySet(), ConflictType.INTEGRATE)
        assertTrue(entry.affectedElements.isEmpty())
    }
}
