package dev.ayuislands.vcs

import java.awt.Color
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [VcsColorBlender] — the pure HSB-lerp helper that mixes a stock
 * XML baseline color with a per-category vibrant target by the user-configured
 * intensity percent.
 *
 * Semantics under test (HSB lerp invariants for the VCS palette):
 *  - intensity=0 → base color per-channel, alpha from base
 *  - intensity=100 → target color per-channel, alpha from base
 *  - intensity=50 → midpoint via HSB lerp, alpha from base
 *  - alpha is ALWAYS preserved from base (translucent SELECTION_BACKGROUND keys
 *    must not lose their transparency when "vibrified")
 *  - hue uses shortest-arc lerp (red↔magenta crossing 0/360 boundary does NOT
 *    pass through the spectrum's full range)
 *  - out-of-range intensities are clamped via [VcsIntensity.of] without throwing
 */
class VcsColorBlenderTest {
    private val mutedBlue = Color(0x73, 0xB8, 0xFF) // 2.6.2 DIFF_MODIFIED (Dark)
    private val vibrantCyan = Color(0x59, 0xC2, 0xFF) // pre-2.6.2 DIFF_MODIFIED target

    @Test
    fun `blend at intensity 0 returns the base color unchanged per channel`() {
        val result = VcsColorBlender.blend(mutedBlue, vibrantCyan, VcsIntensity.of(0))
        assertEquals(mutedBlue.red, result.red)
        assertEquals(mutedBlue.green, result.green)
        assertEquals(mutedBlue.blue, result.blue)
        assertEquals(mutedBlue.alpha, result.alpha)
    }

    @Test
    fun `blend at intensity 100 returns the target color per channel`() {
        val result = VcsColorBlender.blend(mutedBlue, vibrantCyan, VcsIntensity.of(100))
        assertEquals(vibrantCyan.red, result.red)
        assertEquals(vibrantCyan.green, result.green)
        assertEquals(vibrantCyan.blue, result.blue)
        // Alpha sourced from base (base is opaque -> 255).
        assertEquals(mutedBlue.alpha, result.alpha)
    }

    @Test
    fun `blend at intensity 50 produces a value between base and target hue`() {
        val result = VcsColorBlender.blend(mutedBlue, vibrantCyan, VcsIntensity.of(50))
        val baseHue = hueOf(mutedBlue)
        val targetHue = hueOf(vibrantCyan)
        val resultHue = hueOf(result)
        // Result hue should lie between base and target on the shortest arc.
        val (lo, hi) = listOf(baseHue, targetHue).let { it.min() to it.max() }
        assertTrue(
            resultHue in lo..hi || abs(targetHue - baseHue) > HUE_HALF_ROTATION,
            "intensity=50 hue should fall between base ($baseHue) and target ($targetHue), got $resultHue",
        )
    }

    @Test
    fun `blend preserves base alpha when base is translucent`() {
        val translucentBase = Color(0x33, 0x88, 0xFF, 0x40) // SELECTION_BACKGROUND-like
        val opaqueTarget = Color(0x59, 0xC2, 0xFF, 0xFF)
        val result = VcsColorBlender.blend(translucentBase, opaqueTarget, VcsIntensity.of(75))
        assertEquals(0x40, result.alpha, "Translucent base alpha must survive the blend")
    }

    @Test
    fun `blend preserves base alpha at full intensity even with opaque target`() {
        val translucentBase = Color(0x33, 0x88, 0xFF, 0x40)
        val opaqueTarget = Color(0xFF, 0xFF, 0xFF, 0xFF)
        val result = VcsColorBlender.blend(translucentBase, opaqueTarget, VcsIntensity.of(100))
        assertEquals(0x40, result.alpha, "Alpha-from-base rule applies at intensity=100 too")
    }

    @Test
    fun `blend uses shortest-arc hue lerp across the 0-360 wraparound`() {
        // Red sits at hue ~0; magenta sits at hue ~300. Shortest arc between them
        // is 60 (going through the wraparound), NOT 300 (going through the full
        // spectrum). A naive lerp passes through orange/yellow/green/cyan/blue —
        // the shortest-arc lerp goes red → magenta directly.
        val red = Color(0xFF, 0x00, 0x00)
        val magenta = Color(0xFF, 0x00, 0xFF)
        val result = VcsColorBlender.blend(red, magenta, VcsIntensity.of(50))
        // Result should still register as "warm" — not pass through green/cyan.
        // Green channel staying low is the strongest indicator.
        assertTrue(
            result.green < SHORTEST_ARC_GREEN_CEILING,
            "shortest-arc lerp red→magenta must not pass through green; got green=${result.green}",
        )
    }

    @Test
    fun `blend clamps out-of-range intensity without throwing`() {
        val belowRange = VcsColorBlender.blend(mutedBlue, vibrantCyan, VcsIntensity.of(-50))
        assertEquals(mutedBlue.red, belowRange.red)
        assertEquals(mutedBlue.green, belowRange.green)
        assertEquals(mutedBlue.blue, belowRange.blue)

        val aboveRange = VcsColorBlender.blend(mutedBlue, vibrantCyan, VcsIntensity.of(250))
        assertEquals(vibrantCyan.red, aboveRange.red)
        assertEquals(vibrantCyan.green, aboveRange.green)
        assertEquals(vibrantCyan.blue, aboveRange.blue)
    }

    @Test
    fun `blend at zero intensity returns base even when target is wildly different hue`() {
        // Identity short-circuit must not consult target at all at intensity=0.
        val result = VcsColorBlender.blend(mutedBlue, Color(0xFF, 0x00, 0x00), VcsIntensity.of(0))
        assertEquals(mutedBlue.red, result.red)
        assertEquals(mutedBlue.green, result.green)
        assertEquals(mutedBlue.blue, result.blue)
    }

    private fun hueOf(color: Color): Float = Color.RGBtoHSB(color.red, color.green, color.blue, null)[0]

    private companion object {
        /**
         * Maximum allowed green-channel value for a shortest-arc red↔magenta lerp.
         * A naive long-arc lerp would pass through green/cyan/blue and pump the
         * green channel well above 0x40. Set the ceiling well below 0x40 so the
         * test definitively distinguishes the two paths without flakiness on
         * rounding edges.
         */
        const val SHORTEST_ARC_GREEN_CEILING: Int = 0x20

        /** 180° on the HSB hue circle, normalised to `[0, 1]`. */
        const val HUE_HALF_ROTATION: Float = 0.5f
    }
}
