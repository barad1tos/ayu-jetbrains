package dev.ayuislands.accent

import com.intellij.openapi.editor.colors.EditorColorsScheme
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.Color
import java.awt.Window
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Retains native refresh and canonical palette contracts after Studio replaces the old apply owner. */
class AccentApplicatorTest {
    private val mockScheme = mockk<EditorColorsScheme>(relaxed = true)

    @AfterTest
    fun tearDown() = unmockkAll()

    @Test
    fun `repaintAllWindows repaints multiple windows`() {
        val window1 = mockk<Window>(relaxed = true)
        val window2 = mockk<Window>(relaxed = true)
        val window3 = mockk<Window>(relaxed = true)
        // repaintAllWindows skips non-displayable windows.
        every { window1.isDisplayable } returns true
        every { window2.isDisplayable } returns true
        every { window3.isDisplayable } returns true

        AccentApplicator.repaintAllWindows(arrayOf(window1, window2, window3))

        verify { window1.repaint() }
        verify { window2.repaint() }
        verify { window3.repaint() }
    }

    @Test
    fun `repaintAllWindows handles empty array without error`() {
        AccentApplicator.repaintAllWindows(emptyArray())
        // No exception thrown = pass
    }

    @Test
    fun `ALWAYS_ON_UI_KEYS contains expected count of keys`() {
        val keys = AccentUiKeys.accent
        assertEquals(12, keys.size, "Expected 12 always-on UI keys")
    }

    @Test
    fun `ALWAYS_ON_UI_KEYS does not tint shared OnePixelDivider`() {
        val keys = AccentUiKeys.accent

        assertFalse(
            "OnePixelDivider.background" in keys,
            "OnePixelDivider is shared across editor splitters, tool windows, " +
                "Settings, and diff views; " +
                "always-on accent writes would over-tint IDE-wide dividers",
        )
    }

    @Test
    fun `always-on editor palette contains expected color keys`() {
        val keys = AccentEditorPalette.colorKeys
        assertEquals(3, keys.size, "Expected 3 always-on editor color keys")
    }

    @Test
    fun `always-on editor palette contains expected attribute count`() {
        val overrides = AccentEditorPalette.attributes(mockScheme, Color.ORANGE)
        assertEquals(9, overrides.count(), "Expected 9 attribute overrides")
    }

    @Test
    fun `always-on attribute order starts with bookmarks`() {
        val first = AccentEditorPalette.attributes(mockScheme, Color.ORANGE).first()
        assertEquals("BOOKMARKS_ATTRIBUTES", first.first.externalName)
    }

    @Test
    fun `DARK_FOREGROUND constant has correct value`() {
        val darkFg = Color(AccentUiKeys.DARK_FOREGROUND_RGBA, true)
        assertEquals(Color(0x1F2430), darkFg)
    }

    @Test
    fun `TAB_ACCENT_BG_ALPHA constant is 50`() {
        val alpha = AccentUiKeys.TAB_ALPHA
        assertEquals(50, alpha)
    }
}
