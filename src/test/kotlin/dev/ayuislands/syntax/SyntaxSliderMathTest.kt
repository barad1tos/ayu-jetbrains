package dev.ayuislands.syntax

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * RED-gate test set for `SyntaxPresetCurves.sliderToCurve(value)`.
 *
 * Locks the D-05 slider model BEFORE any implementation exists:
 *  - 50 = identity (`CategoryCurve(0f, 0f)`), the slider zero-point.
 *  - `t = (value - 50) / 50f` ranges -1.0..+1.0; deltas are `t * swing`.
 *  - Below 50 desaturates/dims (negative deltas); above 50 saturates/brightens
 *    (positive deltas).
 *  - The ramp is linear from the 50 midpoint and symmetric around it.
 *  - `sliderToCurve` itself does NOT clamp to `AccentHsl` bounds - clamping is
 *    the applicator's `transformForeground` job; the raw deltas stay within the
 *    swing magnitudes.
 *
 * Assertions are written in terms of SHAPE (identity, sign, linearity,
 * symmetry, monotonicity), not the exact swing magnitudes - `MAX_SAT_SWING` /
 * `MAX_LIGHT_SWING` are runIde-calibrated tuning constants the implementation
 * owns. `SyntaxPresetCurves.sliderToCurve` does NOT exist yet, so this file is
 * expected to FAIL to compile (unresolved reference) - that is the intended
 * RED state. Plan 02 lands the implementation that turns this green.
 */
class SyntaxSliderMathTest {
    // --- Identity at the 50 midpoint -------------------------------------

    @Test
    fun `sliderToCurve(50) is exact identity`() {
        assertEquals(
            CategoryCurve.IDENTITY,
            SyntaxPresetCurves.sliderToCurve(50),
            "50 is the slider zero-point - no change from the preset base (D-05)",
        )
    }

    // --- Sign of deltas above / below 50 ---------------------------------

    @Test
    fun `sliderToCurve(100) saturates and brightens (both deltas positive)`() {
        val curve = SyntaxPresetCurves.sliderToCurve(100)
        assertTrue(curve.saturationDelta > 0f, "above 50 must saturate: $curve")
        assertTrue(curve.lightnessDelta > 0f, "above 50 must brighten: $curve")
    }

    @Test
    fun `sliderToCurve(0) desaturates and dims (both deltas negative)`() {
        val curve = SyntaxPresetCurves.sliderToCurve(0)
        assertTrue(curve.saturationDelta < 0f, "below 50 must desaturate: $curve")
        assertTrue(curve.lightnessDelta < 0f, "below 50 must dim: $curve")
    }

    // --- Linearity from the 50 midpoint ----------------------------------

    @Test
    fun `sliderToCurve is linear from 50 - 75 is half of 100`() {
        val full = SyntaxPresetCurves.sliderToCurve(100)
        val half = SyntaxPresetCurves.sliderToCurve(75)
        assertEquals(
            full.saturationDelta / 2f,
            half.saturationDelta,
            LINEARITY_TOLERANCE,
            "75 is the midpoint between 50 and 100 - saturationDelta must be half of 100's",
        )
        assertEquals(
            full.lightnessDelta / 2f,
            half.lightnessDelta,
            LINEARITY_TOLERANCE,
            "75 is the midpoint between 50 and 100 - lightnessDelta must be half of 100's",
        )
    }

    // --- Symmetry around the 50 midpoint ---------------------------------

    @Test
    fun `sliderToCurve is symmetric around 50 - 75 mirrors 25`() {
        val above = SyntaxPresetCurves.sliderToCurve(75)
        val below = SyntaxPresetCurves.sliderToCurve(25)
        assertEquals(
            above.saturationDelta,
            -below.saturationDelta,
            LINEARITY_TOLERANCE,
            "75 and 25 are equidistant from 50 - saturation deltas must be opposite",
        )
        assertEquals(
            above.lightnessDelta,
            -below.lightnessDelta,
            LINEARITY_TOLERANCE,
            "75 and 25 are equidistant from 50 - lightness deltas must be opposite",
        )
    }

    // --- Monotonicity across the full range ------------------------------

    @Test
    fun `sliderToCurve saturationDelta is strictly increasing across 0,25,50,75,100`() {
        val deltas = SLIDER_SAMPLES.map { SyntaxPresetCurves.sliderToCurve(it).saturationDelta }
        for (index in 0 until deltas.size - 1) {
            assertTrue(
                deltas[index] < deltas[index + 1],
                "saturationDelta must increase from slider ${SLIDER_SAMPLES[index]} " +
                    "(${deltas[index]}) to ${SLIDER_SAMPLES[index + 1]} (${deltas[index + 1]})",
            )
        }
    }

    // --- No self-clamping to AccentHsl bounds ----------------------------

    @Test
    fun `sliderToCurve emits raw symmetric swing deltas - does not pre-clamp`() {
        // The function returns deltas (a swing magnitude), NOT an absolute
        // lightness/saturation. The corrected model raises the lightness swing
        // to a chroma-intent authority (0.17) that exceeds the old AccentHsl
        // floor (0.10), so the no-clamp proof is now expressed structurally: the
        // extremes are exact opposites (perfectly symmetric, un-coerced) and a
        // pre-clamped output could not be. The applicator owns the actual clamp.
        val low = SyntaxPresetCurves.sliderToCurve(0)
        val high = SyntaxPresetCurves.sliderToCurve(100)
        assertEquals(
            high.lightnessDelta,
            -low.lightnessDelta,
            LINEARITY_TOLERANCE,
            "slider 0 / 100 lightness deltas must be exact opposites - raw, un-coerced swing",
        )
        assertEquals(
            high.saturationDelta,
            -low.saturationDelta,
            LINEARITY_TOLERANCE,
            "slider 0 / 100 saturation deltas must be exact opposites - raw, un-coerced swing",
        )
        // The lightness swing now reaches the chroma-intent magnitude, proving
        // the bump from the old sub-floor swing landed.
        assertTrue(
            kotlin.math.abs(high.lightnessDelta) > ACCENT_HSL_FLOOR,
            "lightness swing ${high.lightnessDelta} must exceed the old 0.10 sub-floor (corrected model)",
        )
    }

    companion object {
        private const val LINEARITY_TOLERANCE = 1e-4f

        // AccentHsl.MIN_LIGHTNESS - the corrected lightness swing (chroma intent)
        // now exceeds this, so it is the lower bound the bump must clear.
        private const val ACCENT_HSL_FLOOR = 0.10f

        private val SLIDER_SAMPLES = listOf(0, 25, 50, 75, 100)
    }
}
