package dev.ayuislands.glow

import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.ColorUtil
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Area
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.UIManager

class GlowRenderer {
    private val log = logger<GlowRenderer>()

    companion object {
        const val DEFAULT_GLOW_WIDTH = 12

        private const val MAX_ALPHA = 255
        private const val PERCENTAGE_DIVISOR = 100.0
        private const val LIGHT_THEME_ALPHA_MULTIPLIER = 1.5
        private const val SOFT_ALPHA_DIVISOR = 3.0f
        private const val GRADIENT_ALPHA_DIVISOR = 2.0f
        private const val NEON_CORE_THRESHOLD = 0.3f
        private const val NEON_BLOOM_DIVISOR = 0.7f
        private const val NEON_BLOOM_INTENSITY = 0.6f
        private const val FRAME_RENDER_BUDGET_MS = 16.0
        private const val NANOS_PER_MS = 1_000_000.0
    }

    // Style cache (lightweight, recomputed on style/color change)
    private data class StyleKey(
        val style: GlowStyle,
        val color: Color,
        val intensity: Int,
        val glowWidth: Int,
    )

    private var styleKey: StyleKey? = null
    private var cachedColor: Color = Color.BLACK
    internal var cachedStyle: GlowStyle = GlowStyle.SOFT
    internal var cachedBaseAlpha: Int = 0

    // Frame image cache (expensive, keyed on size + style)
    private data class FrameKey(
        val width: Int,
        val height: Int,
        val arcWidth: Int,
        val style: GlowStyle,
        val color: Color,
        val baseAlpha: Int,
        val glowWidth: Int,
    )

    private var frameKey: FrameKey? = null
    private var cachedFrame: BufferedImage? = null

    fun ensureCache(
        accentColor: Color,
        style: GlowStyle = GlowStyle.SOFT,
        intensity: Int = 40,
        glowWidth: Int = DEFAULT_GLOW_WIDTH,
    ) {
        val key = StyleKey(style, accentColor, intensity, glowWidth)
        if (key == styleKey) return

        val baseAlpha = (intensity / PERCENTAGE_DIVISOR * MAX_ALPHA).toInt().coerceIn(0, MAX_ALPHA)

        val panelBackground = UIManager.getColor("Panel.background")
        val isLight = panelBackground != null && !ColorUtil.isDark(panelBackground)
        cachedBaseAlpha =
            if (isLight) {
                (baseAlpha * LIGHT_THEME_ALPHA_MULTIPLIER).toInt().coerceIn(0, MAX_ALPHA)
            } else {
                baseAlpha
            }
        cachedColor = accentColor
        cachedStyle = style
        styleKey = key

        // Style changed — invalidate frame cache
        cachedFrame = null
        frameKey = null
    }

    /**
     * Paints glow as concentric rounded rectangles from edge inward.
     * Renders to a cached BufferedImage for performance (~0.5ms after first render).
     */
    fun paintGlow(
        graphics: Graphics2D,
        bounds: Rectangle,
        glowWidth: Int = DEFAULT_GLOW_WIDTH,
        arcWidth: Int = 0,
    ) {
        if (bounds.width <= 0 || bounds.height <= 0) return

        val fKey =
            FrameKey(
                bounds.width,
                bounds.height,
                arcWidth,
                cachedStyle,
                cachedColor,
                cachedBaseAlpha,
                glowWidth,
            )

        if (fKey != frameKey || cachedFrame == null) {
            val startNanos = System.nanoTime()
            cachedFrame = renderFrame(bounds.width, bounds.height, arcWidth, glowWidth)
            frameKey = fKey
            val elapsedMs = (System.nanoTime() - startNanos) / NANOS_PER_MS
            if (elapsedMs > FRAME_RENDER_BUDGET_MS) {
                log.warn("Glow frame render took %.2fms (target: <16ms) — cached for reuse".format(elapsedMs))
            }
        }

        graphics.drawImage(cachedFrame, bounds.x, bounds.y, null)
    }

    private fun renderFrame(
        width: Int,
        height: Int,
        arcWidth: Int,
        glowWidth: Int,
    ): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g2 = image.createGraphics()
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            for (i in 0 until glowWidth) {
                val progress = i.toFloat() / glowWidth.toFloat()
                val alpha = computeAlpha(progress)
                if (alpha <= 0) continue

                g2.color = Color(cachedColor.red, cachedColor.green, cachedColor.blue, alpha)

                val inset = i.toDouble()
                val outerW = (width - 2.0 * inset).coerceAtLeast(0.0)
                val outerH = (height - 2.0 * inset).coerceAtLeast(0.0)
                if (outerW <= 0 || outerH <= 0) break

                val outerArc = if (arcWidth > 0) (arcWidth.toDouble() - 2.0 * i).coerceAtLeast(0.0) else 0.0
                val outer = RoundRectangle2D.Double(inset, inset, outerW, outerH, outerArc, outerArc)

                val nextInset = inset + 1.0
                val innerW = (width - 2.0 * nextInset).coerceAtLeast(0.0)
                val innerH = (height - 2.0 * nextInset).coerceAtLeast(0.0)

                if (innerW > 0 && innerH > 0) {
                    val innerArc = if (arcWidth > 0) (arcWidth.toDouble() - 2.0 * (i + 1)).coerceAtLeast(0.0) else 0.0
                    val inner = RoundRectangle2D.Double(nextInset, nextInset, innerW, innerH, innerArc, innerArc)
                    val ring = Area(outer)
                    ring.subtract(Area(inner))
                    g2.fill(ring)
                } else {
                    g2.fill(outer)
                }
            }
        } finally {
            g2.dispose()
        }
        return image
    }

    internal fun computeAlpha(progress: Float): Int =
        when (cachedStyle) {
            GlowStyle.SOFT -> {
                ((1.0f - progress) * cachedBaseAlpha / SOFT_ALPHA_DIVISOR).toInt().coerceIn(0, MAX_ALPHA)
            }
            GlowStyle.SHARP_NEON -> {
                if (progress < NEON_CORE_THRESHOLD) {
                    cachedBaseAlpha
                } else {
                    val bloomProgress = (progress - NEON_CORE_THRESHOLD) / NEON_BLOOM_DIVISOR
                    (cachedBaseAlpha * NEON_BLOOM_INTENSITY * (1.0f - bloomProgress)).toInt().coerceIn(0, MAX_ALPHA)
                }
            }
            GlowStyle.GRADIENT -> {
                ((1.0f - progress) * cachedBaseAlpha / GRADIENT_ALPHA_DIVISOR).toInt().coerceIn(0, MAX_ALPHA)
            }
        }

    fun invalidateCache() {
        styleKey = null
        cachedFrame = null
        frameKey = null
    }
}
