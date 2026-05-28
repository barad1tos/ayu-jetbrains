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
        assertEquals(255, result.alpha, "Result must be opaque")
    }

    @Test
    fun `blend at max visible intensity output hue matches accent hue`() {
        // Contract: at max visible intensity the blender returns the accent HUE on
        // the base's luminance (target = HSB(accent.H, accent.S, base.B)),
        // NOT the accent's RGB per-channel. The legacy per-channel identity
        // was superseded by the hue-uniformity invariant — see
        // ChromeTintBlenderHueUniformityTest for the 5-surface lock.
        val result = ChromeTintBlender.blend(accentRed, darkBase, TintIntensity.of(TintIntensity.MAX))
        val resultHue = hueOf(result)
        val accentHue = hueOf(accentRed)
        assertTrue(
            hueDelta(resultHue, accentHue) <= HUE_EPSILON,
            "intensity=100 hue should match accent hue: got $resultHue vs $accentHue",
        )
        assertEquals(255, result.alpha, "Result must be opaque")
    }

    @Test
    fun `blend at minimum visible intensity stays closer to stock than accent hue`() {
        val stockStatusBar = Color(0x2E, 0x35, 0x44)
        val result = ChromeTintBlender.blend(Color(0x5C, 0xCF, 0xE6), stockStatusBar, TintIntensity.of(10))
        val resultHue = hueOf(result)
        val stockHue = hueOf(stockStatusBar)
        val accentHue = hueOf(Color(0x5C, 0xCF, 0xE6))

        assertTrue(
            hueDelta(resultHue, stockHue) < hueDelta(resultHue, accentHue),
            "intensity=10 must stay visually closer to stock chrome than to the accent hue",
        )
        assertTrue(
            result.green - stockStatusBar.green <= LOW_INTENSITY_GREEN_DELTA_MAX,
            "intensity=10 must be a subtle accent; got green delta ${result.green - stockStatusBar.green}",
        )
    }

    @Test
    fun `blend crosses the red hue seam by the shortest path`() {
        val scenarios =
            listOf(
                HueSeamScenario(baseHue = 0.95f, accentHue = 0.05f),
                HueSeamScenario(baseHue = 0.05f, accentHue = 0.95f),
            )

        for (scenario in scenarios) {
            val base = Color.getHSBColor(scenario.baseHue, 0.45f, 0.35f)
            val accent = Color.getHSBColor(scenario.accentHue, 0.90f, 0.80f)
            val result = ChromeTintBlender.blend(accent, base, TintIntensity.of(25))
            val resultHue = hueOf(result)

            assertTrue(
                hueDelta(resultHue, RED_SEAM_HUE) <= HUE_SEAM_EPSILON,
                "mid-intensity hue must cross the red seam, not detour through cyan: " +
                    "baseHue=${scenario.baseHue}, accentHue=${scenario.accentHue}, resultHue=$resultHue",
            )
            assertTrue(
                hueDelta(resultHue, CYAN_DETOUR_HUE) > CYAN_DETOUR_MIN_DELTA,
                "red-seam blend must stay away from the opposite cyan hue: resultHue=$resultHue",
            )
        }
    }

    @Test
    fun `blend at intensity 50 output hue converges to accent hue`() {
        // Contract: the blender does NOT produce an RGB midpoint between base
        // and accent — it lerps the base toward a synthesised HSB target
        // (accent.H, accent.S, base.B), so max visible intensity reaches the
        // accent hue while lower values stay closer to the stock chrome hue.
        val result = ChromeTintBlender.blend(accentRed, darkBase, TintIntensity.of(50))
        val resultHue = hueOf(result)
        val accentHue = hueOf(accentRed)
        assertTrue(
            hueDelta(resultHue, accentHue) <= HUE_EPSILON,
            "intensity=50 hue should converge toward accent: got $resultHue vs $accentHue",
        )
        assertEquals(255, result.alpha, "Result must be opaque")
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
        assertEquals(255, result.alpha, "Blender contract enforces opaque RGB output")
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

    // --- Hue-space invariant helpers ---

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

    private data class HueSeamScenario(
        val baseHue: Float,
        val accentHue: Float,
    )

    companion object {
        // ε ≈ 0.01 on the unit hue circle ≈ 3.6° on a 360° wheel — same ε used
        // by ChromeTintBlenderHueUniformityTest (kept consistent across suites).
        private const val HUE_EPSILON = 0.01f
        private const val HUE_SEAM_EPSILON = 0.03f
        private const val RED_SEAM_HUE = 0.0f
        private const val CYAN_DETOUR_HUE = 0.5f
        private const val CYAN_DETOUR_MIN_DELTA = 0.35f
        private const val LOW_INTENSITY_GREEN_DELTA_MAX = 10
    }
}
