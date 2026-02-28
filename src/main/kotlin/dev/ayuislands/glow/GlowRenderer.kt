package dev.ayuislands.glow

import com.intellij.openapi.diagnostic.logger
import java.awt.Color
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage

class GlowRenderer {

    private val log = logger<GlowRenderer>()

    private var cachedColor: Color? = null
    private var cachedWidth: Int = 0
    private var topStrip: BufferedImage? = null
    private var bottomStrip: BufferedImage? = null
    private var leftStrip: BufferedImage? = null
    private var rightStrip: BufferedImage? = null

    companion object {
        const val DEFAULT_GLOW_WIDTH = 12
        const val GLOW_START_ALPHA = 80
    }

    fun ensureCache(accentColor: Color, glowWidth: Int = DEFAULT_GLOW_WIDTH) {
        if (accentColor == cachedColor && glowWidth == cachedWidth && topStrip != null) {
            return
        }

        val startColor = Color(accentColor.red, accentColor.green, accentColor.blue, GLOW_START_ALPHA)
        val endColor = Color(accentColor.red, accentColor.green, accentColor.blue, 0)

        // Top strip: 1px wide, glowWidth tall, gradient top-to-bottom (border at top, fades inward)
        topStrip = createStrip(1, glowWidth, startColor, endColor, vertical = true)

        // Bottom strip: 1px wide, glowWidth tall, gradient bottom-to-top (border at bottom)
        bottomStrip = createStrip(1, glowWidth, endColor, startColor, vertical = true)

        // Left strip: glowWidth wide, 1px tall, gradient left-to-right (border at left)
        leftStrip = createStrip(glowWidth, 1, startColor, endColor, vertical = false)

        // Right strip: glowWidth wide, 1px tall, gradient right-to-left (border at right)
        rightStrip = createStrip(glowWidth, 1, endColor, startColor, vertical = false)

        cachedColor = accentColor
        cachedWidth = glowWidth
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
        cachedColor = null
        topStrip = null
        bottomStrip = null
        leftStrip = null
        rightStrip = null
    }
}
