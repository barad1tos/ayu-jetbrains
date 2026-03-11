package dev.ayuislands.glow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlowAnimatorTest {
    /** Helper: set a private field on the animator. */
    private fun GlowAnimator.setPrivateField(
        name: String,
        value: Any,
    ) {
        val field = GlowAnimator::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(this, value)
    }

    /** Helper: get a private field from the animator. */
    private fun GlowAnimator.getPrivateField(name: String): Any? {
        val field = GlowAnimator::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(this)
    }

    // ---- calculateAlpha tests (existing) ----

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
    fun `PULSE release phase decreases alpha after attack peak`() {
        val animator = GlowAnimator()
        // Attack peaks around frame 18 (15% of 120), release starts after
        animator.frame = 18
        val alphaAtPeak = animator.calculateAlpha(GlowAnimation.PULSE)
        animator.frame = 60
        val alphaAtMidRelease = animator.calculateAlpha(GlowAnimation.PULSE)
        animator.frame = 110
        val alphaAtLateRelease = animator.calculateAlpha(GlowAnimation.PULSE)

        assertTrue(
            alphaAtPeak > alphaAtMidRelease,
            "Peak ($alphaAtPeak) should exceed mid-release ($alphaAtMidRelease)",
        )
        assertTrue(
            alphaAtMidRelease > alphaAtLateRelease,
            "Mid-release ($alphaAtMidRelease) should exceed late-release ($alphaAtLateRelease)",
        )
    }

    @Test
    fun `PULSE release clamps to zero when formula goes negative`() {
        val animator = GlowAnimator()
        // Near end of cycle the release formula ((1-cycle)/0.85) could go below 0
        // coerceAtLeast(0f) ensures alpha stays at the PULSE_MIN_ALPHA minimum
        animator.frame = 119 // The last frame of the cycle
        val alpha = animator.calculateAlpha(GlowAnimation.PULSE)
        assertTrue(alpha >= 0.3f, "Alpha at end of cycle ($alpha) should be >= PULSE_MIN_ALPHA")
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

    @Test
    fun `REACTIVE alpha clamped to 1 point 0 when boost is very high`() {
        val animator = GlowAnimator()
        animator.reactiveBoost = 2.0f // Artificially high boost

        animator.frame = 0
        val alpha = animator.calculateAlpha(GlowAnimation.REACTIVE)
        // (0.4 + 2.0*0.92 * 0.6).coerceAtMost(1.0f) should clamp to 1.0
        assertTrue(alpha <= 1.0f, "Alpha ($alpha) should be clamped to 1.0")
    }

    @Test
    fun `REACTIVE with zero boost returns base alpha`() {
        val animator = GlowAnimator()
        animator.reactiveBoost = 0.0f

        animator.frame = 0
        val alpha = animator.calculateAlpha(GlowAnimation.REACTIVE)
        assertEquals(0.4f, alpha, "Zero boost should yield base alpha 0.4")
    }

    // ---- stop() tests ----

    @Test
    fun `stop resets animation state and clears callback`() {
        val animator = GlowAnimator()
        animator.reactiveBoost = 0.8f
        // Set the internal state to simulate a running animation
        animator.setPrivateField("currentAnimation", GlowAnimation.PULSE)

        animator.stop()

        assertEquals(GlowAnimation.NONE, animator.getPrivateField("currentAnimation"))
        assertEquals(0.0f, animator.reactiveBoost)
        assertNull(animator.getPrivateField("onFrame"))
        assertNull(animator.getPrivateField("timer"))
    }

    @Test
    fun `stop is safe to call when already stopped`() {
        val animator = GlowAnimator()
        // Calling stop when the timer is already null should not throw
        animator.stop()
        assertNull(animator.getPrivateField("timer"))
    }

    // ---- dispose() tests ----

    @Test
    fun `dispose delegates to stop`() {
        val animator = GlowAnimator()
        animator.reactiveBoost = 0.5f
        animator.setPrivateField("currentAnimation", GlowAnimation.BREATHE)

        animator.dispose()

        assertEquals(GlowAnimation.NONE, animator.getPrivateField("currentAnimation"))
        assertEquals(0.0f, animator.reactiveBoost)
        assertNull(animator.getPrivateField("timer"))
    }

    // ---- start() tests ----

    @Test
    fun `start with NONE does not create timer`() {
        val animator = GlowAnimator()
        var callbackInvoked = false

        animator.start(GlowAnimation.NONE) { callbackInvoked = true }

        assertNull(animator.getPrivateField("timer"), "Timer should not be created for NONE")
        assertEquals(GlowAnimation.NONE, animator.getPrivateField("currentAnimation"))
        assertFalse(callbackInvoked, "Callback should not be invoked for NONE animation")
    }

    @Test
    fun `start resets frame counter and reactive boost`() {
        val animator = GlowAnimator()
        animator.frame = 500
        animator.reactiveBoost = 0.8f

        animator.start(GlowAnimation.PULSE) {}

        assertEquals(0L, animator.frame)
        assertEquals(0.0f, animator.reactiveBoost)
        // Clean up timer
        animator.stop()
    }

    @Test
    fun `start sets current animation`() {
        val animator = GlowAnimator()

        animator.start(GlowAnimation.BREATHE) {}

        assertEquals(GlowAnimation.BREATHE, animator.getPrivateField("currentAnimation"))
        // Clean up timer
        animator.stop()
    }

    @Test
    fun `start stops previous animation before starting new one`() {
        val animator = GlowAnimator()

        animator.start(GlowAnimation.PULSE) {}
        val firstTimer = animator.getPrivateField("timer")

        animator.start(GlowAnimation.BREATHE) {}
        val secondTimer = animator.getPrivateField("timer")

        // The timers should be different instances (first was stopped, new one created)
        assertTrue(firstTimer !== secondTimer, "New start should create a new timer")
        assertEquals(GlowAnimation.BREATHE, animator.getPrivateField("currentAnimation"))
        animator.stop()
    }

    @Test
    fun `start creates a repeating timer`() {
        val animator = GlowAnimator()

        animator.start(GlowAnimation.PULSE) {}

        val timer = animator.getPrivateField("timer") as javax.swing.Timer
        assertTrue(timer.isRepeats, "Timer should be set to repeat")
        assertTrue(timer.isRunning, "Timer should be running")
        assertEquals(16, timer.delay, "Timer interval should be 16ms")
        animator.stop()
    }

    // ---- PULSE cycle boundary tests ----

    @Test
    fun `PULSE cycle wraps correctly at 120 frame boundary`() {
        val animator = GlowAnimator()
        // Frame 0 and frame 120 should produce the same alpha (both at cycle start)
        animator.frame = 0
        val alphaAtCycleStart = animator.calculateAlpha(GlowAnimation.PULSE)
        animator.frame = 120
        val alphaAtNextCycleStart = animator.calculateAlpha(GlowAnimation.PULSE)

        assertEquals(
            alphaAtCycleStart,
            alphaAtNextCycleStart,
            "Alpha should repeat at cycle boundary",
        )
    }

    // ---- BREATHE cycle boundary tests ----

    @Test
    fun `BREATHE cycle wraps correctly at 240 frame boundary`() {
        val animator = GlowAnimator()
        animator.frame = 0
        val alphaAtCycleStart = animator.calculateAlpha(GlowAnimation.BREATHE)
        animator.frame = 240
        val alphaAtNextCycleStart = animator.calculateAlpha(GlowAnimation.BREATHE)

        assertEquals(
            alphaAtCycleStart,
            alphaAtNextCycleStart,
            "BREATHE alpha should repeat at 240 frame boundary",
        )
    }

    // ---- REACTIVE precise decay math ----

    @Test
    fun `REACTIVE precise alpha computation with known boost`() {
        val animator = GlowAnimator()
        animator.reactiveBoost = 0.5f

        animator.frame = 0
        val alpha = animator.calculateAlpha(GlowAnimation.REACTIVE)
        // boost after decay: 0.5 * 0.92 = 0.46
        // alpha: 0.4 + 0.46 * 0.6 = 0.4 + 0.276 = 0.676
        assertEquals(0.676f, alpha, 0.001f)
        assertEquals(0.46f, animator.reactiveBoost, 0.001f)
    }

    @Test
    fun `REACTIVE boost just above threshold is preserved`() {
        val animator = GlowAnimator()
        // Set the boost such that after decay it stays above the 0.01 threshold
        // 0.012 * 0.92 = 0.01104 > 0.01
        animator.reactiveBoost = 0.012f

        animator.frame = 0
        animator.calculateAlpha(GlowAnimation.REACTIVE)

        assertTrue(
            animator.reactiveBoost > 0.0f,
            "Boost just above threshold should be preserved (${animator.reactiveBoost})",
        )
    }
}
