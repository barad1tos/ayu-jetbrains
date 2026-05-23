package dev.ayuislands.syntax

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.awt.Color
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * H10 fix — RED-before-GREEN coverage for the
 * `syntax-mood-noop-on-editor` Bug #2 round-2 root cause.
 *
 * Platform contract: `EditorColorsSchemeImpl.setAttributes(key, attrs)` has
 * `@NotNull` on the second parameter (verified via `javap -c -p` against the
 * 2025.2.3 `app-client.jar` — bytecode at offset 8-13 calls
 * `$$$reportNull$$$0` if `attrs` is null). The legacy
 * `SyntaxModeApplicator.compute` contract that returned `null` for keys
 * outside the active mood whitelist therefore caused a flood of
 * `IllegalArgumentException` swallowed by `SyntaxModeService.writeSchemeAttributes`,
 * leaving the active scheme stuck at MAXIMUM forever.
 *
 * The fix restores baseline values for non-whitelisted overlay keys
 * (reusing `SyntaxOverlayLoader.loadBaselineForVariant` from commit
 * `949b332`) and emits an empty `TextAttributes()` for pure overlay-only
 * keys with no baseline counterpart. The compute contract becomes
 * `Map<TextAttributesKey, TextAttributes>` — non-null values across the
 * board, no null escape valve.
 *
 * Each test here asserts the NEW contract; all of them MUST fail against
 * the legacy `Map<..., TextAttributes?>` signature, proving they form a
 * proper RED gate before the GREEN implementation flip.
 *
 * Pure-function transforms — loader fully mocked, no platform deps.
 */
class SyntaxModeApplicatorClearByBaselineTest {
    private lateinit var loader: SyntaxOverlayLoader
    private lateinit var keyCache: MutableMap<String, TextAttributesKey>

    @BeforeTest
    fun setUp() {
        keyCache = mutableMapOf()
        mockkStatic(TextAttributesKey::class)
        every { TextAttributesKey.find(any<String>()) } answers {
            val name = firstArg<String>()
            keyCache.getOrPut(name) { mockk(relaxed = true) { every { externalName } returns name } }
        }

        loader = mockk(relaxed = true)

        // Overlay has one STANDARD key, one RICH key, one MAXIMUM key, plus
        // one overlay-only key without a baseline counterpart. Two of the
        // three tier-managed keys (RESTORABLE_RICH, RESTORABLE_MAX) have a
        // baseline entry; OVERLAY_ONLY does NOT.
        val overlay =
            mapOf(
                key("STD_DECL") to attrs(0xFF, 0xCC, 0x66),
                key("RESTORABLE_RICH") to attrs(0x80, 0xD0, 0xFF),
                key("RESTORABLE_MAX") to attrs(0x99, 0x66, 0xCC),
                key("OVERLAY_ONLY") to attrs(0x00, 0xFF, 0x00),
            )
        every { loader.loadOverlayForVariant("Mirage") } returns overlay

        // Baseline holds pristine Ayu values for two overlay keys. OVERLAY_ONLY
        // is deliberately absent — the spec says emit empty TextAttributes
        // for that case.
        val baseline =
            mapOf(
                key("RESTORABLE_RICH") to attrs(0x10, 0x20, 0x30),
                key("RESTORABLE_MAX") to attrs(0x40, 0x50, 0x60),
            )
        every { loader.loadBaselineForVariant("Mirage") } returns baseline

        every { loader.tierKeys(SyntaxMood.MINIMAL) } returns emptySet()
        every { loader.tierKeys(SyntaxMood.STANDARD) } returns setOf(key("STD_DECL"))
        every { loader.tierKeys(SyntaxMood.RICH) } returns setOf(key("RESTORABLE_RICH"))
        every { loader.tierKeys(SyntaxMood.MAXIMUM) } returns setOf(key("RESTORABLE_MAX"))
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
            foregroundColor = Color(r, g, b)
            fontType = 0
        }

    @Test
    fun `compute returns non-null value for every overlay key (no null escape valve)`() {
        val result = SyntaxModeApplicator.compute(SyntaxMood.MINIMAL, emptySet(), "Mirage", loader)
        // Legacy contract emitted four nulls. New contract must emit four
        // non-null TextAttributes — baseline restore or empty fallback.
        result.forEach { (mapKey, mapAttrs) ->
            assertNotNull(
                mapAttrs,
                "compute must never emit null for any overlay key (key=${mapKey.externalName}) — " +
                    "EditorColorsSchemeImpl.setAttributes rejects null via @NotNull",
            )
        }
    }

    @Test
    fun `MINIMAL mood restores baseline for keys with baseline counterpart`() {
        val result = SyntaxModeApplicator.compute(SyntaxMood.MINIMAL, emptySet(), "Mirage", loader)
        val restored = result[key("RESTORABLE_RICH")]
        assertNotNull(restored, "RESTORABLE_RICH must be present (non-null) at MINIMAL")
        assertEquals(
            Color(0x10, 0x20, 0x30),
            restored.foregroundColor,
            "MINIMAL must restore the pristine baseline value, not a clone of the overlay",
        )
    }

    @Test
    fun `MINIMAL mood emits empty TextAttributes for pure overlay-only key (no baseline counterpart)`() {
        val result = SyntaxModeApplicator.compute(SyntaxMood.MINIMAL, emptySet(), "Mirage", loader)
        val cleared = result[key("OVERLAY_ONLY")]
        assertNotNull(
            cleared,
            "OVERLAY_ONLY has no baseline counterpart — must emit empty TextAttributes, never null",
        )
        // Empty attrs ≡ no override ≡ IDE language default. All "color" fields
        // must be unset (null) and fontType must be the default (0).
        assertNull(
            cleared.foregroundColor,
            "empty TextAttributes for overlay-only clear must have null foreground (no override)",
        )
        assertNull(
            cleared.backgroundColor,
            "empty TextAttributes for overlay-only clear must have null background (no override)",
        )
        assertEquals(0, cleared.fontType, "empty TextAttributes must have default fontType (no bold/italic)")
    }

    @Test
    fun `STANDARD mood restores baseline for RICH and MAXIMUM keys (outside whitelist)`() {
        val result = SyntaxModeApplicator.compute(SyntaxMood.STANDARD, emptySet(), "Mirage", loader)

        // STD_DECL is whitelisted → cloned overlay value (NOT baseline).
        val whitelisted = result[key("STD_DECL")]
        assertNotNull(whitelisted)
        assertEquals(
            Color(0xFF, 0xCC, 0x66),
            whitelisted.foregroundColor,
            "whitelisted overlay key keeps the overlay clone, not the baseline",
        )

        // RESTORABLE_RICH is NOT in STANDARD whitelist → baseline restored.
        val rich = result[key("RESTORABLE_RICH")]
        assertNotNull(rich, "RESTORABLE_RICH must be present (non-null) at STANDARD")
        assertNotEquals(
            Color(0x80, 0xD0, 0xFF),
            rich.foregroundColor,
            "RESTORABLE_RICH must NOT keep the overlay value — it falls back to baseline at STANDARD",
        )
        assertEquals(
            Color(0x10, 0x20, 0x30),
            rich.foregroundColor,
            "STANDARD must restore RESTORABLE_RICH to its pristine baseline value",
        )

        // RESTORABLE_MAX is NOT in STANDARD whitelist → baseline restored.
        val max = result[key("RESTORABLE_MAX")]
        assertNotNull(max, "RESTORABLE_MAX must be present (non-null) at STANDARD")
        assertEquals(
            Color(0x40, 0x50, 0x60),
            max.foregroundColor,
            "STANDARD must restore RESTORABLE_MAX to its pristine baseline value",
        )
    }

    @Test
    fun `RICH mood whitelists STANDARD and RICH, restores baseline for MAXIMUM`() {
        val result = SyntaxModeApplicator.compute(SyntaxMood.RICH, emptySet(), "Mirage", loader)

        // STD_DECL is whitelisted → overlay clone.
        val std = result[key("STD_DECL")]
        assertNotNull(std)
        assertEquals(Color(0xFF, 0xCC, 0x66), std.foregroundColor)

        // RESTORABLE_RICH is whitelisted at RICH → overlay clone.
        val rich = result[key("RESTORABLE_RICH")]
        assertNotNull(rich)
        assertEquals(Color(0x80, 0xD0, 0xFF), rich.foregroundColor)

        // RESTORABLE_MAX is NOT in RICH whitelist → baseline restored.
        val max = result[key("RESTORABLE_MAX")]
        assertNotNull(max)
        assertEquals(Color(0x40, 0x50, 0x60), max.foregroundColor)
    }

    @Test
    fun `OVERLAY_ONLY key is cleared (empty TextAttributes) at every mood`() {
        // The pure-overlay-only key has no baseline. It is also in NO tier
        // whitelist in this fixture. At every mood the result must be empty
        // TextAttributes — never null, never the overlay clone, never a
        // baseline lookup (since baseline does not contain it).
        for (mood in listOf(SyntaxMood.MINIMAL, SyntaxMood.STANDARD, SyntaxMood.RICH, SyntaxMood.MAXIMUM)) {
            val result = SyntaxModeApplicator.compute(mood, emptySet(), "Mirage", loader)
            val cleared = result[key("OVERLAY_ONLY")]
            assertNotNull(
                cleared,
                "OVERLAY_ONLY must be emitted at mood=$mood (never null — @NotNull contract)",
            )
            assertNull(
                cleared.foregroundColor,
                "OVERLAY_ONLY at mood=$mood must clear via empty TextAttributes (null fg)",
            )
        }
    }
}
