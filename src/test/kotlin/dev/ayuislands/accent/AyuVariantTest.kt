package dev.ayuislands.accent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AyuVariantTest {
    @Test
    fun `fromThemeName returns MIRAGE for Mirage theme names`() {
        assertEquals(AyuVariant.MIRAGE, AyuVariant.fromThemeName("Ayu Islands Mirage"))
        assertEquals(AyuVariant.MIRAGE, AyuVariant.fromThemeName("Ayu Islands Mirage (Islands UI)"))
    }

    @Test
    fun `fromThemeName returns DARK for Dark theme names`() {
        assertEquals(AyuVariant.DARK, AyuVariant.fromThemeName("Ayu Islands Dark"))
        assertEquals(AyuVariant.DARK, AyuVariant.fromThemeName("Ayu Islands Dark (Islands UI)"))
    }

    @Test
    fun `fromThemeName returns LIGHT for Light theme names`() {
        assertEquals(AyuVariant.LIGHT, AyuVariant.fromThemeName("Ayu Islands Light"))
        assertEquals(AyuVariant.LIGHT, AyuVariant.fromThemeName("Ayu Islands Light (Islands UI)"))
    }

    @Test
    fun `fromThemeName returns null for unknown theme names`() {
        assertNull(AyuVariant.fromThemeName("Darcula"))
        assertNull(AyuVariant.fromThemeName("IntelliJ Light"))
        assertNull(AyuVariant.fromThemeName(""))
        assertNull(AyuVariant.fromThemeName("Ayu Islands"))
    }

    @Test
    fun `each variant has two theme names`() {
        for (variant in AyuVariant.entries) {
            assertEquals(2, variant.themeNames.size, "${variant.name} should have exactly 2 theme names")
        }
    }

    @Test
    fun `dark variants use Darcula parent scheme`() {
        assertEquals("Darcula", AyuVariant.MIRAGE.parentSchemeName)
        assertEquals("Darcula", AyuVariant.DARK.parentSchemeName)
    }

    @Test
    fun `light variant uses Default parent scheme`() {
        assertEquals("Default", AyuVariant.LIGHT.parentSchemeName)
    }

    @Test
    fun `default accents are valid hex color strings`() {
        val hexPattern = Regex("^#[0-9A-Fa-f]{6}$")
        for (variant in AyuVariant.entries) {
            assertTrue(
                hexPattern.matches(variant.defaultAccent),
                "${variant.name} defaultAccent '${variant.defaultAccent}' should be a valid hex color",
            )
        }
    }

    @Test
    fun `neutral grays are valid hex color strings`() {
        val hexPattern = Regex("^#[0-9A-Fa-f]{6}$")
        for (variant in AyuVariant.entries) {
            assertTrue(
                hexPattern.matches(variant.neutralGray),
                "${variant.name} neutralGray '${variant.neutralGray}' should be a valid hex color",
            )
        }
    }

    @Test
    fun `default accent values match specification`() {
        assertEquals("#FFCC66", AyuVariant.MIRAGE.defaultAccent)
        assertEquals("#E6B450", AyuVariant.DARK.defaultAccent)
        assertEquals("#F29718", AyuVariant.LIGHT.defaultAccent)
    }

    @Test
    fun `exhaustive variant count`() {
        assertEquals(3, AyuVariant.entries.size)
    }

    @Test
    fun `all theme names are unique across variants`() {
        val allNames = AyuVariant.entries.flatMap { it.themeNames }
        assertEquals(allNames.size, allNames.toSet().size, "Theme names should be unique across all variants")
    }

    @Test
    fun `fromThemeName covers every registered theme name`() {
        for (variant in AyuVariant.entries) {
            for (name in variant.themeNames) {
                assertEquals(
                    variant,
                    AyuVariant.fromThemeName(name),
                    "fromThemeName('$name') should return ${variant.name}",
                )
            }
        }
    }
}
