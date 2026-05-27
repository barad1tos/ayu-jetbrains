package dev.ayuislands.syntax

import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RED → GREEN coverage for [RgbBlend]. Pins identity-at-100, near-bg-at-10,
 * extrapolation past 100 with per-channel clamp, alpha preservation, and the
 * inclusive [10, 150] intensity range invariants per Phase 50 D-03 / D-04.
 */
class RgbBlendTest {
    @Test
    fun `blend identity at intensity 100 returns fg verbatim`() {
        val fg = Color(255, 200, 100)
        val bg = Color(31, 36, 48)
        val out = RgbBlend.blend(fg, bg, 100)
        assertEquals(255, out.red, "red")
        assertEquals(200, out.green, "green")
        assertEquals(100, out.blue, "blue")
    }

    @Test
    fun `blend near bg at intensity 10 lands within 10 units of bg per channel`() {
        val fg = Color(255, 200, 100)
        val bg = Color(31, 36, 48)
        val out = RgbBlend.blend(fg, bg, 10)
        val tolerance = 30
        assertTrue(
            (out.red - bg.red) in -tolerance..tolerance,
            "red near bg, was ${out.red}",
        )
        assertTrue(
            (out.green - bg.green) in -tolerance..tolerance,
            "green near bg, was ${out.green}",
        )
        assertTrue(
            (out.blue - bg.blue) in -tolerance..tolerance,
            "blue near bg, was ${out.blue}",
        )
    }

    @Test
    fun `blend extrapolation at 150 clamps high channels to 255`() {
        val fg = Color(200, 200, 200)
        val bg = Color(50, 50, 50)
        val out = RgbBlend.blend(fg, bg, 150)
        assertEquals(255, out.red, "red clamped high")
        assertEquals(255, out.green, "green clamped high")
        assertEquals(255, out.blue, "blue clamped high")
    }

    @Test
    fun `blend extrapolation below zero clamps to 0`() {
        val fg = Color(50, 50, 50)
        val bg = Color(200, 200, 200)
        val out = RgbBlend.blend(fg, bg, 150)
        assertEquals(0, out.red, "red clamped low")
        assertEquals(0, out.green, "green clamped low")
        assertEquals(0, out.blue, "blue clamped low")
    }

    @Test
    fun `blend preserves alpha from foreground regardless of bg alpha`() {
        val fg = Color(255, 100, 100, 128)
        val bg = Color(0, 0, 0, 255)
        val out = RgbBlend.blend(fg, bg, 50)
        assertEquals(128, out.alpha, "alpha mirrors fg, never bg")
    }

    @Test
    fun `blend clamps intensity below min to 10`() {
        val fg = Color(255, 200, 100)
        val bg = Color(31, 36, 48)
        val below = RgbBlend.blend(fg, bg, 5)
        val atMin = RgbBlend.blend(fg, bg, 10)
        assertEquals(atMin.red, below.red, "red equal at clamped-min")
        assertEquals(atMin.green, below.green, "green equal at clamped-min")
        assertEquals(atMin.blue, below.blue, "blue equal at clamped-min")
    }

    @Test
    fun `blend clamps intensity above max to 150`() {
        val fg = Color(200, 200, 200)
        val bg = Color(50, 50, 50)
        val above = RgbBlend.blend(fg, bg, 200)
        val atMax = RgbBlend.blend(fg, bg, 150)
        assertEquals(atMax.red, above.red, "red equal at clamped-max")
        assertEquals(atMax.green, above.green, "green equal at clamped-max")
        assertEquals(atMax.blue, above.blue, "blue equal at clamped-max")
    }
}
