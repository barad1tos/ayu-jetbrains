@file:Suppress("UnstableApiUsage")

package dev.ayuislands.accent

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.laf.UIThemeLookAndFeelInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AyuVariantTest {
    private lateinit var lafManager: LafManager

    @BeforeTest
    fun setUp() {
        lafManager = mockk(relaxed = true)
        mockkStatic(LafManager::class)
        every { LafManager.getInstance() } returns lafManager
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `fromThemeName returns MIRAGE for Mirage theme names`() {
        assertEquals(AyuVariant.MIRAGE, AyuVariant.fromThemeName("Ayu Mirage"))
        assertEquals(AyuVariant.MIRAGE, AyuVariant.fromThemeName("Ayu Mirage (Islands UI)"))
    }

    @Test
    fun `fromThemeName returns DARK for Dark theme names`() {
        assertEquals(AyuVariant.DARK, AyuVariant.fromThemeName("Ayu Dark"))
        assertEquals(AyuVariant.DARK, AyuVariant.fromThemeName("Ayu Dark (Islands UI)"))
    }

    @Test
    fun `fromThemeName returns LIGHT for Light theme names`() {
        assertEquals(AyuVariant.LIGHT, AyuVariant.fromThemeName("Ayu Light"))
        assertEquals(AyuVariant.LIGHT, AyuVariant.fromThemeName("Ayu Light (Islands UI)"))
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

    @Test
    fun `detect returns variant for matching theme`() {
        val themeLaf = mockk<UIThemeLookAndFeelInfo>(relaxed = true)
        every { themeLaf.name } returns "Ayu Mirage"
        every { lafManager.currentUIThemeLookAndFeel } returns themeLaf

        assertEquals(AyuVariant.MIRAGE, AyuVariant.detect())
    }

    @Test
    fun `detect returns null for non-Ayu theme`() {
        val themeLaf = mockk<UIThemeLookAndFeelInfo>(relaxed = true)
        every { themeLaf.name } returns "Darcula"
        every { lafManager.currentUIThemeLookAndFeel } returns themeLaf

        assertNull(AyuVariant.detect())
    }

    @Test
    fun `isIslandsUi returns true for Islands UI theme`() {
        val themeLaf = mockk<UIThemeLookAndFeelInfo>(relaxed = true)
        every { themeLaf.name } returns "Ayu Mirage (Islands UI)"
        every { lafManager.currentUIThemeLookAndFeel } returns themeLaf

        assertTrue(AyuVariant.isIslandsUi())
    }

    @Test
    fun `isIslandsUi returns false for non-Islands theme`() {
        val themeLaf = mockk<UIThemeLookAndFeelInfo>(relaxed = true)
        every { themeLaf.name } returns "Ayu Mirage"
        every { lafManager.currentUIThemeLookAndFeel } returns themeLaf

        assertFalse(AyuVariant.isIslandsUi())
    }

    @Test
    fun `isIslandsUi returns false for non-Ayu theme`() {
        val themeLaf = mockk<UIThemeLookAndFeelInfo>(relaxed = true)
        every { themeLaf.name } returns "Darcula"
        every { lafManager.currentUIThemeLookAndFeel } returns themeLaf

        assertFalse(AyuVariant.isIslandsUi())
    }
}
