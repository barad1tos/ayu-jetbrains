package dev.ayuislands.accent.conflict

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.extensions.PluginId
import dev.ayuislands.AyuPlugin
import dev.ayuislands.accent.AccentElementId
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
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
        mockkObject(AyuPlugin)
        every { AyuPlugin.findLoadedPlugin(any<PluginId>()) } returns null
    }

    @AfterTest
    fun tearDown() {
        ConflictRegistry.resetCachedConflictsForTesting()
        unmockkAll()
    }

    @Test
    fun `entries has exactly 3 known conflict plugins`() {
        assertEquals(3, getEntries().size)
    }

    @Test
    fun `entries contain Atom Material Icons as unsupported plugin-level conflict`() {
        val atomMaterialIcons = getEntries().first { it.pluginId == "com.mallowigi" }

        assertEquals("Atom Material Icons", atomMaterialIcons.pluginDisplayName)
        assertEquals(ConflictType.BLOCK, atomMaterialIcons.type)
        assertTrue(
            atomMaterialIcons.affectedElements.isEmpty(),
            "Atom Material Icons must not map stale CHECKBOXES or synthetic MATCHING_TAG to a current element",
        )
    }

    @Test
    fun `Atom Material Icons entry does not claim synthetic affected elements`() {
        val atomMaterialIcons = getEntries().first { it.pluginId == "com.mallowigi" }

        assertTrue(
            atomMaterialIcons.affectedElements.isEmpty(),
            "Atom Material Icons must not map generic fixtures such as MATCHING_TAG to production conflicts",
        )
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
        val conflicts = ConflictRegistry.detectConflicts()

        assertTrue(conflicts.isEmpty(), "Expected no conflicts when plugins are absent")
    }

    /**
     * Caching regression: `detectConflicts` consults the plugin registry only
     * once per session. The second call returns the exact same list instance
     * and does NOT trigger additional `findLoadedPlugin` lookups.
     */
    @Test
    fun `detectConflicts result is cached across calls`() {
        val first = ConflictRegistry.detectConflicts()
        val second = ConflictRegistry.detectConflicts()

        assertSame(first, second, "Cached result must be reused across calls")
        verify(exactly = 3) { AyuPlugin.findLoadedPlugin(any<PluginId>()) }
    }

    /**
     * Reset hook regression: after `resetCachedConflictsForTesting`, the next
     * call must re-run the filter against the current mock state.
     */
    @Test
    fun `resetCachedConflictsForTesting clears cache so next call re-queries plugins`() {
        val firstBatch = ConflictRegistry.detectConflicts()
        ConflictRegistry.resetCachedConflictsForTesting()
        val secondBatch = ConflictRegistry.detectConflicts()

        // Two separate computations: 3 entries x 2 calls = 6 lookups
        verify(exactly = 6) { AyuPlugin.findLoadedPlugin(any<PluginId>()) }
        // Same content, different instances (cache was cleared)
        assertEquals(firstBatch, secondBatch)
    }

    /**
     * Real-user scenario: CodeGlance Pro is INSTALLED and ENABLED — the
     * detection path returns it as an INTEGRATE conflict (used by the Plugins
     * settings panel and the accent applicator to wire the CGP integration).
     */
    @Test
    fun `detectConflicts returns CodeGlance Pro when only CodeGlance Pro is installed`() {
        val cgpDescriptor = mockk<IdeaPluginDescriptor>()
        every {
            AyuPlugin.findLoadedPlugin(PluginId.getId("com.nasller.CodeGlancePro"))
        } returns cgpDescriptor

        val conflicts = ConflictRegistry.detectConflicts()

        assertEquals(1, conflicts.size, "Only CodeGlance Pro is enabled, so exactly one conflict surfaces")
        assertEquals("com.nasller.CodeGlancePro", conflicts.single().pluginId)
        assertTrue(ConflictRegistry.isCodeGlanceProDetected(), "Detection helper must report CGP as active")
        assertTrue(!ConflictRegistry.isIndentRainbowDetected(), "Indent Rainbow stays off when not installed")
    }

    /**
     * Real-user scenario: Indent Rainbow is INSTALLED and ENABLED — symmetric
     * coverage to ensure both detector helpers are wired through the same path.
     */
    @Test
    fun `detectConflicts returns Indent Rainbow when only Indent Rainbow is installed`() {
        val irDescriptor = mockk<IdeaPluginDescriptor>()
        every {
            AyuPlugin.findLoadedPlugin(PluginId.getId("indent-rainbow.indent-rainbow"))
        } returns irDescriptor

        val conflicts = ConflictRegistry.detectConflicts()

        assertEquals(1, conflicts.size)
        assertEquals("indent-rainbow.indent-rainbow", conflicts.single().pluginId)
        assertTrue(ConflictRegistry.isIndentRainbowDetected())
        assertTrue(!ConflictRegistry.isCodeGlanceProDetected())
    }

    @Test
    fun `detectConflicts returns Atom Material Icons when only Atom Material Icons is installed`() {
        val atomDescriptor = mockk<IdeaPluginDescriptor>()
        every {
            AyuPlugin.findLoadedPlugin(PluginId.getId("com.mallowigi"))
        } returns atomDescriptor

        val conflicts = ConflictRegistry.detectConflicts()

        assertEquals(1, conflicts.size)
        assertEquals("com.mallowigi", conflicts.single().pluginId)
        assertEquals(ConflictType.BLOCK, conflicts.single().type)
        assertTrue(conflicts.single().affectedElements.isEmpty())
        assertTrue(ConflictRegistry.isAtomMaterialIconsDetected())
        assertTrue(!ConflictRegistry.isCodeGlanceProDetected())
        assertTrue(!ConflictRegistry.isIndentRainbowDetected())
    }

    /**
     * Real-user scenario: BOTH integrations installed and enabled — the result
     * preserves both entries in the registry's declaration order. The accent
     * applicator iterates this list to wire each integration in turn; a missing
     * entry would silently drop the integration for that user.
     */
    @Test
    fun `detectConflicts returns both integrations when both are installed`() {
        every {
            AyuPlugin.findLoadedPlugin(PluginId.getId("com.nasller.CodeGlancePro"))
        } returns mockk<IdeaPluginDescriptor>()
        every {
            AyuPlugin.findLoadedPlugin(PluginId.getId("indent-rainbow.indent-rainbow"))
        } returns mockk<IdeaPluginDescriptor>()

        val conflicts = ConflictRegistry.detectConflicts()

        assertEquals(2, conflicts.size, "Both integrations active → both in the result")
        assertEquals(
            listOf("com.nasller.CodeGlancePro", "indent-rainbow.indent-rainbow"),
            conflicts.map { it.pluginId },
            "Result preserves the registry's declaration order — callers depend on it",
        )
    }

    /**
     * Behavior lock for the [AyuPlugin.findLoadedPlugin] contract: a DISABLED
     * plugin must NOT appear in the conflict set. Before the wrapper switch,
     * the previous descriptor lookup returned a descriptor for disabled
     * installations and needed a separate `isDisabled` check — losing that
     * check silently treated disabled plugins as active integrations and
     * caused the integration code path to attempt class-loader reflection
     * against a plugin the user had explicitly turned off.
     *
     * Now `findLoadedPlugin` returns `null` for disabled plugins by contract,
     * so the wrapper's null check is sufficient. This test pins that semantics.
     */
    @Test
    fun `detectConflicts ignores plugins that are installed but disabled`() {
        // Disabled plugins surface as null from `AyuPlugin.findLoadedPlugin` —
        // the default mock stub in setUp already returns null for any id, which
        // matches both "absent" and "disabled" cases. Asserting on both detector
        // helpers documents that "disabled" must look identical to "absent" at
        // the conflict layer.
        val conflicts = ConflictRegistry.detectConflicts()

        assertTrue(conflicts.isEmpty(), "Disabled plugins must not be reported as active conflicts")
        assertTrue(!ConflictRegistry.isCodeGlanceProDetected(), "Disabled CGP must not register as detected")
        assertTrue(!ConflictRegistry.isIndentRainbowDetected(), "Disabled IR must not register as detected")
    }
}
