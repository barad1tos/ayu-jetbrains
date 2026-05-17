package dev.ayuislands.accent.toolbar

import dev.ayuislands.accent.AyuVariant
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Locks the `(variant, islandsUi) -> theme name` contract for [VariantThemeNameResolver].
 *
 * Pattern L from `RECURRING_PITFALLS.md` — enum-size regression lock. If
 * [AyuVariant.entries] grows past three (e.g. an `OASIS` variant), the size
 * assertion fails on purpose so the implementer is forced to extend the
 * resolver tests with the new variant's plain + Islands UI mappings in the
 * same change. Without the lock, a missing entry would silently bypass the
 * `Islands UI` flavour check at runtime via the resolver's `error(...)` and
 * surface only when the user clicked the corresponding radio.
 */
class VariantThemeNameResolverTest {
    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `resolveThemeName MIRAGE plain returns Ayu Mirage`() {
        assertEquals("Ayu Mirage", VariantThemeNameResolver.resolveThemeName(AyuVariant.MIRAGE, false))
    }

    @Test
    fun `resolveThemeName MIRAGE Islands UI returns Ayu Mirage (Islands UI)`() {
        assertEquals(
            "Ayu Mirage (Islands UI)",
            VariantThemeNameResolver.resolveThemeName(AyuVariant.MIRAGE, true),
        )
    }

    @Test
    fun `resolveThemeName DARK plain returns Ayu Dark`() {
        assertEquals("Ayu Dark", VariantThemeNameResolver.resolveThemeName(AyuVariant.DARK, false))
    }

    @Test
    fun `resolveThemeName DARK Islands UI returns Ayu Dark (Islands UI)`() {
        assertEquals(
            "Ayu Dark (Islands UI)",
            VariantThemeNameResolver.resolveThemeName(AyuVariant.DARK, true),
        )
    }

    @Test
    fun `resolveThemeName LIGHT plain returns Ayu Light`() {
        assertEquals("Ayu Light", VariantThemeNameResolver.resolveThemeName(AyuVariant.LIGHT, false))
    }

    @Test
    fun `resolveThemeName LIGHT Islands UI returns Ayu Light (Islands UI)`() {
        assertEquals(
            "Ayu Light (Islands UI)",
            VariantThemeNameResolver.resolveThemeName(AyuVariant.LIGHT, true),
        )
    }

    @Test
    fun `AyuVariant entries size lock — three variants exactly`() {
        // Pattern L regression lock. If a fourth variant lands without an Islands UI
        // entry, the per-variant tests above stay green but downstream radio /
        // checkbox UI silently no-ops for the new variant. Failing here forces the
        // implementer to extend [VariantThemeNameResolver]'s test coverage in the
        // same change. The size literal matches the
        // [dev.ayuislands.accent.AccentResolverSourceLabelTest] convention.
        assertEquals(
            3,
            AyuVariant.entries.size,
            "AyuVariant.entries grew — extend VariantThemeNameResolverTest with the new variant",
        )
    }

    @Test
    fun `every AyuVariant entry has exactly two themeNames — plain and Islands UI`() {
        // Pattern L follow-up: the resolver requires a 1:1 plain / Islands UI pair per
        // variant. A variant with only a plain entry would `error(...)` when the user
        // toggled Islands UI on; this test surfaces the schema drift at the enum
        // level instead.
        assertTrue(
            AyuVariant.entries.all { it.themeNames.size == 2 },
            "Every AyuVariant must declare exactly two themeNames " +
                "(plain + Islands UI): ${AyuVariant.entries.map { it.name to it.themeNames }}",
        )
    }

    @Test
    fun `resolveThemeName errors when requested flavour is missing`() {
        // Simulate a hypothetical schema drift where a variant lost its Islands UI
        // entry. Mocking `AyuVariant.MIRAGE`'s `themeNames` (object-scoped enum
        // instance) keeps the production enum pristine while exercising the
        // resolver's `error(...)` branch — the failsafe Pitfall 7 would otherwise
        // bypass silently at the `LafManager.findLaf` null return.
        mockkObject(AyuVariant.MIRAGE)
        every { AyuVariant.MIRAGE.themeNames } returns setOf("Ayu Mirage")

        assertFailsWith<IllegalStateException> {
            VariantThemeNameResolver.resolveThemeName(AyuVariant.MIRAGE, true)
        }
    }
}
