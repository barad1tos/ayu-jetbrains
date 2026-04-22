package dev.ayuislands.accent

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.awt.Color
import javax.swing.UIManager
import kotlin.math.abs
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

        // Default stubs: Panel.background available, arbitrary base keys resolve to darkBase.
        every { UIManager.getColor("Panel.background") } returns darkBase
        every { UIManager.getColor(any<String>()) } answers {
            val key = firstArg<String>()
            if (key == "Panel.background") darkBase else darkBase
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
    fun `blend at intensity 100 output hue matches accent hue`() {
        // Phase 40-09 contract update: at max intensity the blender returns the
        // accent HUE on the base's luminance (target = HSB(accent.H, accent.S,
        // base.B)), NOT the accent's RGB per-channel. The legacy per-channel
        // identity was superseded by the hue-uniformity invariant — see
        // ChromeTintBlenderHueUniformityTest for the 5-surface lock.
        val result = ChromeTintBlender.blend(accentRed, "Panel.background", 100)
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
        // Phase 40-09 contract update: replaced the stale RGB per-channel
        // midpoint assertion with a hue-convergence invariant. The new blender
        // does NOT produce an RGB midpoint between base and accent — it lerps
        // the base toward a synthesised HSB target (accent.H, accent.S, base.B),
        // so the output hue converges from the base hue toward the accent hue.
        // The neutral darkBase has S≈0 (undefined hue), so any tint moves the
        // output toward the accent hue within ε at 50%.
        val result = ChromeTintBlender.blend(accentRed, "Panel.background", 50)
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
        // Below range — clamps to 0 (no tint, base color returned).
        val clampedLow = ChromeTintBlender.blend(accentRed, "Panel.background", -10)
        assertEquals(darkBase.red, clampedLow.red)
        assertEquals(darkBase.green, clampedLow.green)
        assertEquals(darkBase.blue, clampedLow.blue)

        // Above range — clamps to 100. Phase 40-09 contract update: at max
        // intensity the blender returns accent-hue on base-luminance, not raw
        // accent RGB. Assert the hue matches the accent hue within ε.
        val clampedHigh = ChromeTintBlender.blend(accentRed, "Panel.background", 150)
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
        val result = ChromeTintBlender.blend(translucentAccent, "Panel.background", 50)
        assertEquals(255, result.alpha, "D-05 enforces opaque RGB output")
    }

    @Test
    fun `blend falls back to Panel background when base key is missing`() {
        val panelFallback = Color(0x20, 0x20, 0x20)
        every { UIManager.getColor("Missing.key") } returns null
        every { UIManager.getColor("Panel.background") } returns panelFallback

        val result = ChromeTintBlender.blend(Color.RED, "Missing.key", 50)
        // Phase 40-09 contract update: the legacy RGB midpoint (~143) was
        // superseded by the hue-replacement algorithm — the new blender
        // produces accent-hue on panelFallback luminance, not an RGB midpoint.
        // The fallback chain itself is the property under test here: the
        // blender must not throw / return null / return transparent when the
        // primary key misses. Assert non-null opaque output + hue convergence.
        assertEquals(255, result.alpha, "Fallback path must still return opaque output (D-05)")
        val resultHue = hueOf(result)
        val accentHue = hueOf(Color.RED)
        assertTrue(
            hueDelta(resultHue, accentHue) <= HUE_EPSILON,
            "fallback output hue should converge toward accent: got $resultHue vs $accentHue",
        )
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
