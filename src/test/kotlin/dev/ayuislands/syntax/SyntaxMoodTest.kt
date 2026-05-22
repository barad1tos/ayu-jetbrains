package dev.ayuislands.syntax

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SyntaxMoodTest {
    @Test
    fun `enum has exactly four entries in declaration order`() {
        val expected =
            listOf(
                SyntaxMood.MINIMAL,
                SyntaxMood.STANDARD,
                SyntaxMood.RICH,
                SyntaxMood.MAXIMUM,
            )
        assertEquals(expected, SyntaxMood.entries.toList())
    }

    @Test
    fun `displayName values match SYNTAX-02 spec`() {
        assertEquals("Minimal", SyntaxMood.MINIMAL.displayName)
        assertEquals("Standard", SyntaxMood.STANDARD.displayName)
        assertEquals("Rich", SyntaxMood.RICH.displayName)
        assertEquals("Maximum", SyntaxMood.MAXIMUM.displayName)
    }

    @Test
    fun `approximateKeyCount matches Plan 49-01 measured tier sizes (UI rounding to nearest 100)`() {
        // Values reflect Plan 49-01 actual curated counts (baseline 1488 + per-tier delta):
        //   MINIMAL  = 1488 + 0   → rounded 1500
        //   STANDARD = 1488 + 52  → 1540 → rounded 1550
        //   RICH     = 1488 + 205 → 1693 → rounded 1700
        //   MAXIMUM  = 1488 + 570 → 2058 → rounded 2100
        assertEquals(1500, SyntaxMood.MINIMAL.approximateKeyCount)
        assertEquals(1550, SyntaxMood.STANDARD.approximateKeyCount)
        assertEquals(1700, SyntaxMood.RICH.approximateKeyCount)
        assertEquals(2100, SyntaxMood.MAXIMUM.approximateKeyCount)
    }

    @Test
    fun `KDoc documents approximateKeyCount tolerance (revision iteration 1, warning 7a)`() {
        // Source-regex Pattern L lock: ensures the KDoc keeps explaining the
        // UI-rounding policy so future EXPANSION-N reviewers know the enum value
        // is intentional and the canonical count lives in SyntaxOverlayLoader.
        val path = Path.of("src/main/kotlin/dev/ayuislands/syntax/SyntaxMood.kt")
        val src = Files.readString(path)
        assertTrue(src.contains("approximateKeyCount"))
        assertTrue(src.contains("UI rounding") || src.contains("UI-rounded"))
        assertTrue(src.contains("SyntaxOverlayLoader.tierKeys"))
    }

    @Test
    fun `fromName null returns MAXIMUM default per D-02`() {
        assertSame(SyntaxMood.MAXIMUM, SyntaxMood.fromName(null))
    }

    @Test
    fun `fromName empty string returns MAXIMUM default`() {
        assertSame(SyntaxMood.MAXIMUM, SyntaxMood.fromName(""))
    }

    @Test
    fun `fromName valid enum name returns matching entry`() {
        assertSame(SyntaxMood.MINIMAL, SyntaxMood.fromName("MINIMAL"))
        assertSame(SyntaxMood.STANDARD, SyntaxMood.fromName("STANDARD"))
        assertSame(SyntaxMood.RICH, SyntaxMood.fromName("RICH"))
        assertSame(SyntaxMood.MAXIMUM, SyntaxMood.fromName("MAXIMUM"))
    }

    @Test
    fun `fromName unknown value returns MAXIMUM default`() {
        assertSame(SyntaxMood.MAXIMUM, SyntaxMood.fromName("BOGUS_TIER"))
    }

    @Test
    fun `fromName is case-sensitive (lowercase rejected)`() {
        assertSame(SyntaxMood.MAXIMUM, SyntaxMood.fromName("standard"))
        assertSame(SyntaxMood.MAXIMUM, SyntaxMood.fromName("Minimal"))
    }
}
