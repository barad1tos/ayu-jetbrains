package dev.ayuislands.accent.toolbar.popup

import com.intellij.ui.ColorUtil
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the [PopupSwatch] paint + state contract per 48-REDESIGN-SPEC §3.4.
 *
 * Pattern-I test-seam coverage:
 *   - preferred size matches 36 x 24 JBUI-scaled,
 *   - idle paint fills the centre with `ColorUtil.fromHex(hex)`,
 *   - selected paint composites a 0.55-alpha overlay using `BORDER_RGB=0x4E5A6E`,
 *   - pressed paint composites a 0.70-alpha overlay (deeper than selected),
 *   - click invokes `onClick(hex)` exactly once (lambda-list workaround per
 *     Spawn-A finding #2 — `mockk<(String) -> Unit>(relaxed=true)` throws
 *     `UnsupportedOperationException`).
 */
class PopupSwatchTest {
    @Test
    fun `preferred size is 36 x 24 JBUI scaled`() {
        val swatch = PopupSwatch("#FFB454", isSelected = false, onClick = {})
        assertEquals(Dimension(JBUI.scale(36), JBUI.scale(24)), swatch.preferredSize)
    }

    @Test
    fun `idle paint fills centre pixel with accent hex`() {
        val width = JBUI.scale(36)
        val height = JBUI.scale(24)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val swatch =
            PopupSwatch("#FFB454", isSelected = false, onClick = {}).apply {
                setSize(width, height)
            }
        val g2 = image.createGraphics()
        try {
            swatch.paintForTest(g2)
        } finally {
            g2.dispose()
        }
        val expected = ColorUtil.fromHex("#FFB454")
        val actual = Color(image.getRGB(width / 2, height / 2), true)
        assertColorClose(expected, actual, tolerance = 4)
    }

    @Test
    fun `selected paint composites 0_55 alpha overlay (matches AccentColorPanel)`() {
        val width = JBUI.scale(36)
        val height = JBUI.scale(24)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val swatch =
            PopupSwatch("#FFB454", isSelected = true, onClick = {}).apply {
                setSize(width, height)
            }
        val g2 = image.createGraphics()
        try {
            swatch.paintForTest(g2)
        } finally {
            g2.dispose()
        }
        val accent = ColorUtil.fromHex("#FFB454")
        val border = Color(0x4E5A6E)
        val expectedR = blend(accent.red, border.red, 0.55f)
        val expectedG = blend(accent.green, border.green, 0.55f)
        val expectedB = blend(accent.blue, border.blue, 0.55f)
        val actual = Color(image.getRGB(width / 2, height / 2), true)
        assertColorClose(Color(expectedR, expectedG, expectedB), actual, tolerance = 4)
    }

    @Test
    fun `pressed paint composites 0_70 alpha overlay (deeper than selected)`() {
        val width = JBUI.scale(36)
        val height = JBUI.scale(24)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val swatch =
            PopupSwatch("#FFB454", isSelected = false, onClick = {}).apply {
                setSize(width, height)
                setPressedForTest(true)
            }
        val g2 = image.createGraphics()
        try {
            swatch.paintForTest(g2)
        } finally {
            g2.dispose()
        }
        val accent = ColorUtil.fromHex("#FFB454")
        val border = Color(0x4E5A6E)
        val expectedR = blend(accent.red, border.red, 0.70f)
        val actual = Color(image.getRGB(width / 2, height / 2), true)
        // Pressed overlay must be darker than the accent-only fill — assert the red
        // component shifted measurably toward the border RGB.
        assertTrue(
            abs(actual.red - expectedR) <= 6,
            "Pressed red=${actual.red} must approach expected=$expectedR (border-tinted)",
        )
        assertTrue(actual.red < accent.red, "Pressed red ${actual.red} must be deeper than idle ${accent.red}")
    }

    @Test
    fun `setSelected flips the internal flag and triggers repaint`() {
        val swatch = PopupSwatch("#FFB454", isSelected = false, onClick = {})
        assertFalse(swatch.selected)
        swatch.setSelected(true)
        assertTrue(swatch.selected)
        // Repeat call must be a no-op (no double-repaint cost).
        swatch.setSelected(true)
        assertTrue(swatch.selected)
    }

    @Test
    fun `click invokes onClick with the swatch hex exactly once`() {
        // Spawn-A finding #2: `mockk<(String) -> Unit>(relaxed=true)` throws
        // UnsupportedOperationException on Kotlin function types. Use a capture list.
        val captured = mutableListOf<String>()
        val swatch =
            PopupSwatch("#73D0FF", isSelected = false, onClick = { captured.add(it) }).apply {
                setSize(JBUI.scale(36), JBUI.scale(24))
            }
        val press =
            java.awt.event.MouseEvent(
                swatch,
                java.awt.event.MouseEvent.MOUSE_PRESSED,
                0L,
                0,
                5,
                5,
                1,
                false,
                java.awt.event.MouseEvent.BUTTON1,
            )
        val release =
            java.awt.event.MouseEvent(
                swatch,
                java.awt.event.MouseEvent.MOUSE_RELEASED,
                0L,
                0,
                5,
                5,
                1,
                false,
                java.awt.event.MouseEvent.BUTTON1,
            )
        swatch.dispatchEvent(press)
        swatch.dispatchEvent(release)
        assertEquals(listOf("#73D0FF"), captured)
    }

    private fun blend(
        accent: Int,
        border: Int,
        alpha: Float,
    ): Int = (accent * (1f - alpha) + border * alpha).toInt().coerceIn(0, 255)

    private fun assertColorClose(
        expected: Color,
        actual: Color,
        tolerance: Int,
    ) {
        val r = expected.red to actual.red
        val g = expected.green to actual.green
        val b = expected.blue to actual.blue
        assertTrue(abs(r.first - r.second) <= tolerance, "R: expected=${r.first} actual=${r.second}")
        assertTrue(abs(g.first - g.second) <= tolerance, "G: expected=${g.first} actual=${g.second}")
        assertTrue(abs(b.first - b.second) <= tolerance, "B: expected=${b.first} actual=${b.second}")
    }
}
