package dev.ayuislands.accent

import java.awt.Color
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [ChromeTintBlender] — the pure-math helper that produces an
 * opaque tinted [Color] from an accent + stock base. The blender has no
 * UIManager dependency — callers pass `baseColor: Color` directly (see
 * [ChromeBaseColors] for the snapshot that holds the stock baseline).
 */
class ChromeTintBlenderTest {
    private val darkBase = Color(0x20, 0x20, 0x20)
    private val accentRed = Color(0xFF, 0x00, 0x00)

    @Test
    fun `blend at intensity 0 returns the base color unchanged per channel`() {
        val result = ChromeTintBlender.blend(accentRed, darkBase, TintIntensity.of(0))
        assertEquals(darkBase.red, result.red)
        assertEquals(darkBase.green, result.green)
        assertEquals(darkBase.blue, result.blue)
        assertEquals(255, result.alpha, "Result must be opaque (D-05)")
    }

    @Test
    fun `blend at intensity 100 output hue matches accent hue`() {
        // Phase 40-09 contract: at max intensity the blender returns the accent
        // HUE on the base's luminance (target = HSB(accent.H, accent.S, base.B)),
        // NOT the accent's RGB per-channel. The legacy per-channel identity
        // was superseded by the hue-uniformity invariant — see
        // ChromeTintBlenderHueUniformityTest for the 5-surface lock.
        val result = ChromeTintBlender.blend(accentRed, darkBase, TintIntensity.of(100))
        val resultHue = hueOf(result)
        val accentHue = hueOf(accentRed)
        assertTrue(
            hueDelta(resultHue, accentHue) <= HUE_EPSILON,
            "intensity=100 hue should match accent hue: got $resultHue vs $accentHue",
        )
        assertEquals(255, result.alpha, "Result must be opaque (D-05)")
    }

    @Test
    fun `blend at intensity 50 output hue converges toward accent hue`() {
        // Phase 40-09 contract: the new blender does NOT produce an RGB midpoint
        // between base and accent — it lerps the base toward a synthesised HSB
        // target (accent.H, accent.S, base.B), so the output hue converges from
        // the base hue toward the accent hue. The neutral darkBase has S≈0
        // (undefined hue), so any tint moves the output toward the accent hue
        // within ε at 50%.
        val result = ChromeTintBlender.blend(accentRed, darkBase, TintIntensity.of(50))
        val resultHue = hueOf(result)
        val accentHue = hueOf(accentRed)
        assertTrue(
            hueDelta(resultHue, accentHue) <= HUE_EPSILON,
            "intensity=50 hue should converge toward accent: got $resultHue vs $accentHue",
        )
        assertEquals(255, result.alpha, "Result must be opaque (D-05)")
    }

    @Test
    fun `blend clamps out-of-range intensity without throwing`() {
        // Below range — wrapper clamps to MIN (0), so no tint: base color returned.
        val clampedLow = ChromeTintBlender.blend(accentRed, darkBase, TintIntensity.of(-10))
        assertEquals(darkBase.red, clampedLow.red)
        assertEquals(darkBase.green, clampedLow.green)
        assertEquals(darkBase.blue, clampedLow.blue)

        // Above range — wrapper clamps to MAX; hue still converges to accent.
        val clampedHigh = ChromeTintBlender.blend(accentRed, darkBase, TintIntensity.of(150))
        val clampedHue = hueOf(clampedHigh)
        val accentHue = hueOf(accentRed)
        assertTrue(
            hueDelta(clampedHue, accentHue) <= HUE_EPSILON,
            "clamped high hue should match accent hue: got $clampedHue vs $accentHue",
        )
    }

    @Test
    fun `blend always returns opaque alpha 255 even with translucent accent input`() {
        val translucentAccent = Color(0, 0, 0, 0x80)
        val result = ChromeTintBlender.blend(translucentAccent, darkBase, TintIntensity.of(50))
        assertEquals(255, result.alpha, "D-05 enforces opaque RGB output")
    }

    @Test
    fun `blend with tiny intensity rounds per channel without going negative`() {
        val base = Color(0, 0, 0)
        val nearBase = Color(0x0A, 0, 0)

        val result = ChromeTintBlender.blend(nearBase, base, TintIntensity.of(1))
        // Both channels are zero; 1% of a zero-chroma base stays at zero. The
        // test guards against negative-rounding bugs in the lerp math.
        assertEquals(0, result.red, "Rounding bias must not push below the base channel")
        assertEquals(0, result.green)
        assertEquals(0, result.blue)
    }

    // --- Phase 40-09 helpers (hue-space invariants) ---

    private fun hueOf(color: Color): Float {
        val hsb = FloatArray(3)
        Color.RGBtoHSB(color.red, color.green, color.blue, hsb)
        return hsb[0]
    }

    /**
     * Circular hue distance on the `[0, 1)` unit circle — hues 0.99 and 0.01
     * are adjacent (Δ=0.02), not far apart (Δ=0.98).
     */
    private fun hueDelta(
        a: Float,
        b: Float,
    ): Float {
        val raw = abs(a - b)
        return if (raw > 0.5f) 1f - raw else raw
    }

    companion object {
        // ε ≈ 0.01 on the unit hue circle ≈ 3.6° on a 360° wheel — same ε used
        // by ChromeTintBlenderHueUniformityTest (kept consistent across suites).
        private const val HUE_EPSILON = 0.01f
    }
}
