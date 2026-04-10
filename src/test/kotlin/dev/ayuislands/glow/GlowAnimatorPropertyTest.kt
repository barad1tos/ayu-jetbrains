package dev.ayuislands.glow

import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlowAnimatorPropertyTest {
    @Test
    fun `NONE always returns 1 for any frame`() =
        runBlocking {
            val animator = GlowAnimator()
            checkAll(Arb.long(0L..10_000L)) { frame ->
                animator.frame = frame
                assertEquals(
                    1.0f,
                    animator.calculateAlpha(GlowAnimation.NONE),
                    "NONE must return 1.0 at frame $frame",
                )
            }
        }

    @Test
    fun `PULSE alpha is always within bounds for any frame`() =
        runBlocking {
            val animator = GlowAnimator()
            checkAll(Arb.long(0L..50_000L)) { frame ->
                animator.frame = frame
                val alpha = animator.calculateAlpha(GlowAnimation.PULSE)
                assertTrue(
                    alpha >= 0.3f,
                    "PULSE alpha must be >= 0.3 at frame $frame, got $alpha",
                )
                assertTrue(
                    alpha <= 1.0f,
                    "PULSE alpha must be <= 1.0 at frame $frame, got $alpha",
                )
            }
        }

    @Test
    fun `BREATHE alpha is always within bounds for any frame`() =
        runBlocking {
            val animator = GlowAnimator()
            checkAll(Arb.long(0L..50_000L)) { frame ->
                animator.frame = frame
                val alpha = animator.calculateAlpha(GlowAnimation.BREATHE)
                assertTrue(
                    alpha >= 0.2f,
                    "BREATHE alpha must be >= 0.2 at frame $frame, got $alpha",
                )
                assertTrue(
                    alpha <= 1.0f,
                    "BREATHE alpha must be <= 1.0 at frame $frame, got $alpha",
                )
            }
        }

    @Test
    fun `PULSE is periodic with cycle length 120`() =
        runBlocking {
            val animator = GlowAnimator()
            checkAll(Arb.long(0L..10_000L)) { frame ->
                animator.frame = frame
                val alpha1 = animator.calculateAlpha(GlowAnimation.PULSE)
                animator.frame = frame + 120
                val alpha2 = animator.calculateAlpha(GlowAnimation.PULSE)
                assertEquals(
                    alpha1,
                    alpha2,
                    "PULSE must be periodic at frame $frame vs ${frame + 120}",
                )
            }
        }

    @Test
    fun `BREATHE is periodic with cycle length 240`() =
        runBlocking {
            val animator = GlowAnimator()
            checkAll(Arb.long(0L..10_000L)) { frame ->
                animator.frame = frame
                val alpha1 = animator.calculateAlpha(GlowAnimation.BREATHE)
                animator.frame = frame + 240
                val alpha2 = animator.calculateAlpha(GlowAnimation.BREATHE)
                assertEquals(
                    alpha1,
                    alpha2,
                    "BREATHE must be periodic at frame $frame vs ${frame + 240}",
                )
            }
        }

    @Test
    fun `REACTIVE alpha is always in 0 to 1 range regardless of boost`() =
        runBlocking {
            val animator = GlowAnimator()
            checkAll(Arb.long(0L..1_000L)) { frame ->
                animator.frame = frame
                animator.reactiveBoost = 5.0f
                val alpha = animator.calculateAlpha(GlowAnimation.REACTIVE)
                assertTrue(
                    alpha in 0.0f..1.0f,
                    "REACTIVE alpha must be in [0,1] at frame $frame with high boost, got $alpha",
                )
            }
        }

    @Test
    fun `all animation types produce alpha in 0 to 1 for any frame`() =
        runBlocking {
            checkAll(Arb.long(0L..10_000L)) { frame ->
                for (animation in GlowAnimation.entries) {
                    val animator = GlowAnimator()
                    animator.frame = frame
                    val alpha = animator.calculateAlpha(animation)
                    assertTrue(
                        alpha in 0.0f..1.0f,
                        "${animation.name} alpha out of [0,1] at frame $frame: $alpha",
                    )
                }
            }
        }
}
