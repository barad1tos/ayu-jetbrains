package dev.ayuislands.glow

import com.intellij.ui.ColorUtil
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Area
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlowRendererTest {
    private fun rendererWithStyle(
        style: GlowStyle,
        baseAlpha: Int = 200,
    ): GlowRenderer {
        val renderer = GlowRenderer()
        renderer.cachedStyle = style
        renderer.cachedBaseAlpha = baseAlpha
        return renderer
    }

    @Test
    fun `SOFT alpha decreases linearly from edge to center`() {
        val renderer = rendererWithStyle(GlowStyle.SOFT, baseAlpha = 255)
        val alphaAtEdge = renderer.computeAlpha(0.0f)
        val alphaAtMid = renderer.computeAlpha(0.5f)
        val alphaAtCenter = renderer.computeAlpha(1.0f)

        assertTrue(alphaAtEdge > alphaAtMid, "Edge alpha ($alphaAtEdge) should exceed mid ($alphaAtMid)")
        assertTrue(alphaAtMid > alphaAtCenter, "Mid alpha ($alphaAtMid) should exceed center ($alphaAtCenter)")
        assertEquals(0, alphaAtCenter, "Alpha at progress=1.0 should be 0")
    }

    @Test
    fun `SHARP_NEON has bright core then rapid falloff`() {
        val renderer = rendererWithStyle(GlowStyle.SHARP_NEON, baseAlpha = 200)

        val alphaInCore = renderer.computeAlpha(0.1f)
        val alphaAtCoreEdge = renderer.computeAlpha(0.29f)
        val alphaInBloom = renderer.computeAlpha(0.5f)
        val alphaAtEnd = renderer.computeAlpha(1.0f)

        assertEquals(200, alphaInCore, "Core alpha should equal baseAlpha")
        assertEquals(200, alphaAtCoreEdge, "Core edge alpha should equal baseAlpha")
        assertTrue(alphaInBloom < alphaInCore, "Bloom alpha ($alphaInBloom) should be less than core ($alphaInCore)")
        assertEquals(0, alphaAtEnd, "Alpha at end should be 0")
    }

    @Test
    fun `GRADIENT alpha decreases with half divisor`() {
        val renderer = rendererWithStyle(GlowStyle.GRADIENT, baseAlpha = 200)
        val alphaAtEdge = renderer.computeAlpha(0.0f)
        val alphaAtMid = renderer.computeAlpha(0.5f)
        val alphaAtEnd = renderer.computeAlpha(1.0f)

        assertTrue(alphaAtEdge > alphaAtMid)
        assertEquals(0, alphaAtEnd)

        val softRenderer = rendererWithStyle(GlowStyle.SOFT, baseAlpha = 200)
        val softAtEdge = softRenderer.computeAlpha(0.0f)
        assertTrue(
            alphaAtEdge > softAtEdge,
            "Gradient edge ($alphaAtEdge) should be brighter than Soft edge ($softAtEdge) due to smaller divisor",
        )
    }

    @Test
    fun `computeAlpha clamps to 0-255 range`() {
        val renderer = rendererWithStyle(GlowStyle.SOFT, baseAlpha = 255)
        for (progress in listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)) {
            val alpha = renderer.computeAlpha(progress)
            assertTrue(alpha in 0..255, "Alpha $alpha at progress $progress should be in 0..255")
        }
    }

    @Test
    fun `ensureCache invalidates frame cache on style change`() {
        val renderer = GlowRenderer()
        renderer.ensureCache(Color.RED, GlowStyle.SOFT, 40, 12)

        val image = BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB)
        val g2 = image.createGraphics()
        renderer.paintGlow(g2, Rectangle(0, 0, 100, 100), 12, 8)
        g2.dispose()

        renderer.ensureCache(Color.BLUE, GlowStyle.SHARP_NEON, 85, 20)

        renderer.invalidateCache()
        // After invalidation, accessing ensureCache with new params should work without error
        renderer.ensureCache(Color.GREEN, GlowStyle.GRADIENT, 50, 12)
    }

    @Test
    fun `ensureCache boosts alpha for light theme`() {
        val renderer = GlowRenderer()
        val uiDefaults = javax.swing.UIManager.getDefaults()
        val original = uiDefaults.getColor("Panel.background")
        try {
            uiDefaults["Panel.background"] = Color(240, 240, 240)
            renderer.ensureCache(
                Color.RED,
                GlowStyle.SOFT,
                40,
                12,
            )
            val lightAlpha = renderer.cachedBaseAlpha

            uiDefaults["Panel.background"] = Color(30, 30, 30)
            renderer.ensureCache(
                Color.RED,
                GlowStyle.SOFT,
                40,
                12,
            )
            val darkAlpha = renderer.cachedBaseAlpha

            assertTrue(
                lightAlpha > darkAlpha,
                "Light theme alpha ($lightAlpha) should " +
                    "be higher than dark ($darkAlpha)",
            )
        } finally {
            uiDefaults["Panel.background"] = original
        }
    }

    @Test
    fun `large frame cache stores border pixels instead of transparent interior`() {
        val width = 1939
        val height = 1277
        val renderer = GlowRenderer()
        renderer.ensureCache(Color.CYAN, GlowStyle.SHARP_NEON, 50, 4)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()

        renderer.paintGlow(graphics, Rectangle(0, 0, width, height), glowWidth = 4, arcWidth = 16)
        graphics.dispose()

        assertTrue(renderer.cachedPixelCount > 0)
        assertTrue(renderer.cachedPixelCount < width.toLong() * height / 10)
    }

    @Test
    fun `border cache preserves full frame pixels across geometry and alpha`() {
        val cases =
            listOf(
                RenderCase(width = 80, height = 80, arcWidth = 0, glowWidth = 4),
                RenderCase(width = 800, height = 600, arcWidth = 100, glowWidth = 4),
                RenderCase(width = 31, height = 29, arcWidth = 40, glowWidth = 24),
                RenderCase(width = 800, height = 600, arcWidth = 16, glowWidth = 4, edgesOnly = true),
            )

        cases.forEach { case ->
            listOf(0.08f, 0.5f, 1f).forEach { alpha ->
                val renderer = GlowRenderer()
                val accent = Color(0x5CCFE6)
                renderer.ensureCache(accent, GlowStyle.SHARP_NEON, 50, case.glowWidth)
                val expected = renderLegacyFrame(renderer, accent, case, alpha)
                val actual = BufferedImage(case.width, case.height, BufferedImage.TYPE_INT_ARGB)
                val graphics = actual.createGraphics()
                graphics.composite = AlphaComposite.SrcOver.derive(alpha)
                renderer.paintGlow(
                    graphics,
                    Rectangle(0, 0, case.width, case.height),
                    case.glowWidth,
                    case.arcWidth,
                    case.edgesOnly,
                )
                graphics.dispose()

                assertContentEquals(
                    pixels(expected),
                    pixels(actual),
                    "render changed for $case at alpha=$alpha",
                )
            }
        }
    }

    @Test
    fun `ensureCache is idempotent for same params`() {
        val renderer = GlowRenderer()
        renderer.ensureCache(Color.RED, GlowStyle.SOFT, 40, 12)
        val alpha1 = renderer.cachedBaseAlpha
        renderer.ensureCache(Color.RED, GlowStyle.SOFT, 40, 12)
        val alpha2 = renderer.cachedBaseAlpha
        assertEquals(alpha1, alpha2)
    }

    @Test
    fun `paintGlow does not crash on repeated calls`() {
        val renderer = GlowRenderer()
        renderer.ensureCache(Color.CYAN, GlowStyle.SOFT, 50, 8)

        val image = BufferedImage(80, 80, BufferedImage.TYPE_INT_ARGB)
        val g2 = image.createGraphics()
        renderer.paintGlow(g2, Rectangle(0, 0, 80, 80), 8, 6)
        renderer.paintGlow(g2, Rectangle(0, 0, 80, 80), 8, 6)
        g2.dispose()
        // Smoke test: verifies no crash on repeated paint
    }

    @Test
    fun `paintGlow skips rendering for zero-size bounds`() {
        val renderer = GlowRenderer()
        renderer.ensureCache(Color.RED, GlowStyle.SOFT, 40, 12)

        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val g2 = image.createGraphics()

        // Should not throw for zero/negative bounds
        renderer.paintGlow(g2, Rectangle(0, 0, 0, 0), 12, 8)
        renderer.paintGlow(g2, Rectangle(0, 0, -1, -1), 12, 8)
        g2.dispose()
    }

    private fun renderLegacyFrame(
        renderer: GlowRenderer,
        accent: Color,
        case: RenderCase,
        alpha: Float,
    ): BufferedImage {
        val frame = BufferedImage(case.width, case.height, BufferedImage.TYPE_INT_ARGB)
        val frameGraphics = frame.createGraphics()
        try {
            frameGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            if (case.edgesOnly) {
                val columns = case.glowWidth.coerceAtMost((case.width + 1) / 2)
                for (index in 0 until columns) {
                    val layerAlpha = renderer.computeAlpha(index.toFloat() / case.glowWidth)
                    if (layerAlpha <= 0) continue

                    frameGraphics.color = ColorUtil.toAlpha(accent, layerAlpha)
                    frameGraphics.fillRect(index, 0, 1, case.height)
                    val rightX = case.width - 1 - index
                    if (rightX != index) frameGraphics.fillRect(rightX, 0, 1, case.height)
                }
            } else {
                paintLegacyRings(frameGraphics, renderer, accent, case)
            }
        } finally {
            frameGraphics.dispose()
        }

        val target = BufferedImage(case.width, case.height, BufferedImage.TYPE_INT_ARGB)
        val targetGraphics = target.createGraphics()
        targetGraphics.composite = AlphaComposite.SrcOver.derive(alpha)
        targetGraphics.drawImage(frame, 0, 0, null)
        targetGraphics.dispose()
        return target
    }

    private fun paintLegacyRings(
        graphics: java.awt.Graphics2D,
        renderer: GlowRenderer,
        accent: Color,
        case: RenderCase,
    ) {
        for (index in 0 until case.glowWidth) {
            val layerAlpha = renderer.computeAlpha(index.toFloat() / case.glowWidth)
            if (layerAlpha <= 0) continue

            graphics.color = ColorUtil.toAlpha(accent, layerAlpha)
            val inset = index.toDouble()
            val outerWidth = (case.width - 2.0 * inset).coerceAtLeast(0.0)
            val outerHeight = (case.height - 2.0 * inset).coerceAtLeast(0.0)
            if (outerWidth <= 0 || outerHeight <= 0) break

            val outerArc = if (case.arcWidth > 0) (case.arcWidth - 2.0 * index).coerceAtLeast(0.0) else 0.0
            val outer = RoundRectangle2D.Double(inset, inset, outerWidth, outerHeight, outerArc, outerArc)
            val innerInset = inset + 1.0
            val innerWidth = (case.width - 2.0 * innerInset).coerceAtLeast(0.0)
            val innerHeight = (case.height - 2.0 * innerInset).coerceAtLeast(0.0)
            if (innerWidth > 0 && innerHeight > 0) {
                val innerArc =
                    if (case.arcWidth > 0) {
                        (case.arcWidth - 2.0 * (index + 1)).coerceAtLeast(0.0)
                    } else {
                        0.0
                    }
                val ring = Area(outer)
                ring.subtract(
                    Area(
                        RoundRectangle2D.Double(
                            innerInset,
                            innerInset,
                            innerWidth,
                            innerHeight,
                            innerArc,
                            innerArc,
                        ),
                    ),
                )
                graphics.fill(ring)
            } else {
                graphics.fill(outer)
            }
        }
    }

    private fun pixels(image: BufferedImage): IntArray =
        image.getRGB(0, 0, image.width, image.height, null, 0, image.width)

    private data class RenderCase(
        val width: Int,
        val height: Int,
        val arcWidth: Int,
        val glowWidth: Int,
        val edgesOnly: Boolean = false,
    )
}
