package dev.ayuislands.glow

import io.kotest.property.Arb
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlowRendererPropertyTest {
    @Test
    fun `SOFT computeAlpha is always in 0 to 255 range`(): Unit =
        runBlocking {
            checkAll(
                Arb.float(min = 0f, max = 1f),
                Arb.int(0..255),
            ) { progress, baseAlpha ->
                val renderer = GlowRenderer()
                renderer.cachedStyle = GlowStyle.SOFT
                renderer.cachedBaseAlpha = baseAlpha
                val alpha = renderer.computeAlpha(progress)
                assertTrue(
                    alpha in 0..255,
                    "SOFT alpha must be in [0,255]: got $alpha (progress=$progress, base=$baseAlpha)",
                )
            }
        }

    @Test
    fun `SHARP_NEON computeAlpha is always in 0 to 255 range`(): Unit =
        runBlocking {
            checkAll(
                Arb.float(min = 0f, max = 1f),
                Arb.int(0..255),
            ) { progress, baseAlpha ->
                val renderer = GlowRenderer()
                renderer.cachedStyle = GlowStyle.SHARP_NEON
                renderer.cachedBaseAlpha = baseAlpha
                val alpha = renderer.computeAlpha(progress)
                assertTrue(
                    alpha in 0..255,
                    "SHARP_NEON alpha must be in [0,255]: got $alpha (progress=$progress, base=$baseAlpha)",
                )
            }
        }

    @Test
    fun `GRADIENT computeAlpha is always in 0 to 255 range`(): Unit =
        runBlocking {
            checkAll(
                Arb.float(min = 0f, max = 1f),
                Arb.int(0..255),
            ) { progress, baseAlpha ->
                val renderer = GlowRenderer()
                renderer.cachedStyle = GlowStyle.GRADIENT
                renderer.cachedBaseAlpha = baseAlpha
                val alpha = renderer.computeAlpha(progress)
                assertTrue(
                    alpha in 0..255,
                    "GRADIENT alpha must be in [0,255]: got $alpha (progress=$progress, base=$baseAlpha)",
                )
            }
        }

    @Test
    fun `computeAlpha at progress 0 returns maximum alpha for the style`(): Unit =
        runBlocking {
            checkAll(Arb.int(4..255)) { baseAlpha ->
                for (style in GlowStyle.entries) {
                    val renderer = GlowRenderer()
                    renderer.cachedStyle = style
                    renderer.cachedBaseAlpha = baseAlpha
                    val alpha = renderer.computeAlpha(0f)
                    assertTrue(
                        alpha > 0,
                        "${style.name} computeAlpha(0) must be > 0 when baseAlpha=$baseAlpha, got $alpha",
                    )
                }
            }
        }

    @Test
    fun `SOFT computeAlpha decreases as progress increases`(): Unit =
        runBlocking {
            checkAll(Arb.int(10..255)) { baseAlpha ->
                val renderer = GlowRenderer()
                renderer.cachedStyle = GlowStyle.SOFT
                renderer.cachedBaseAlpha = baseAlpha
                val alphaAtStart = renderer.computeAlpha(0f)
                val alphaAtEnd = renderer.computeAlpha(1f)
                assertTrue(
                    alphaAtStart >= alphaAtEnd,
                    "SOFT alpha must decrease: start=$alphaAtStart, end=$alphaAtEnd (base=$baseAlpha)",
                )
            }
        }

    @Test
    fun `GRADIENT computeAlpha decreases as progress increases`(): Unit =
        runBlocking {
            checkAll(Arb.int(10..255)) { baseAlpha ->
                val renderer = GlowRenderer()
                renderer.cachedStyle = GlowStyle.GRADIENT
                renderer.cachedBaseAlpha = baseAlpha
                val alphaAtStart = renderer.computeAlpha(0f)
                val alphaAtEnd = renderer.computeAlpha(1f)
                assertTrue(
                    alphaAtStart >= alphaAtEnd,
                    "GRADIENT alpha must decrease: start=$alphaAtStart, end=$alphaAtEnd (base=$baseAlpha)",
                )
            }
        }

    @Test
    fun `SHARP_NEON has constant alpha in core region`(): Unit =
        runBlocking {
            checkAll(Arb.int(10..255)) { baseAlpha ->
                val renderer = GlowRenderer()
                renderer.cachedStyle = GlowStyle.SHARP_NEON
                renderer.cachedBaseAlpha = baseAlpha
                val alphaAt0 = renderer.computeAlpha(0f)
                val alphaAt20Pct = renderer.computeAlpha(0.2f)
                assertEquals(
                    alphaAt0,
                    alphaAt20Pct,
                    "SHARP_NEON core must be constant: 0%=$alphaAt0, 20%=$alphaAt20Pct (base=$baseAlpha)",
                )
            }
        }

    @Test
    fun `computeAlpha with zero base alpha returns zero for all styles`() {
        for (style in GlowStyle.entries) {
            val renderer = GlowRenderer()
            renderer.cachedStyle = style
            renderer.cachedBaseAlpha = 0
            val alpha = renderer.computeAlpha(0.5f)
            assertEquals(
                0,
                alpha,
                "${style.name} with baseAlpha=0 must return 0, got $alpha",
            )
        }
    }
}
