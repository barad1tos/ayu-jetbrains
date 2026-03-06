package dev.ayuislands.accent.conflict

import dev.ayuislands.accent.AccentElementId
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [ConflictRegistry].
 *
 * The `cachedConflicts` lazy delegate cannot be reset on Java 21+ due to module
 * access restrictions. These tests verify the static `entries` configuration
 * which is the source of truth for conflict detection logic.
 */
class ConflictRegistryTest {
    private fun getEntries(): List<ConflictEntry> {
        val field = ConflictRegistry::class.java.getDeclaredField("entries")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(ConflictRegistry) as List<ConflictEntry>
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `entries has exactly 3 known conflict plugins`() {
        assertEquals(3, getEntries().size)
    }

    @Test
    fun `entries contain Atom Material Icons with BLOCK type`() {
        val entries = getEntries()
        val atom = entries.first { it.pluginId == "com.mallowigi" }

        assertEquals("Atom Material Icons", atom.pluginDisplayName)
        assertEquals(ConflictType.BLOCK, atom.type)
        assertEquals(setOf(AccentElementId.CHECKBOXES), atom.affectedElements)
    }

    @Test
    fun `entries contain CodeGlance Pro with INTEGRATE type`() {
        val entries = getEntries()
        val cgp = entries.first { it.pluginId == "com.nasller.CodeGlancePro" }

        assertEquals("CodeGlance Pro", cgp.pluginDisplayName)
        assertEquals(ConflictType.INTEGRATE, cgp.type)
        assertTrue(cgp.affectedElements.isEmpty())
    }

    @Test
    fun `Atom Material Icons only blocks CHECKBOXES element`() {
        val entries = getEntries()
        val atom = entries.first { it.pluginId == "com.mallowigi" }

        assertEquals(1, atom.affectedElements.size)
        assertTrue(AccentElementId.CHECKBOXES in atom.affectedElements)
    }

    @Test
    fun `CodeGlance Pro has no affected elements`() {
        val entries = getEntries()
        val cgp = entries.first { it.pluginId == "com.nasller.CodeGlancePro" }

        assertTrue(cgp.affectedElements.isEmpty())
    }

    @Test
    fun `detectConflicts does not throw`() {
        assertNotNull(ConflictRegistry.detectConflicts())
    }

    @Test
    fun `getConflictFor returns null for non-conflicting element`() {
        val conflict = ConflictRegistry.getConflictFor(AccentElementId.LINKS)
        assertNull(conflict)
    }

    @Test
    fun `entries contain Indent Rainbow with INTEGRATE type`() {
        val entries = getEntries()
        val ir = entries.first { it.pluginId == "indent-rainbow.indent-rainbow" }

        assertEquals("Indent Rainbow", ir.pluginDisplayName)
        assertEquals(ConflictType.INTEGRATE, ir.type)
        assertTrue(ir.affectedElements.isEmpty())
    }

    @Test
    fun `isCodeGlanceProDetected does not throw`() {
        // In a test environment without real plugins, just verify the method executes
        ConflictRegistry.isCodeGlanceProDetected()
    }

    @Test
    fun `isIndentRainbowDetected does not throw`() {
        ConflictRegistry.isIndentRainbowDetected()
    }
}
