package dev.ayuislands.glow

import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Component
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Shape
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
        private const val CACHE_EDGE_PADDING = 2
    }

    // Style cache (lightweight, recomputed on style/color change)
    private data class StyleKey(
        val style: GlowStyle,
        val color: Color,
        val intensity: Int,
        val glowWidth: Int,
        val isLightTheme: Boolean,
    )

    private var styleKey: StyleKey? = null
    private var cachedColor: Color = JBColor.BLACK
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
        val edgesOnly: Boolean,
    )

    private data class GlowLayer(
        val shape: Shape,
        val color: Color,
    )

    private data class FrameSlice(
        val image: BufferedImage,
        val x: Int,
        val y: Int,
    )

    private data class FrameCache(
        val slices: List<FrameSlice>,
    ) {
        val pixelCount: Long = slices.sumOf { it.image.width.toLong() * it.image.height }

        fun paint(
            graphics: Graphics2D,
            x: Int,
            y: Int,
        ) {
            slices.forEach { slice ->
                graphics.drawImage(slice.image, x + slice.x, y + slice.y, null)
            }
        }
    }

    private var frameKey: FrameKey? = null
    private var cachedFrame: FrameCache? = null

    internal val cachedPixelCount: Long
        get() = cachedFrame?.pixelCount ?: 0L

    fun ensureCache(
        accentColor: Color,
        style: GlowStyle = GlowStyle.SOFT,
        intensity: Int = 40,
        glowWidth: Int = DEFAULT_GLOW_WIDTH,
    ) {
        val panelBackground = UIManager.getColor("Panel.background")
        val isLightTheme = panelBackground != null && !ColorUtil.isDark(panelBackground)
        val key = StyleKey(style, accentColor, intensity, glowWidth, isLightTheme)
        if (key == styleKey) return

        val baseAlpha = (intensity / PERCENTAGE_DIVISOR * MAX_ALPHA).toInt().coerceIn(0, MAX_ALPHA)
        cachedBaseAlpha =
            if (isLightTheme) {
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
     * Paints glow from cached border slices:
     * concentric rounded rectangles from edge inward, or — with [edgesOnly] —
     * straight full-height vertical falloff strips on the left and right,
     * with no corner arcs at all.
     */
    fun paintGlow(
        graphics: Graphics2D,
        bounds: Rectangle,
        glowWidth: Int = DEFAULT_GLOW_WIDTH,
        arcWidth: Int = 0,
        edgesOnly: Boolean = false,
    ) {
        if (bounds.width <= 0 || bounds.height <= 0) return

        val nextFrameKey =
            FrameKey(
                bounds.width,
                bounds.height,
                arcWidth,
                cachedStyle,
                cachedColor,
                cachedBaseAlpha,
                glowWidth,
                edgesOnly,
            )

        if (nextFrameKey != frameKey || cachedFrame == null) {
            val startNanos = System.nanoTime()
            cachedFrame =
                if (edgesOnly) {
                    renderSideEdges(bounds.width, bounds.height, glowWidth)
                } else {
                    renderFrame(bounds.width, bounds.height, arcWidth, glowWidth)
                }
            frameKey = nextFrameKey
            val elapsedMs = (System.nanoTime() - startNanos) / NANOS_PER_MS
            if (elapsedMs > FRAME_RENDER_BUDGET_MS) {
                log.warn("Glow frame render took %.2fms (target: <16ms) — cached for reuse".format(elapsedMs))
            }
        }

        cachedFrame?.paint(graphics, bounds.x, bounds.y)
    }

    private fun renderFrame(
        width: Int,
        height: Int,
        arcWidth: Int,
        glowWidth: Int,
    ): FrameCache {
        val layers = frameLayers(width, height, arcWidth, glowWidth)
        if (layers.isEmpty()) return FrameCache(emptyList())

        val arcExtent = (arcWidth.coerceAtLeast(0) + 1) / 2
        val thickness = maxOf(glowWidth + CACHE_EDGE_PADDING, arcExtent + CACHE_EDGE_PADDING)
        val slices = borderRegions(width, height, thickness).map { region -> renderSlice(region, layers) }
        return FrameCache(slices)
    }

    // Side-edges placement: two mirrored vertical falloff strips, every column
    // uniform from y=0 to height — deliberately no rounded-corner geometry.
    private fun renderSideEdges(
        width: Int,
        height: Int,
        glowWidth: Int,
    ): FrameCache {
        val columns = glowWidth.coerceAtMost((width + 1) / 2)
        if (columns <= 0) return FrameCache(emptyList())

        val slices =
            sideRegions(width, height, columns).map { region ->
                renderSideSlice(region, width, height, glowWidth)
            }
        return FrameCache(slices)
    }

    private fun frameLayers(
        width: Int,
        height: Int,
        arcWidth: Int,
        glowWidth: Int,
    ): List<GlowLayer> =
        buildList {
            for (index in 0 until glowWidth) {
                val progress = index.toFloat() / glowWidth.toFloat()
                val alpha = computeAlpha(progress)
                if (alpha <= 0) continue

                val inset = index.toDouble()
                val outerWidth = (width - 2.0 * inset).coerceAtLeast(0.0)
                val outerHeight = (height - 2.0 * inset).coerceAtLeast(0.0)
                if (outerWidth <= 0 || outerHeight <= 0) break

                val outerArc = if (arcWidth > 0) (arcWidth.toDouble() - 2.0 * index).coerceAtLeast(0.0) else 0.0
                val outer = RoundRectangle2D.Double(inset, inset, outerWidth, outerHeight, outerArc, outerArc)
                val nextInset = inset + 1.0
                val innerWidth = (width - 2.0 * nextInset).coerceAtLeast(0.0)
                val innerHeight = (height - 2.0 * nextInset).coerceAtLeast(0.0)
                val shape =
                    if (innerWidth > 0 && innerHeight > 0) {
                        val innerArc =
                            if (arcWidth > 0) {
                                (arcWidth.toDouble() - 2.0 * (index + 1)).coerceAtLeast(0.0)
                            } else {
                                0.0
                            }
                        Area(outer).apply {
                            subtract(
                                Area(
                                    RoundRectangle2D.Double(
                                        nextInset,
                                        nextInset,
                                        innerWidth,
                                        innerHeight,
                                        innerArc,
                                        innerArc,
                                    ),
                                ),
                            )
                        }
                    } else {
                        outer
                    }
                add(GlowLayer(shape, ColorUtil.toAlpha(cachedColor, alpha)))
            }
        }

    private fun renderSlice(
        region: Rectangle,
        layers: List<GlowLayer>,
    ): FrameSlice {
        val image = UIUtil.createImage(null as Component?, region.width, region.height, BufferedImage.TYPE_INT_ARGB)
        val g2 = image.createGraphics()
        try {
            g2.translate(-region.x, -region.y)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            layers.forEach { layer ->
                g2.color = layer.color
                g2.fill(layer.shape)
            }
        } finally {
            g2.dispose()
        }
        return FrameSlice(image, region.x, region.y)
    }

    private fun renderSideSlice(
        region: Rectangle,
        frameWidth: Int,
        frameHeight: Int,
        glowWidth: Int,
    ): FrameSlice {
        val image = UIUtil.createImage(null as Component?, region.width, region.height, BufferedImage.TYPE_INT_ARGB)
        val g2 = image.createGraphics()
        try {
            g2.translate(-region.x, -region.y)
            val columns = glowWidth.coerceAtMost((frameWidth + 1) / 2)
            for (index in 0 until columns) {
                val progress = index.toFloat() / glowWidth.toFloat()
                val alpha = computeAlpha(progress)
                if (alpha <= 0) continue

                g2.color = ColorUtil.toAlpha(cachedColor, alpha)
                g2.fillRect(index, 0, 1, frameHeight)
                // On odd-width overlays the strips meet in the middle; paint
                // the shared center column once or SrcOver doubles its alpha
                // into a bright seam.
                val rightX = frameWidth - 1 - index
                if (rightX != index) g2.fillRect(rightX, 0, 1, frameHeight)
            }
        } finally {
            g2.dispose()
        }
        return FrameSlice(image, region.x, region.y)
    }

    private fun borderRegions(
        width: Int,
        height: Int,
        thickness: Int,
    ): List<Rectangle> {
        val topHeight = thickness.coerceAtMost(height)
        val bottomY = maxOf(topHeight, height - thickness)
        val middleHeight = bottomY - topHeight
        val leftWidth = thickness.coerceAtMost(width)
        val rightX = maxOf(leftWidth, width - thickness)
        return buildList {
            add(Rectangle(0, 0, width, topHeight))
            if (bottomY < height) add(Rectangle(0, bottomY, width, height - bottomY))
            if (middleHeight > 0) {
                add(Rectangle(0, topHeight, leftWidth, middleHeight))
                if (rightX < width) add(Rectangle(rightX, topHeight, width - rightX, middleHeight))
            }
        }
    }

    private fun sideRegions(
        width: Int,
        height: Int,
        columns: Int,
    ): List<Rectangle> {
        val leftWidth = columns.coerceAtMost(width)
        val rightX = maxOf(leftWidth, width - columns)
        return buildList {
            add(Rectangle(0, 0, leftWidth, height))
            if (rightX < width) add(Rectangle(rightX, 0, width - rightX, height))
        }
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
