package dev.ayuislands.whatsnew

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.awt.image.ConvolveOp
import java.awt.image.Kernel
import javax.swing.JPanel

/**
 * Paints a slide screenshot with rounded corners and a soft directional drop
 * shadow. Sizing is owned here: [getPreferredSize] reads the parent column's
 * current width and returns the lesser of `(maxLogicalImageWidth + shadow spread)`
 * and the parent — so wider IDE windows let the image grow up to its cap and
 * narrower windows shrink it without overflow. No external scaler registration
 * is needed (would double-scale). `paintComponent` scales the backing [source]
 * bitmap and rebuilds a cached Gaussian-blurred shadow to match the size at
 * each layout pass.
 *
 * Source images are expected to be "clean" — containing only window content,
 * with no gray desktop/IDE background captured around them. We intentionally
 * do NOT trim at runtime: that's been moved to a pre-production step
 * (the maintainer runs a small cropping utility against `resources/whatsnew/`
 * before committing PNGs). Runtime rendering stays simple and predictable.
 */
internal class WhatsNewImagePanel(
    image: Image,
    widthFactor: Float,
) : JPanel() {
    private val source: BufferedImage = toBufferedImage(image)
    private val aspectRatio: Double = source.height.toDouble() / source.width.toDouble()

    /**
     * Max logical width this slide should reach, after per-slide factor.
     * A factor of 1.0 = [DEFAULT_IMAGE_WIDTH]; 1.5 = 150% of that; etc.
     * The image never grows past this even on very wide IDE windows.
     */
    private val maxLogicalImageWidth: Int = computeMaxLogicalImageWidth(widthFactor)

    private var shadowCache: BufferedImage? = null
    private var shadowCacheW: Int = -1
    private var shadowCacheH: Int = -1

    private var lastResolvedParentWidth: Int = -1
    private var cachedPreferredSize: Dimension? = null

    init {
        isOpaque = false
    }

    /**
     * Target logical width is the lesser of:
     *  - the maximum allowed (manifest imageScale × DEFAULT_IMAGE_WIDTH)
     *  - the parent column's current width (so the image always fits).
     *
     * Height derives from the source aspect ratio so the image never gets
     * stretched or squished. The shadow spread is added on top so the
     * rendered drop-shadow halo has room without being clipped.
     *
     * Result is cached per (parentWidth) and reused until parent width
     * changes — without the cache, layout managers can re-query during
     * the same pass and oscillate when the returned size shrinks the
     * parent that we just measured.
     */
    override fun getPreferredSize(): Dimension {
        val parentWidth = resolveParentWidth()
        val cached = cachedPreferredSize
        if (cached != null && parentWidth == lastResolvedParentWidth) return cached
        val maxScaledW = JBUI.scale(maxLogicalImageWidth + SHADOW_X_SPREAD)
        val targetW =
            if (parentWidth > 0) {
                minOf(maxScaledW, parentWidth)
            } else {
                maxScaledW
            }
        val imgW = (targetW - JBUI.scale(SHADOW_X_SPREAD)).coerceAtLeast(1)
        val imgH = (imgW * aspectRatio).toInt().coerceAtLeast(1)
        val size = Dimension(targetW, imgH + JBUI.scale(SHADOW_Y_SPREAD))
        lastResolvedParentWidth = parentWidth
        cachedPreferredSize = size
        return size
    }

    override fun getMaximumSize(): Dimension = preferredSize

    override fun getMinimumSize(): Dimension {
        val minScaledW = JBUI.scale(MIN_IMAGE_WIDTH + SHADOW_X_SPREAD)
        val imgW = (minScaledW - JBUI.scale(SHADOW_X_SPREAD)).coerceAtLeast(1)
        val imgH = (imgW * aspectRatio).toInt().coerceAtLeast(1)
        return Dimension(minScaledW, imgH + JBUI.scale(SHADOW_Y_SPREAD))
    }

    /**
     * Walks up the component tree looking for a laid-out ancestor so the
     * preferredSize calculation can cap the image to what the tab actually
     * offers. The immediate parent is often a BoxLayout.X_AXIS wrapper that
     * has zero width before its own pref is computed — so we keep climbing
     * until we hit something sized, or run out of ancestors.
     */
    private fun resolveParentWidth(): Int {
        var node: java.awt.Container? = parent
        while (node != null) {
            val w = node.width
            if (w > 0) return w
            node = node.parent
        }
        return 0
    }

    override fun removeNotify() {
        super.removeNotify()
        // Drop the bitmap when the panel is unparented (tab close / reparent).
        // Without this the per-instance shadow + cached preferredSize survive
        // until GC, holding image memory proportional to the slide count.
        shadowCache = null
        shadowCacheW = -1
        shadowCacheH = -1
        cachedPreferredSize = null
        lastResolvedParentWidth = -1
    }

    override fun invalidate() {
        // Layout invalidation cascades from any ancestor change (window resize,
        // viewport scroll, scaler.apply revalidate). Drop the preferredSize
        // cache so the next layout pass re-measures against the now-current
        // parent width — without this the cache could stick to a stale value
        // when the BoxLayout parent width happens to equal a previous result.
        cachedPreferredSize = null
        lastResolvedParentWidth = -1
        super.invalidate()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g2 = graphics.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)

            val spreadX = JBUI.scale(SHADOW_X_SPREAD)
            val spreadY = JBUI.scale(SHADOW_Y_SPREAD)
            val imgW = (width - spreadX).coerceAtLeast(1)
            val imgH = (height - spreadY).coerceAtLeast(1)
            val arc = JBUI.scale(CORNER_ARC)

            val shadow = shadowFor(imgW, imgH)
            g2.drawImage(
                shadow,
                -JBUI.scale(SHADOW_BLUR_RADIUS) + JBUI.scale(SHADOW_OFFSET_X),
                -JBUI.scale(SHADOW_BLUR_RADIUS) + JBUI.scale(SHADOW_OFFSET_Y),
                null,
            )

            val clip =
                RoundRectangle2D.Float(
                    0f,
                    0f,
                    imgW.toFloat(),
                    imgH.toFloat(),
                    arc.toFloat(),
                    arc.toFloat(),
                )
            val oldClip = g2.clip
            g2.clip(clip)
            g2.drawImage(source, 0, 0, imgW, imgH, null)
            g2.clip = oldClip

            g2.color = BORDER_COLOR
            g2.drawRoundRect(0, 0, imgW - 1, imgH - 1, arc, arc)
        } finally {
            g2.dispose()
        }
    }

    private fun shadowFor(
        imgW: Int,
        imgH: Int,
    ): BufferedImage {
        if (shadowCache != null && shadowCacheW == imgW && shadowCacheH == imgH) return shadowCache!!
        val fresh = buildBlurredShadow(imgW, imgH)
        shadowCache = fresh
        shadowCacheW = imgW
        shadowCacheH = imgH
        return fresh
    }

    private fun buildBlurredShadow(
        imgW: Int,
        imgH: Int,
    ): BufferedImage {
        val margin = JBUI.scale(SHADOW_BLUR_RADIUS)
        val totalW = imgW + margin * 2
        val totalH = imgH + margin * 2
        val shadow = BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_ARGB)
        val sg = shadow.createGraphics()
        try {
            sg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            sg.composite = AlphaComposite.Src
            sg.color = Color(0, 0, 0, SHADOW_BASE_ALPHA)
            val arc = JBUI.scale(CORNER_ARC)
            sg.fillRoundRect(margin, margin, imgW, imgH, arc, arc)
        } finally {
            sg.dispose()
        }
        return gaussianBlur(shadow)
    }

    companion object {
        // Default natural width of a slide image in logical (non-UI-scaled) px.
        // Manifest widthFactor values scale around this.
        private const val DEFAULT_IMAGE_WIDTH = 800
        private const val MIN_IMAGE_WIDTH = 200
        private const val MIN_WIDTH_FACTOR = 0.3f
        private const val MAX_WIDTH_FACTOR = 2.0f

        /**
         * Pure size math: clamp [widthFactor] into the valid range, multiply by
         * [DEFAULT_IMAGE_WIDTH], and floor at [MIN_IMAGE_WIDTH] so a manifest
         * specifying an absurdly small factor still renders something visible.
         * Extracted for unit testing without instantiating the Swing panel.
         */
        @JvmStatic
        internal fun computeMaxLogicalImageWidth(widthFactor: Float): Int =
            (DEFAULT_IMAGE_WIDTH * widthFactor.coerceIn(MIN_WIDTH_FACTOR, MAX_WIDTH_FACTOR))
                .toInt()
                .coerceAtLeast(MIN_IMAGE_WIDTH)

        // Shadow footprint extends this many logical px past the image edges.
        private const val SHADOW_X_SPREAD = 24
        private const val SHADOW_Y_SPREAD = 28
        private const val SHADOW_OFFSET_X = 2
        private const val SHADOW_OFFSET_Y = 8
        private const val SHADOW_BLUR_RADIUS = 12
        private const val SHADOW_BASE_ALPHA = 90
        private const val CORNER_ARC = 10

        // Hairline border that traces the rendered image edge.
        private const val RGB_MIN = 0
        private const val RGB_MAX = 255
        private const val BORDER_LIGHT_ALPHA = 48
        private const val BORDER_DARK_ALPHA = 32

        private val BORDER_COLOR: JBColor =
            JBColor(
                Color(RGB_MIN, RGB_MIN, RGB_MIN, BORDER_LIGHT_ALPHA),
                Color(RGB_MAX, RGB_MAX, RGB_MAX, BORDER_DARK_ALPHA),
            )

        // Gaussian kernel sigma per the 3-sigma rule — keeps 99.7% of the
        // Gaussian curve inside the kernel window, so any clipping at the
        // kernel edge is negligible.
        private const val GAUSSIAN_SIGMA_DIVISOR = 3f

        private fun toBufferedImage(image: Image): BufferedImage {
            if (image is BufferedImage) return image
            val w = image.getWidth(null).coerceAtLeast(1)
            val h = image.getHeight(null).coerceAtLeast(1)
            val copy = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g = copy.createGraphics()
            try {
                g.drawImage(image, 0, 0, null)
            } finally {
                g.dispose()
            }
            return copy
        }

        private fun gaussianBlur(source: BufferedImage): BufferedImage {
            val kernelSize = SHADOW_BLUR_RADIUS * 2 + 1
            val sigma = SHADOW_BLUR_RADIUS / GAUSSIAN_SIGMA_DIVISOR
            val data = FloatArray(kernelSize)
            var total = 0f
            for (i in 0 until kernelSize) {
                val x = (i - SHADOW_BLUR_RADIUS).toFloat()
                data[i] = kotlin.math.exp(-(x * x) / (2f * sigma * sigma))
                total += data[i]
            }
            for (i in data.indices) data[i] /= total

            val horizontal = Kernel(kernelSize, 1, data)
            val vertical = Kernel(1, kernelSize, data)
            val horizontalOp = ConvolveOp(horizontal, ConvolveOp.EDGE_NO_OP, null)
            val verticalOp = ConvolveOp(vertical, ConvolveOp.EDGE_NO_OP, null)

            val intermediate = horizontalOp.filter(source, null)
            return verticalOp.filter(intermediate, null)
        }
    }
}
