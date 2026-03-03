package dev.ayuislands.glow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlowAnimatorTest {
    @Test
    fun `NONE always returns 1 point 0`() {
        val animator = GlowAnimator()
        animator.frame = 0
        assertEquals(1.0f, animator.calculateAlpha(GlowAnimation.NONE))
        animator.frame = 100
        assertEquals(1.0f, animator.calculateAlpha(GlowAnimation.NONE))
    }

    @Test
    fun `PULSE alpha stays within 0 point 3 to 1 point 0 bounds`() {
        val animator = GlowAnimator()
        for (f in 0L..240L) {
            animator.frame = f
            val alpha = animator.calculateAlpha(GlowAnimation.PULSE)
            assertTrue(alpha >= 0.3f, "Frame $f: alpha $alpha should be >= 0.3")
            assertTrue(alpha <= 1.0f, "Frame $f: alpha $alpha should be <= 1.0")
        }
    }

    @Test
    fun `PULSE attack phase increases alpha`() {
        val animator = GlowAnimator()
        // Attack phase: frames 0-17 of a 120-frame cycle (15% attack ratio)
        animator.frame = 0
        val alphaAtStart = animator.calculateAlpha(GlowAnimation.PULSE)
        animator.frame = 9
        val alphaAtMidAttack = animator.calculateAlpha(GlowAnimation.PULSE)
        animator.frame = 17
        val alphaAtPeakAttack = animator.calculateAlpha(GlowAnimation.PULSE)

        assertTrue(
            alphaAtMidAttack > alphaAtStart,
            "Mid-attack ($alphaAtMidAttack) should exceed start ($alphaAtStart)",
        )
        assertTrue(
            alphaAtPeakAttack > alphaAtMidAttack,
            "Peak-attack ($alphaAtPeakAttack) should exceed mid-attack ($alphaAtMidAttack)",
        )
    }

    @Test
    fun `BREATHE alpha stays within 0 point 2 to 1 point 0 bounds`() {
        val animator = GlowAnimator()
        for (f in 0L..480L) {
            animator.frame = f
            val alpha = animator.calculateAlpha(GlowAnimation.BREATHE)
            assertTrue(alpha >= 0.2f, "Frame $f: alpha $alpha should be >= 0.2")
            assertTrue(alpha <= 1.0f, "Frame $f: alpha $alpha should be <= 1.0")
        }
    }

    @Test
    fun `BREATHE produces sinusoidal pattern over 240 frames`() {
        val animator = GlowAnimator()
        // Frame 0: sin(0) = 0 → breath = 0.5 → alpha ~= 0.2 + 0.5*0.8 = 0.6
        animator.frame = 0
        val alphaAt0 = animator.calculateAlpha(GlowAnimation.BREATHE)

        // Frame 60: sin(π/2) = 1 → breath = 1.0 → alpha ~= 0.2 + 1.0*0.8 = 1.0
        animator.frame = 60
        val alphaAt60 = animator.calculateAlpha(GlowAnimation.BREATHE)

        // Frame 180: sin(3π/2) = -1 → breath = 0.0 → alpha ~= 0.2
        animator.frame = 180
        val alphaAt180 = animator.calculateAlpha(GlowAnimation.BREATHE)

        assertTrue(alphaAt60 > alphaAt0, "Peak ($alphaAt60) should exceed start ($alphaAt0)")
        assertTrue(alphaAt60 > alphaAt180, "Peak ($alphaAt60) should exceed trough ($alphaAt180)")
        assertTrue(alphaAt180 < 0.25f, "Trough ($alphaAt180) should be near minimum")
    }

    @Test
    fun `REACTIVE decays boost by 0 point 92 per frame`() {
        val animator = GlowAnimator()
        animator.reactiveBoost = 1.0f

        animator.frame = 0
        val alpha1 = animator.calculateAlpha(GlowAnimation.REACTIVE)
        // After one call: reactiveBoost = 1.0 * 0.92 = 0.92
        val alpha2 = animator.calculateAlpha(GlowAnimation.REACTIVE)
        // After two calls: reactiveBoost = 0.92 * 0.92 = 0.8464

        assertTrue(alpha1 > alpha2, "Alpha should decrease as boost decays ($alpha1 > $alpha2)")
    }

    @Test
    fun `REACTIVE cuts boost to zero below threshold`() {
        val animator = GlowAnimator()
        animator.reactiveBoost = 0.005f // Below the 0.01 threshold

        animator.frame = 0
        val alpha = animator.calculateAlpha(GlowAnimation.REACTIVE)
        // After decay: 0.005 * 0.92 = 0.0046 < 0.01 → reactiveBoost set to 0
        assertEquals(0.0f, animator.reactiveBoost, "Boost should be zeroed below threshold")
        assertEquals(0.4f, alpha, "Alpha should be base (0.4) when boost is zero")
    }
}
