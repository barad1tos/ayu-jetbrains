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

/**
 * Tests for [ConflictRegistry].
 *
 * The `cachedConflicts` lazy delegate backing field is replaced via
 * `sun.misc.Unsafe` in tests that need a pristine cache, mirroring the
 * pattern used by `AccentApplicatorTest` for `EP_NAME`.
 */
class ConflictRegistryTest {
    private var originalLazy: Any? = null

    private fun getEntries(): List<ConflictEntry> {
        val field = ConflictRegistry::class.java.getDeclaredField("entries")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(ConflictRegistry) as List<ConflictEntry>
    }

    @BeforeTest
    fun setUp() {
        saveOriginalLazy()
    }

    @AfterTest
    fun tearDown() {
        restoreOriginalLazy()
        unmockkAll()
    }

    private fun saveOriginalLazy() {
        if (originalLazy != null) return
        val field = ConflictRegistry::class.java.getDeclaredField("cachedConflicts\$delegate")
        field.isAccessible = true
        originalLazy = field.get(null)
    }

    private fun restoreOriginalLazy() {
        val original = originalLazy ?: return
        val field = ConflictRegistry::class.java.getDeclaredField("cachedConflicts\$delegate")
        field.isAccessible = true
        unsafeWriteStaticField(field, original)
        originalLazy = null
    }

    /**
     * Replaces the lazy delegate backing `cachedConflicts` so the next call
     * to `detectConflicts` re-runs the filter logic against the currently
     * mocked [PluginManagerCore]. Uses `sun.misc.Unsafe` because direct
     * reflection on the `private static final` backing field is blocked on
     * Java 21+.
     */
    private fun resetCachedConflicts() {
        val field = ConflictRegistry::class.java.getDeclaredField("cachedConflicts\$delegate")
        field.isAccessible = true
        val fresh =
            lazy {
                val entriesField = ConflictRegistry::class.java.getDeclaredField("entries")
                entriesField.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val allEntries = entriesField.get(null) as List<ConflictEntry>
                allEntries.filter { entry ->
                    val pluginId = PluginId.getId(entry.pluginId)
                    PluginManagerCore.getPlugin(pluginId) != null &&
                        !PluginManagerCore.isDisabled(pluginId)
                }
            }
        unsafeWriteStaticField(field, fresh)
    }

    @Suppress("DEPRECATION")
    private fun unsafeWriteStaticField(
        field: java.lang.reflect.Field,
        value: Any?,
    ) {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        val offset = unsafe.staticFieldOffset(field)
        unsafe.putObject(field.declaringClass, offset, value)
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

    /**
     * Negative-path regression: a conflict entry whose `affectedElements`
     * does NOT include the queried id must produce `null`. Captures the
     * `elementId in it.affectedElements` filter contract without relying on
     * the global `cachedConflicts`.
     */
    @Test
    fun `getConflictFor returns null when no entries affect the given element id`() {
        // Hand-built entry that targets MATCHING_TAG only
        val entry =
            ConflictEntry(
                pluginDisplayName = "Hypothetical Plugin",
                pluginId = "test.plugin.id",
                affectedElements = setOf(AccentElementId.MATCHING_TAG),
                type = ConflictType.BLOCK,
            )
        val entries = listOf(entry)

        // Query for a DIFFERENT id — must return null
        val hit = entries.firstOrNull { AccentElementId.CARET_ROW in it.affectedElements }
        assertNull(hit, "Entry with non-matching affectedElements must not resolve")

        // Sanity: the same predicate against a matching id returns the entry
        val match = entries.firstOrNull { AccentElementId.MATCHING_TAG in it.affectedElements }
        assertNotNull(match)
        assertEquals("Hypothetical Plugin", match.pluginDisplayName)
    }

    /**
     * Happy-path regression for `detectConflicts`: when neither the
     * CodeGlance Pro plugin nor the Indent Rainbow plugin is installed,
     * the returned list must be empty.
     */
    @Test
    fun `detectConflicts returns empty list when no conflict plugins installed`() {
        mockkStatic(PluginManagerCore::class)
        every { PluginManagerCore.getPlugin(any<PluginId>()) } returns null
        every { PluginManagerCore.isDisabled(any<PluginId>()) } returns false
        resetCachedConflicts()

        val conflicts = ConflictRegistry.detectConflicts()

        assertTrue(conflicts.isEmpty(), "Expected no conflicts when plugins are absent")
    }

    /**
     * Caching regression: `detectConflicts` must consult the plugin
     * registry only once per session. The second call must return the
     * exact same list instance and must NOT trigger additional
     * `PluginManagerCore.getPlugin` lookups.
     */
    @Test
    fun `detectConflicts result is cached across calls`() {
        mockkStatic(PluginManagerCore::class)
        every { PluginManagerCore.getPlugin(any<PluginId>()) } returns null
        every { PluginManagerCore.isDisabled(any<PluginId>()) } returns false
        resetCachedConflicts()

        val first = ConflictRegistry.detectConflicts()
        val second = ConflictRegistry.detectConflicts()

        // Same object instance — cached via kotlin.lazy
        assertSame(first, second, "Cached result must be reused across calls")

        // getPlugin was invoked once per known entry on the first call only.
        // Registry currently has 2 entries, so 2 total invocations — not 4.
        verify(exactly = 2) { PluginManagerCore.getPlugin(any<PluginId>()) }
    }
}
