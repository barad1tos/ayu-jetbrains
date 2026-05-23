package dev.ayuislands.syntax

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mood-whitelist behavior for [SyntaxModeApplicator]. Axis transforms are
 * exercised in [SyntaxModeApplicatorAxisTransformTest].
 *
 * Pure-functions object: no platform deps in core. Loader is mocked so
 * compute() works without booting LightPlatformTestCase.
 *
 * Post-H10 contract: non-whitelisted overlay keys produce a non-null clear
 * payload (baseline clone when available, otherwise empty `TextAttributes()`).
 * The baseline mock in this file is intentionally empty so every clear maps
 * to empty `TextAttributes` — keeps the assertions focused on the
 * whitelist/clear decision rather than baseline restoration (which has
 * dedicated coverage in `SyntaxModeApplicatorClearByBaselineTest`).
 */
class SyntaxModeApplicatorTest {
    private lateinit var loader: SyntaxOverlayLoader
    private lateinit var keyCache: MutableMap<String, TextAttributesKey>

    @BeforeTest
    fun setUp() {
        mockkStatic(TextAttributesKey::class)
        keyCache = mutableMapOf()
        every { TextAttributesKey.find(any<String>()) } answers {
            val name = firstArg<String>()
            keyCache.getOrPut(name) { mockk(relaxed = true) { every { externalName } returns name } }
        }

        loader = mockk(relaxed = true)
        val standardKeys = setOf(key("STD_DECL"))
        val richKeys = setOf(key("RICH_REF"))
        val maximumKeys = setOf(key("MAX_DOC"))
        val overlay =
            mapOf(
                key("STD_DECL") to attrs(0xFF, 0xCC, 0x66),
                key("RICH_REF") to attrs(0x80, 0xD0, 0xFF),
                key("MAX_DOC") to attrs(0x99, 0x66, 0xCC),
                key("UNRELATED") to attrs(0x00, 0xFF, 0x00),
            )
        every { loader.loadOverlayForVariant("Mirage") } returns overlay
        // Empty baseline — every clear becomes an empty TextAttributes (the
        // pure overlay-only branch). Baseline restoration is covered by
        // SyntaxModeApplicatorClearByBaselineTest.
        every { loader.loadBaselineForVariant("Mirage") } returns emptyMap()
        every { loader.tierKeys(SyntaxMood.MINIMAL) } returns emptySet()
        every { loader.tierKeys(SyntaxMood.STANDARD) } returns standardKeys
        every { loader.tierKeys(SyntaxMood.RICH) } returns richKeys
        every { loader.tierKeys(SyntaxMood.MAXIMUM) } returns maximumKeys
        StyleAxis.entries.forEach { axis -> every { loader.axisKeys(axis) } returns emptySet() }
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    private fun key(name: String): TextAttributesKey = TextAttributesKey.find(name)

    private fun attrs(
        r: Int,
        g: Int,
        b: Int,
    ): TextAttributes =
        TextAttributes().apply {
            foregroundColor = java.awt.Color(r, g, b)
            fontType = 0
        }

    private fun assertClearedToEmpty(
        attrs: TextAttributes?,
        keyName: String,
    ) {
        assertNotNull(attrs, "$keyName must be present (non-null) — @NotNull setAttributes contract")
        assertNull(attrs.foregroundColor, "$keyName clear payload must have null foreground (empty TextAttributes)")
        assertNull(attrs.backgroundColor, "$keyName clear payload must have null background (empty TextAttributes)")
        assertEquals(0, attrs.fontType, "$keyName clear payload must have default fontType")
    }

    @Test
    fun `mood MINIMAL clears every overlay key to empty TextAttributes (no nulls)`() {
        val result = SyntaxModeApplicator.compute(SyntaxMood.MINIMAL, emptySet(), "Mirage", loader)
        // All four overlay keys must appear and be non-null. With an empty
        // baseline fixture each clear maps to an empty `TextAttributes()`.
        assertEquals(4, result.size, "every overlay key must appear in the result")
        result.forEach { (k, v) -> assertClearedToEmpty(v, k.externalName) }
    }

    @Test
    fun `mood STANDARD keeps STANDARD whitelist and clears others to empty TextAttributes`() {
        val result = SyntaxModeApplicator.compute(SyntaxMood.STANDARD, emptySet(), "Mirage", loader)
        assertNotNull(result[key("STD_DECL")], "STD_DECL must be in active whitelist")
        assertClearedToEmpty(result[key("RICH_REF")], "RICH_REF")
        assertClearedToEmpty(result[key("MAX_DOC")], "MAX_DOC")
        assertClearedToEmpty(result[key("UNRELATED")], "UNRELATED")
    }

    @Test
    fun `mood RICH keeps STANDARD plus RICH and MAX_DOC clears to empty TextAttributes`() {
        val result = SyntaxModeApplicator.compute(SyntaxMood.RICH, emptySet(), "Mirage", loader)
        assertNotNull(result[key("STD_DECL")], "STD_DECL must remain at RICH (cumulative)")
        assertNotNull(result[key("RICH_REF")], "RICH_REF must be in active whitelist at RICH")
        assertClearedToEmpty(result[key("MAX_DOC")], "MAX_DOC")
    }

    @Test
    fun `mood MAXIMUM keeps every tiered key and UNRELATED clears to empty TextAttributes`() {
        val result = SyntaxModeApplicator.compute(SyntaxMood.MAXIMUM, emptySet(), "Mirage", loader)
        assertNotNull(result[key("STD_DECL")], "STD_DECL must remain at MAXIMUM")
        assertNotNull(result[key("RICH_REF")], "RICH_REF must remain at MAXIMUM")
        assertNotNull(result[key("MAX_DOC")], "MAX_DOC must be in active whitelist at MAXIMUM")
        assertClearedToEmpty(result[key("UNRELATED")], "UNRELATED")
    }

    @Test
    fun `no axes leaves the active overlay attrs unmodified (clone, not original)`() {
        val result = SyntaxModeApplicator.compute(SyntaxMood.MAXIMUM, emptySet(), "Mirage", loader)
        val applied = result[key("STD_DECL")]
        assertNotNull(applied)
        // The cloned attrs preserve foreground / fontType from the overlay
        assertEquals(java.awt.Color(0xFF, 0xCC, 0x66), applied.foregroundColor)
        assertEquals(0, applied.fontType)
    }

    @Test
    fun `result map does NOT contain keys absent from overlay (don't touch unrelated keys)`() {
        val result = SyntaxModeApplicator.compute(SyntaxMood.MAXIMUM, emptySet(), "Mirage", loader)
        // Only the 4 overlay keys appear; unrelated platform keys are not in the result
        assertEquals(setOf("STD_DECL", "RICH_REF", "MAX_DOC", "UNRELATED"), result.keys.map { it.externalName }.toSet())
    }

    @Test
    fun `compute is deterministic (same input produces equal result two calls)`() {
        val a = SyntaxModeApplicator.compute(SyntaxMood.RICH, emptySet(), "Mirage", loader)
        val b = SyntaxModeApplicator.compute(SyntaxMood.RICH, emptySet(), "Mirage", loader)
        assertEquals(a.keys, b.keys)
        // Every entry must clone to equal foreground + fontType across re-computes.
        a.entries.forEach { (k, attrsA) ->
            val attrsB = b[k]
            assertNotNull(attrsB)
            assertEquals(attrsA.foregroundColor, attrsB.foregroundColor)
            assertEquals(attrsA.fontType, attrsB.fontType)
        }
    }

    @Test
    fun `unknown variant returns empty result (loader returns empty overlay)`() {
        every { loader.loadOverlayForVariant("Unknown") } returns emptyMap()
        every { loader.loadBaselineForVariant("Unknown") } returns emptyMap()
        val result = SyntaxModeApplicator.compute(SyntaxMood.MAXIMUM, emptySet(), "Unknown", loader)
        assertTrue(result.isEmpty(), "no overlay → no keys to write")
    }
}
