package dev.ayuislands.accent

import java.awt.Color
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Algorithmic lock on the uniform-hue invariant that Phase 40-09 introduces:
 * `ChromeTintBlender.blend` must produce the SAME hue across the five real
 * chrome base colors at any intensity value, while preserving each base's
 * original luminance so the existing visual hierarchy (status bar darker than
 * toolbar, etc.) survives the rework.
 *
 * VERIFICATION Gap 1 (40-VERIFICATION.md, runIde smoke 2026-04-22) surfaced the
 * regression — at intensity=20 each surface rendered as a visibly different
 * color because per-channel RGB lerp mixed each base toward the accent without
 * homogenising hue. This suite RED-locks the corrected algorithm.
 *
 * After the 40-chrome refactor the blender takes `baseColor: Color` directly
 * (no UIManager reads), so the test feeds stock values as literal [Color]s
 * instead of mocking `UIManager.getColor`.
 */
class ChromeTintBlenderHueUniformityTest {
    // Stock base table drawn from VERIFICATION Gap 1 — the five real chrome surfaces
    // observed in the runIde sandbox (Mirage LAF).
    private val chromeBases: Map<String, Color> =
        mapOf(
            "StatusBar.background" to Color(0x1F, 0x24, 0x30),
            "NavBar.background" to Color(0x25, 0x2E, 0x38),
            "ToolWindow.Stripe.background" to Color(0x25, 0x2E, 0x38),
            "MainToolbar.background" to Color(0x25, 0x2E, 0x38),
            "ToolWindow.Header.borderColor" to Color(0x30, 0x3A, 0x47),
        )

    // Representative accents sweep: Cyan (from runIde repro), warm Orange, Pink, Violet.
    private val accents: List<Color> =
        listOf(
            Color(0x5C, 0xCF, 0xE6),
            Color(0xE6, 0xB4, 0x50),
            Color(0xFF, 0x6B, 0x9D),
            Color(0x7F, 0x52, 0xFF),
        )

    @Test
    fun `blend produces the same hue across all 5 chrome bases at intensity 20`() {
        accents.forEach { accent ->
            assertUniformHue(accent, intensity = 20)
        }
    }

    @Test
    fun `blend produces the same hue across all 5 chrome bases at intensity 50`() {
        accents.forEach { accent ->
            assertUniformHue(accent, intensity = 50)
        }
    }

    @Test
    fun `blend produces the same hue across all 5 chrome bases at intensity 80`() {
        accents.forEach { accent ->
            assertUniformHue(accent, intensity = 80)
        }
    }

    @Test
    fun `blend preserves luminance hierarchy - tinted StatusBar stays darker than tinted MainToolbar`() {
        accents.forEach { accent ->
            val tintedStatus =
                ChromeTintBlender.blend(
                    accent,
                    chromeBases.getValue("StatusBar.background"),
                    TintIntensity.of(50),
                )
            val tintedToolbar =
                ChromeTintBlender.blend(
                    accent,
                    chromeBases.getValue("MainToolbar.background"),
                    TintIntensity.of(50),
                )
            val statusLuma = luma(tintedStatus)
            val toolbarLuma = luma(tintedToolbar)
            assertTrue(
                statusLuma < toolbarLuma,
                "StatusBar ($statusLuma) must remain darker than MainToolbar " +
                    "($toolbarLuma) after tinting with accent=$accent",
            )
        }
    }

    @Test
    fun `at intensity 100 blend hue equals accent hue within epsilon`() {
        accents.forEach { accent ->
            val accentHue = hueOf(accent)
            chromeBases.forEach { (key, base) ->
                val tinted = ChromeTintBlender.blend(accent, base, TintIntensity.of(100))
                val delta = hueDelta(hueOf(tinted), accentHue)
                assertTrue(
                    delta <= HUE_EPSILON,
                    "intensity=100 should match accent hue exactly (ε=$HUE_EPSILON); " +
                        "got Δ=$delta for key=$key accent=$accent → tinted=$tinted",
                )
            }
        }
    }

    @Test
    fun `at intensity 0 blend returns base color unchanged per channel`() {
        accents.forEach { accent ->
            chromeBases.forEach { (key, base) ->
                val result = ChromeTintBlender.blend(accent, base, TintIntensity.of(0))
                assertTrue(
                    result.red == base.red,
                    "red mismatch for $key: expected ${base.red}, got ${result.red}",
                )
                assertTrue(
                    result.green == base.green,
                    "green mismatch for $key: expected ${base.green}, got ${result.green}",
                )
                assertTrue(
                    result.blue == base.blue,
                    "blue mismatch for $key: expected ${base.blue}, got ${result.blue}",
                )
                assertTrue(result.alpha == OPAQUE_ALPHA, "alpha must stay 255 (D-05)")
            }
        }
    }

    @Test
    fun `saturation of output at intensity 50 stays within damped accent saturation band`() {
        // Defends against the hue-replacement formula producing a desaturated gray.
        // For saturated accents the output saturation at intensity=50 should be within
        // `[0.4, 1.05] × accent.S` — lower bound because we lerp base (S≈0) → target
        // (S≈accent.S * damp), upper bound allows HSB rounding tolerance.
        val saturatedAccent = Color(0x5C, 0xCF, 0xE6) // Cyan — clearly chromatic.
        val accentSat = saturationOf(saturatedAccent)
        chromeBases.forEach { (key, base) ->
            val tinted = ChromeTintBlender.blend(saturatedAccent, base, TintIntensity.of(50))
            val tintedSat = saturationOf(tinted)
            val lower = accentSat * 0.4f
            val upper = accentSat * 1.05f
            assertTrue(
                tintedSat in lower..upper,
                "intensity=50 saturation out of band for $key: " +
                    "expected [$lower, $upper], got $tintedSat",
            )
        }
    }

    private fun assertUniformHue(
        accent: Color,
        intensity: Int,
    ) {
        val wrapped = TintIntensity.of(intensity)
        val tintedByKey =
            chromeBases.mapValues { (_, base) -> ChromeTintBlender.blend(accent, base, wrapped) }
        val hues = tintedByKey.mapValues { (_, tinted) -> hueOf(tinted) }
        val reference = hues.values.first()
        hues.forEach { (key, h) ->
            val delta = hueDelta(h, reference)
            val ref = hues.keys.first()
            assertTrue(
                delta <= HUE_EPSILON,
                "hue spread exceeds ε=$HUE_EPSILON at intensity=$intensity accent=$accent: " +
                    "reference=$ref(h=$reference) vs $key(h=$h) Δ=$delta; " +
                    "tintedByKey=$tintedByKey",
            )
        }
    }

    private fun hueOf(color: Color): Float {
        val hsb = FloatArray(3)
        Color.RGBtoHSB(color.red, color.green, color.blue, hsb)
        return hsb[0]
    }

    private fun saturationOf(color: Color): Float {
        val hsb = FloatArray(3)
        Color.RGBtoHSB(color.red, color.green, color.blue, hsb)
        return hsb[1]
    }

    /**
     * Circular hue distance on the `[0, 1)` unit circle — hues 0.99 and 0.01 are
     * adjacent (Δ=0.02), not far apart (Δ=0.98).
     */
    private fun hueDelta(
        a: Float,
        b: Float,
    ): Float {
        val raw = abs(a - b)
        return if (raw > 0.5f) 1f - raw else raw
    }

    private fun luma(color: Color): Double {
        val weighted = LUMA_R * color.red + LUMA_G * color.green + LUMA_B * color.blue
        return weighted / CHANNEL_MAX
    }

    companion object {
        // ε ≈ 0.01 on the unit hue circle ≈ 3.6° on a 360° wheel — the perceptual
        // floor at which hue differences start looking "the same color" to the eye.
        // The pre-rework RGB lerp fails this at intensity 20/50/80 because per-surface
        // base hue differences (StatusBar luminance lower than NavBar) drift through
        // the lerp; the HSB-replacement rework holds well within this band.
        private const val HUE_EPSILON = 0.01f
        private const val OPAQUE_ALPHA = 255
        private const val LUMA_R = 0.299
        private const val LUMA_G = 0.587
        private const val LUMA_B = 0.114
        private const val CHANNEL_MAX = 255.0
    }
}
