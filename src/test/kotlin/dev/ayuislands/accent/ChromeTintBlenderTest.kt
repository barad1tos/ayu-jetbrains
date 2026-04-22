package dev.ayuislands.accent

import com.intellij.ui.ColorUtil
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.awt.Color
import javax.swing.UIManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChromeTintBlenderTest {
    private val darkBase = Color(0x20, 0x20, 0x20)
    private val accentRed = Color(0xFF, 0x00, 0x00)

    @BeforeTest
    fun setUp() {
        mockkStatic(UIManager::class)
        mockkStatic(ColorUtil::class)

        // Default stubs: Panel.background available, arbitrary base keys resolve to darkBase.
        every { UIManager.getColor("Panel.background") } returns darkBase
        every { UIManager.getColor(any<String>()) } answers {
            val key = firstArg<String>()
            if (key == "Panel.background") darkBase else darkBase
        }

        // ColorUtil.isDark default: delegate to a luma check so tests stay deterministic.
        every { ColorUtil.isDark(any<Color>()) } answers {
            val c = firstArg<Color>()
            val luma = (0.299 * c.red + 0.587 * c.green + 0.114 * c.blue) / 255.0
            luma < 0.5
        }
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `blend at intensity 0 returns the base color unchanged per channel`() {
        val result = ChromeTintBlender.blend(accentRed, "Panel.background", 0)
        assertEquals(darkBase.red, result.red)
        assertEquals(darkBase.green, result.green)
        assertEquals(darkBase.blue, result.blue)
        assertEquals(255, result.alpha, "Result must be opaque (D-05)")
    }

    @Test
    fun `blend at intensity 100 returns the accent unchanged per channel`() {
        val result = ChromeTintBlender.blend(accentRed, "Panel.background", 100)
        assertEquals(accentRed.red, result.red)
        assertEquals(accentRed.green, result.green)
        assertEquals(accentRed.blue, result.blue)
        assertEquals(255, result.alpha, "Result must be opaque (D-05)")
    }

    @Test
    fun `blend at intensity 50 produces the midpoint per channel with rounding tolerance`() {
        val result = ChromeTintBlender.blend(accentRed, "Panel.background", 50)
        val expectedR = (darkBase.red + accentRed.red) / 2
        val expectedG = (darkBase.green + accentRed.green) / 2
        val expectedB = (darkBase.blue + accentRed.blue) / 2
        assertTrue(
            Math.abs(result.red - expectedR) <= 1,
            "red midpoint tolerance: expected ~$expectedR, got ${result.red}",
        )
        assertTrue(
            Math.abs(result.green - expectedG) <= 1,
            "green midpoint tolerance: expected ~$expectedG, got ${result.green}",
        )
        assertTrue(
            Math.abs(result.blue - expectedB) <= 1,
            "blue midpoint tolerance: expected ~$expectedB, got ${result.blue}",
        )
    }

    @Test
    fun `blend clamps out-of-range intensity without throwing`() {
        // Below range — clamps to 0 (no tint, base color returned).
        val clampedLow = ChromeTintBlender.blend(accentRed, "Panel.background", -10)
        assertEquals(darkBase.red, clampedLow.red)
        assertEquals(darkBase.green, clampedLow.green)
        assertEquals(darkBase.blue, clampedLow.blue)

        // Above range — clamps to 100 (full accent returned).
        val clampedHigh = ChromeTintBlender.blend(accentRed, "Panel.background", 150)
        assertEquals(accentRed.red, clampedHigh.red)
        assertEquals(accentRed.green, clampedHigh.green)
        assertEquals(accentRed.blue, clampedHigh.blue)
    }

    @Test
    fun `blend always returns opaque alpha 255 even with translucent accent input`() {
        val translucentAccent = Color(0, 0, 0, 0x80)
        val result = ChromeTintBlender.blend(translucentAccent, "Panel.background", 50)
        assertEquals(255, result.alpha, "D-05 enforces opaque RGB output")
    }

    @Test
    fun `blend falls back to Panel background when base key is missing`() {
        val panelFallback = Color(0x20, 0x20, 0x20)
        every { UIManager.getColor("Missing.key") } returns null
        every { UIManager.getColor("Panel.background") } returns panelFallback

        val result = ChromeTintBlender.blend(Color.RED, "Missing.key", 50)
        // Midpoint between panel fallback (0x20) and Color.RED (255,0,0):
        // red ~ (0x20 + 255) / 2 = 143
        val expectedR = (panelFallback.red + Color.RED.red) / 2
        val expectedG = (panelFallback.green + Color.RED.green) / 2
        val expectedB = (panelFallback.blue + Color.RED.blue) / 2
        assertTrue(Math.abs(result.red - expectedR) <= 1)
        assertTrue(Math.abs(result.green - expectedG) <= 1)
        assertTrue(Math.abs(result.blue - expectedB) <= 1)
    }

    @Test
    fun `blend falls back to accent when both base key and Panel background are null`() {
        every { UIManager.getColor("Missing.key") } returns null
        every { UIManager.getColor("Panel.background") } returns null

        val result = ChromeTintBlender.blend(Color.RED, "Missing.key", 50)
        // When bg == accent, lerp between accent and accent is accent.
        assertEquals(Color.RED.red, result.red)
        assertEquals(Color.RED.green, result.green)
        assertEquals(Color.RED.blue, result.blue)
        assertEquals(255, result.alpha)
    }

    @Test
    fun `contrastForeground returns white for dark tinted background`() {
        val dark = Color(0x1F, 0x24, 0x30)
        every { ColorUtil.isDark(dark) } returns true
        assertEquals(Color.WHITE, ChromeTintBlender.contrastForeground(dark))
    }

    @Test
    fun `contrastForeground returns DARK_FOREGROUND for light tinted background`() {
        val light = Color(0xE6, 0xE6, 0xE6)
        every { ColorUtil.isDark(light) } returns false
        assertEquals(Color(0x1F, 0x24, 0x30), ChromeTintBlender.contrastForeground(light))
    }

    @Test
    fun `blend with tiny intensity rounds per channel without going negative`() {
        val base = Color(0, 0, 0)
        val nearBase = Color(0x0A, 0, 0)
        every { UIManager.getColor("Tiny.key") } returns base

        val result = ChromeTintBlender.blend(nearBase, "Tiny.key", 1)
        // 1% of 10 = 0.1, plus 0.5 bias = 0.6, toInt = 0. Result red stays at 0.
        assertEquals(0, result.red, "Rounding bias must not push below the base channel")
        assertEquals(0, result.green)
        assertEquals(0, result.blue)
    }
}
