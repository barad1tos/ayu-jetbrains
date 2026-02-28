package dev.ayuislands.glow

import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.ColorUtil
import java.awt.Color
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.UIManager

class GlowRenderer {

    private val log = logger<GlowRenderer>()

    private data class CacheKey(
        val style: GlowStyle,
        val color: Color,
        val intensity: Int,
        val width: Int,
    )

    private var cacheKey: CacheKey? = null
    private var topStrip: BufferedImage? = null
    private var bottomStrip: BufferedImage? = null
    private var leftStrip: BufferedImage? = null
    private var rightStrip: BufferedImage? = null

    companion object {
        const val DEFAULT_GLOW_WIDTH = 12
        const val GLOW_START_ALPHA = 80
    }

    fun ensureCache(
        accentColor: Color,
        style: GlowStyle = GlowStyle.SOFT,
        intensity: Int = 40,
        glowWidth: Int = DEFAULT_GLOW_WIDTH,
    ) {
        val key = CacheKey(style, accentColor, intensity, glowWidth)
        if (key == cacheKey && topStrip != null) {
            return
        }

        topStrip = createStyledStrip(1, glowWidth, accentColor, style, intensity, vertical = true, reverse = false)
        bottomStrip = createStyledStrip(1, glowWidth, accentColor, style, intensity, vertical = true, reverse = true)
        leftStrip = createStyledStrip(glowWidth, 1, accentColor, style, intensity, vertical = false, reverse = false)
        rightStrip = createStyledStrip(glowWidth, 1, accentColor, style, intensity, vertical = false, reverse = true)

        cacheKey = key
    }

    private fun createStyledStrip(
        width: Int,
        height: Int,
        accentColor: Color,
        style: GlowStyle,
        intensity: Int,
        vertical: Boolean,
        reverse: Boolean,
    ): BufferedImage {
        // Scale alpha by intensity (0-100) -> 0-255 range
        val baseAlpha = (intensity / 100.0 * 255).toInt().coerceIn(0, 255)

        // Light variant detection: boost alpha for visibility on light backgrounds
        val panelBackground = UIManager.getColor("Panel.background")
        val isLight = panelBackground != null && !ColorUtil.isDark(panelBackground)
        val scaledAlpha = if (isLight) (baseAlpha * 1.5).toInt().coerceIn(0, 255) else baseAlpha

        return when (style) {
            GlowStyle.SOFT -> createSoftStrip(width, height, accentColor, scaledAlpha, vertical, reverse)
            GlowStyle.SHARP_NEON -> createSharpNeonStrip(width, height, accentColor, scaledAlpha, vertical, reverse)
            GlowStyle.GRADIENT -> createGradientStrip(width, height, accentColor, scaledAlpha, vertical, reverse)
        }
    }

    private fun createSoftStrip(
        width: Int,
        height: Int,
        accentColor: Color,
        alpha: Int,
        vertical: Boolean,
        reverse: Boolean,
    ): BufferedImage {
        val startColor = Color(accentColor.red, accentColor.green, accentColor.blue, alpha / 3)
        val endColor = Color(accentColor.red, accentColor.green, accentColor.blue, 0)

        val from = if (reverse) endColor else startColor
        val to = if (reverse) startColor else endColor
        return createStrip(width, height, from, to, vertical)
    }

    private fun createSharpNeonStrip(
        width: Int,
        height: Int,
        accentColor: Color,
        alpha: Int,
        vertical: Boolean,
        reverse: Boolean,
    ): BufferedImage {
        val strip = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = strip.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

            val totalSize = if (vertical) height else width
            val coreSize = (totalSize * 0.3).toInt().coerceAtLeast(1)
            val bloomSize = totalSize - coreSize

            val brightColor = Color(accentColor.red, accentColor.green, accentColor.blue, alpha)
            val midColor = Color(accentColor.red, accentColor.green, accentColor.blue, (alpha * 0.6).toInt())
            val fadeColor = Color(accentColor.red, accentColor.green, accentColor.blue, 0)

            if (reverse) {
                // Bloom region (far end to mid-point): fade -> mid
                if (bloomSize > 0) {
                    val bloomPaint = if (vertical) {
                        GradientPaint(0f, 0f, fadeColor, 0f, bloomSize.toFloat(), midColor)
                    } else {
                        GradientPaint(0f, 0f, fadeColor, bloomSize.toFloat(), 0f, midColor)
                    }
                    graphics.paint = bloomPaint
                    graphics.fillRect(0, 0, if (vertical) width else bloomSize, if (vertical) bloomSize else height)
                }

                // Core region (mid-point to border): mid -> bright
                val coreStart = bloomSize
                val corePaint = if (vertical) {
                    GradientPaint(0f, coreStart.toFloat(), midColor, 0f, totalSize.toFloat(), brightColor)
                } else {
                    GradientPaint(coreStart.toFloat(), 0f, midColor, totalSize.toFloat(), 0f, brightColor)
                }
                graphics.paint = corePaint
                if (vertical) {
                    graphics.fillRect(0, coreStart, width, coreSize)
                } else {
                    graphics.fillRect(coreStart, 0, coreSize, height)
                }
            } else {
                // Core region (border to 30%): bright -> mid
                val corePaint = if (vertical) {
                    GradientPaint(0f, 0f, brightColor, 0f, coreSize.toFloat(), midColor)
                } else {
                    GradientPaint(0f, 0f, brightColor, coreSize.toFloat(), 0f, midColor)
                }
                graphics.paint = corePaint
                graphics.fillRect(0, 0, if (vertical) width else coreSize, if (vertical) coreSize else height)

                // Bloom region (30% to end): mid -> fade
                if (bloomSize > 0) {
                    val bloomPaint = if (vertical) {
                        GradientPaint(0f, coreSize.toFloat(), midColor, 0f, totalSize.toFloat(), fadeColor)
                    } else {
                        GradientPaint(coreSize.toFloat(), 0f, midColor, totalSize.toFloat(), 0f, fadeColor)
                    }
                    graphics.paint = bloomPaint
                    if (vertical) {
                        graphics.fillRect(0, coreSize, width, bloomSize)
                    } else {
                        graphics.fillRect(coreSize, 0, bloomSize, height)
                    }
                }
            }
        } finally {
            graphics.dispose()
        }
        return strip
    }

    private fun createGradientStrip(
        width: Int,
        height: Int,
        accentColor: Color,
        alpha: Int,
        vertical: Boolean,
        reverse: Boolean,
    ): BufferedImage {
        val panelBackground = UIManager.getColor("Panel.background") ?: Color(0x1F, 0x24, 0x30)
        val blended = ColorUtil.mix(accentColor, panelBackground, 0.7)

        val startColor = Color(accentColor.red, accentColor.green, accentColor.blue, alpha / 2)
        val endColor = Color(blended.red, blended.green, blended.blue, 0)

        val from = if (reverse) endColor else startColor
        val to = if (reverse) startColor else endColor
        return createStrip(width, height, from, to, vertical)
    }

    private fun createStrip(
        width: Int,
        height: Int,
        startColor: Color,
        endColor: Color,
        vertical: Boolean,
    ): BufferedImage {
        val strip = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = strip.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            val paint = if (vertical) {
                GradientPaint(0f, 0f, startColor, 0f, height.toFloat(), endColor)
            } else {
                GradientPaint(0f, 0f, startColor, width.toFloat(), 0f, endColor)
            }
            graphics.paint = paint
            graphics.fillRect(0, 0, width, height)
        } finally {
            graphics.dispose()
        }
        return strip
    }

    fun paintGlow(graphics: Graphics2D, bounds: Rectangle, glowWidth: Int = DEFAULT_GLOW_WIDTH) {
        val startNanos = System.nanoTime()

        val top = topStrip ?: return
        val bottom = bottomStrip ?: return
        val left = leftStrip ?: return
        val right = rightStrip ?: return

        // Top edge: stretch 1px strip across full width at top of bounds
        graphics.drawImage(
            top,
            bounds.x, bounds.y,
            bounds.width, glowWidth, null,
        )

        // Bottom edge: stretch bottom strip across full width at bottom of bounds
        graphics.drawImage(
            bottom,
            bounds.x, bounds.y + bounds.height - glowWidth,
            bounds.width, glowWidth, null,
        )

        // Left edge: stretch left strip across height excluding corners
        graphics.drawImage(
            left,
            bounds.x, bounds.y + glowWidth,
            glowWidth, bounds.height - 2 * glowWidth, null,
        )

        // Right edge: stretch right strip excluding corners
        graphics.drawImage(
            right,
            bounds.x + bounds.width - glowWidth, bounds.y + glowWidth,
            glowWidth, bounds.height - 2 * glowWidth, null,
        )

        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0
        if (elapsedMs > 16.0) {
            log.warn("Glow paint took %.2fms (target: <16ms)".format(elapsedMs))
        }
    }

    fun invalidateCache() {
        cacheKey = null
        topStrip = null
        bottomStrip = null
        leftStrip = null
        rightStrip = null
    }
}
