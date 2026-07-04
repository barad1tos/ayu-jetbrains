package dev.ayuislands.accent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Locks the per-rung hex judgment that historically distinguished the three
 * accent-ladder walkers. [AccentHexPolicy.LENIENT]'s language-fallback carve-out
 * is the load-bearing branch: the live resolver has always rejected an invalid
 * language fallback while letting every other rung pass invalid values through
 * raw — flattening the policies would silently change which rung wins.
 */
class AccentHexPolicyTest {
    @Test
    fun `STRICT accepts only valid hex and normalizes padding`() {
        assertEquals("#112233", AccentHexPolicy.STRICT.accept(AccentResolver.Source.PROJECT_OVERRIDE, "  #112233  "))
        assertNull(AccentHexPolicy.STRICT.accept(AccentResolver.Source.PROJECT_OVERRIDE, "not-a-hex"))
        assertNull(AccentHexPolicy.STRICT.accept(AccentResolver.Source.LANGUAGE_FALLBACK_OVERRIDE, "not-a-hex"))
    }

    @Test
    fun `LENIENT passes invalid hex through raw for non-fallback rungs`() {
        assertEquals(
            "not-a-hex",
            AccentHexPolicy.LENIENT.accept(AccentResolver.Source.PROJECT_OVERRIDE, "not-a-hex"),
        )
        assertEquals(
            "not-a-hex",
            AccentHexPolicy.LENIENT.accept(AccentResolver.Source.FORCED_LANGUAGE_OVERRIDE, "not-a-hex"),
        )
        assertEquals(
            "not-a-hex",
            AccentHexPolicy.LENIENT.accept(AccentResolver.Source.LANGUAGE_OVERRIDE, "not-a-hex"),
        )
    }

    @Test
    fun `LENIENT rejects invalid hex on the language fallback rung`() {
        assertNull(AccentHexPolicy.LENIENT.accept(AccentResolver.Source.LANGUAGE_FALLBACK_OVERRIDE, "not-a-hex"))
    }

    @Test
    fun `LENIENT normalizes valid hex on every rung including language fallback`() {
        assertEquals(
            "#73D0FF",
            AccentHexPolicy.LENIENT.accept(AccentResolver.Source.LANGUAGE_FALLBACK_OVERRIDE, " #73D0FF "),
        )
        assertEquals(
            "#112233",
            AccentHexPolicy.LENIENT.accept(AccentResolver.Source.PROJECT_FALLBACK, "#112233"),
        )
    }

    @Test
    fun `RAW passes every value through verbatim`() {
        assertEquals("#112233", AccentHexPolicy.RAW.accept(AccentResolver.Source.PROJECT_OVERRIDE, "#112233"))
        assertEquals(
            "not-a-hex",
            AccentHexPolicy.RAW.accept(AccentResolver.Source.LANGUAGE_FALLBACK_OVERRIDE, "not-a-hex"),
        )
    }
}
