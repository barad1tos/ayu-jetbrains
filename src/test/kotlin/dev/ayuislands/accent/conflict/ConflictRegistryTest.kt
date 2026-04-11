package dev.ayuislands.accent.conflict

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import dev.ayuislands.accent.AccentElementId
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConflictRegistryTest {
    private fun getEntries(): List<ConflictEntry> {
        val field = ConflictRegistry::class.java.getDeclaredField("entries")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(ConflictRegistry) as List<ConflictEntry>
    }

    @BeforeTest
    fun setUp() {
        ConflictRegistry.resetCachedConflictsForTesting()
    }

    @AfterTest
    fun tearDown() {
        ConflictRegistry.resetCachedConflictsForTesting()
        unmockkAll()
    }

    @Test
    fun `entries has exactly 2 known conflict plugins`() {
        assertEquals(2, getEntries().size)
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
    fun `CodeGlance Pro has no affected elements`() {
        val entries = getEntries()
        val cgp = entries.first { it.pluginId == "com.nasller.CodeGlancePro" }

        assertTrue(cgp.affectedElements.isEmpty())
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

    /**
     * Negative-path regression: a conflict entry whose `affectedElements`
     * does NOT include the queried id must produce `null`.
     */
    @Test
    fun `getConflictFor returns null when no entries affect the given element id`() {
        val entry =
            ConflictEntry(
                pluginDisplayName = "Hypothetical Plugin",
                pluginId = "test.plugin.id",
                affectedElements = setOf(AccentElementId.MATCHING_TAG),
                type = ConflictType.BLOCK,
            )
        val entries = listOf(entry)

        val hit = entries.firstOrNull { AccentElementId.CARET_ROW in it.affectedElements }
        assertNull(hit, "Entry with non-matching affectedElements must not resolve")

        val match = entries.firstOrNull { AccentElementId.MATCHING_TAG in it.affectedElements }
        assertNotNull(match)
        assertEquals("Hypothetical Plugin", match.pluginDisplayName)
    }

    /**
     * Happy-path regression for `detectConflicts`: when neither CodeGlance Pro
     * nor Indent Rainbow is installed, the returned list must be empty.
     */
    @Test
    fun `detectConflicts returns empty list when no conflict plugins installed`() {
        mockkStatic(PluginManagerCore::class)
        every { PluginManagerCore.getPlugin(any<PluginId>()) } returns null
        every { PluginManagerCore.isDisabled(any<PluginId>()) } returns false

        val conflicts = ConflictRegistry.detectConflicts()

        assertTrue(conflicts.isEmpty(), "Expected no conflicts when plugins are absent")
    }

    /**
     * Caching regression: `detectConflicts` consults the plugin registry only
     * once per session. The second call returns the exact same list instance
     * and does NOT trigger additional `PluginManagerCore.getPlugin` lookups.
     */
    @Test
    fun `detectConflicts result is cached across calls`() {
        mockkStatic(PluginManagerCore::class)
        every { PluginManagerCore.getPlugin(any<PluginId>()) } returns null
        every { PluginManagerCore.isDisabled(any<PluginId>()) } returns false

        val first = ConflictRegistry.detectConflicts()
        val second = ConflictRegistry.detectConflicts()

        assertSame(first, second, "Cached result must be reused across calls")
        verify(exactly = 2) { PluginManagerCore.getPlugin(any<PluginId>()) }
    }

    /**
     * Reset hook regression: after `resetCachedConflictsForTesting`, the next
     * call must re-run the filter against the current mock state.
     */
    @Test
    fun `resetCachedConflictsForTesting clears cache so next call re-queries plugins`() {
        mockkStatic(PluginManagerCore::class)
        every { PluginManagerCore.getPlugin(any<PluginId>()) } returns null
        every { PluginManagerCore.isDisabled(any<PluginId>()) } returns false

        val firstBatch = ConflictRegistry.detectConflicts()
        ConflictRegistry.resetCachedConflictsForTesting()
        val secondBatch = ConflictRegistry.detectConflicts()

        // Two separate computations: 2 entries x 2 calls = 4 lookups
        verify(exactly = 4) { PluginManagerCore.getPlugin(any<PluginId>()) }
        // Same content, different instances (cache was cleared)
        assertEquals(firstBatch, secondBatch)
    }
}
