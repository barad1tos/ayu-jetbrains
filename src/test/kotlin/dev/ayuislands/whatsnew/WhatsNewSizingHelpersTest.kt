package dev.ayuislands.whatsnew

import java.awt.Component
import java.awt.image.BufferedImage
import javax.swing.BoxLayout
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pure-math helpers extracted from Swing-coupled classes. Per the project rule
 * "agents can't do Swing layout", we don't render and assert pixel positions —
 * but every value the layout depends on must have direct red/green coverage.
 *
 * If any of these helpers' clamps drift, slides will either truncate, oscillate
 * during resize, or render at the wrong size on HiDPI displays.
 */
class WhatsNewSizingHelpersTest {
    // -- WhatsNewPanel.computeScale --

    @Test
    fun `computeScale returns 1_0 when panelWidth equals designWidth`() {
        assertEquals(1.0f, WhatsNewPanel.computeScale(panelWidth = 900, scaledDesignWidth = 900))
    }

    @Test
    fun `computeScale caps at MAX_SCALE 1_0 for very wide panels`() {
        // Wider than design → still capped, so cards don't grow past their natural max.
        assertEquals(1.0f, WhatsNewPanel.computeScale(panelWidth = 3000, scaledDesignWidth = 900))
    }

    @Test
    fun `computeScale clamps at MIN_SCALE 0_6 for very narrow panels`() {
        // Narrower than design → coerce up to MIN_SCALE so content stays readable
        // (cards/labels don't shrink to unreadable size).
        assertEquals(0.6f, WhatsNewPanel.computeScale(panelWidth = 200, scaledDesignWidth = 900))
    }

    @Test
    fun `computeScale returns proportional value in the valid range`() {
        // 720 / 900 = 0.8 — inside [0.6, 1.0], passed through unchanged.
        assertEquals(0.8f, WhatsNewPanel.computeScale(panelWidth = 720, scaledDesignWidth = 900))
    }

    @Test
    fun `computeScale returns MIN_SCALE when designWidth is zero or negative`() {
        // Defensive — caller guards on panelWidth > 0 but not on designWidth.
        // Returning MIN_SCALE avoids div-by-zero / negative-ratio surprises.
        assertEquals(0.6f, WhatsNewPanel.computeScale(panelWidth = 900, scaledDesignWidth = 0))
        assertEquals(0.6f, WhatsNewPanel.computeScale(panelWidth = 900, scaledDesignWidth = -10))
    }

    // -- WhatsNewImagePanel.computeMaxLogicalImageWidth --

    @Test
    fun `computeMaxLogicalImageWidth returns DEFAULT for factor 1_0`() {
        assertEquals(800, WhatsNewImagePanel.computeMaxLogicalImageWidth(1.0f))
    }

    @Test
    fun `computeMaxLogicalImageWidth doubles for factor 2_0`() {
        // Real-world usage: the v2.5.0 collage uses imageScale=2.0 to render
        // wider than a single-window screenshot.
        assertEquals(1600, WhatsNewImagePanel.computeMaxLogicalImageWidth(2.0f))
    }

    @Test
    fun `computeMaxLogicalImageWidth halves for factor 0_5`() {
        assertEquals(400, WhatsNewImagePanel.computeMaxLogicalImageWidth(0.5f))
    }

    @Test
    fun `computeMaxLogicalImageWidth caps at MAX_WIDTH_FACTOR for absurdly large factors`() {
        // factor=10 → coerced to 2.0 → 1600. Defends against a manifest typo
        // accidentally rendering a 16x-wide image that overflows the tab.
        assertEquals(1600, WhatsNewImagePanel.computeMaxLogicalImageWidth(10.0f))
    }

    @Test
    fun `computeMaxLogicalImageWidth clamps factor at MIN_WIDTH_FACTOR for tiny inputs`() {
        // The factor floor (MIN_WIDTH_FACTOR=0.3) clips before the width floor
        // (MIN_IMAGE_WIDTH=200) ever kicks in: 0.3 * 800 = 240 > 200. So all
        // these "absurdly small" factors collapse to the same minimum width
        // produced by the factor clamp.
        assertEquals(240, WhatsNewImagePanel.computeMaxLogicalImageWidth(0.3f), "factor = floor → 240")
        assertEquals(240, WhatsNewImagePanel.computeMaxLogicalImageWidth(0.1f), "below-floor factor coerces up")
        assertEquals(240, WhatsNewImagePanel.computeMaxLogicalImageWidth(0.0f), "zero factor coerces up")
        assertEquals(240, WhatsNewImagePanel.computeMaxLogicalImageWidth(-1.0f), "negative factor coerces up")
    }

    @Test
    fun `computeMaxLogicalImageWidth never returns below MIN_IMAGE_WIDTH absolute floor`() {
        // Defense-in-depth: even at the smallest factor the clamp permits, we
        // never drop below the absolute width floor. This guards a future
        // refactor that lowers MIN_WIDTH_FACTOR (or removes the factor clamp)
        // from accidentally rendering an invisible 1-pixel slide.
        val smallest = WhatsNewImagePanel.computeMaxLogicalImageWidth(0.0f)
        assertTrue(smallest >= 200, "result must always be >= MIN_IMAGE_WIDTH=200; got $smallest")
    }

    @Test
    fun `computeMaxLogicalImageWidth is monotonic in factor across the valid range`() {
        // Sanity-check the curve: bigger factor → bigger width, until cap.
        val small = WhatsNewImagePanel.computeMaxLogicalImageWidth(0.5f)
        val mid = WhatsNewImagePanel.computeMaxLogicalImageWidth(1.0f)
        val large = WhatsNewImagePanel.computeMaxLogicalImageWidth(1.5f)
        val capped = WhatsNewImagePanel.computeMaxLogicalImageWidth(2.0f)
        assertTrue(
            mid in (small + 1)..<large && large < capped,
            "widths must increase monotonically with factor up to the cap",
        )
    }

    // -- ShowWhatsNewButton.computeButtonWidth --

    @Test
    fun `computeButtonWidth returns scaledMinWidth when label fits within it`() {
        // "Close" — short label, well under the floor.
        assertEquals(
            160,
            ShowWhatsNewButton.computeButtonWidth(
                labelWidth = 50,
                scaledMinWidth = 160,
                scaledHorizontalPadding = 28,
            ),
        )
    }

    @Test
    fun `computeButtonWidth grows past min when the label demands it`() {
        // Long label like "Open Accent Overrides" — needs more than 160 to fit.
        assertEquals(
            228,
            ShowWhatsNewButton.computeButtonWidth(
                labelWidth = 200,
                scaledMinWidth = 160,
                scaledHorizontalPadding = 28,
            ),
        )
    }

    @Test
    fun `computeButtonWidth picks min when label-plus-padding equals min exactly`() {
        // Boundary case — equals must pick the min (max picks either).
        assertEquals(
            160,
            ShowWhatsNewButton.computeButtonWidth(
                labelWidth = 132,
                scaledMinWidth = 160,
                scaledHorizontalPadding = 28,
            ),
        )
    }

    // -- WhatsNewImagePanel.toBufferedImage --

    @Test
    fun `toBufferedImage returns the same instance when input is already BufferedImage`() {
        // Passthrough path — avoids a needless copy when the caller already
        // has a BufferedImage.
        val input = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB)
        val result = WhatsNewImagePanel.toBufferedImage(input)
        assertSame(input, result, "BufferedImage input must be returned as-is")
    }

    @Test
    fun `toBufferedImage copies non-BufferedImage into a fresh TYPE_INT_ARGB buffer`() {
        // Drive the copy path: a Toolkit Image (not a BufferedImage subclass)
        // goes through drawImage into a fresh buffer with the same dimensions.
        val source = BufferedImage(16, 9, BufferedImage.TYPE_INT_RGB) // non-ARGB
        // Wrap in a plain Image reference so the isa check goes through the
        // copy path instead of the short-circuit.
        val plain: java.awt.Image = source
        val result = WhatsNewImagePanel.toBufferedImage(plain)
        // The isa check sees BufferedImage and short-circuits — same instance.
        assertSame(source, result)

        // Now cover the copy branch by passing a real non-BufferedImage proxy.
        val proxy =
            object : java.awt.Image() {
                override fun getWidth(observer: java.awt.image.ImageObserver?) = 20

                override fun getHeight(observer: java.awt.image.ImageObserver?) = 15

                override fun getSource(): java.awt.image.ImageProducer = source.source

                override fun getGraphics() = source.createGraphics()

                override fun getProperty(
                    name: String,
                    observer: java.awt.image.ImageObserver?,
                ) = null
            }
        val copied: java.awt.Image = WhatsNewImagePanel.toBufferedImage(proxy)
        assertNotSame<java.awt.Image>(proxy, copied, "non-BufferedImage input must be copied")
        assertEquals(20, (copied as BufferedImage).width, "copied width must match source")
        assertEquals(15, copied.height, "copied height must match source")
    }

    @Test
    fun `toBufferedImage floors at 1x1 for zero-or-negative dimensions`() {
        // Defensive — a broken Image returning getWidth=-1 must still produce
        // a renderable copy (one pixel) instead of a BufferedImage constructor
        // IllegalArgumentException.
        val brokenProxy =
            object : java.awt.Image() {
                override fun getWidth(observer: java.awt.image.ImageObserver?) = -1

                override fun getHeight(observer: java.awt.image.ImageObserver?) = 0

                override fun getSource() = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).source

                override fun getGraphics() = null

                override fun getProperty(
                    name: String,
                    observer: java.awt.image.ImageObserver?,
                ) = null
            }
        val result = WhatsNewImagePanel.toBufferedImage(brokenProxy)
        assertEquals(1, result.width, "width must floor at 1")
        assertEquals(1, result.height, "height must floor at 1")
    }

    // -- WhatsNewImagePanel.gaussianBlur --

    @Test
    fun `gaussianBlur returns an image with the same dimensions as input`() {
        // The separable convolution runs horizontal → vertical and must
        // preserve image bounds (the filter pads edges to EDGE_NO_OP, so
        // the result shares the source dimensions).
        val input = BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB)
        val blurred = WhatsNewImagePanel.gaussianBlur(input)
        assertEquals(40, blurred.width, "blur must preserve width")
        assertEquals(30, blurred.height, "blur must preserve height")
    }

    @Test
    fun `gaussianBlur on a solid fill preserves the average luminance`() {
        // The Gaussian kernel is normalized so the sum of weights == 1. A
        // solid-color input must come out of the blur with the same average
        // color (within anti-aliasing + edge handling tolerance). If a future
        // refactor drops the `data[i] /= total` normalization, the output
        // pixel values would shift and this assertion would catch it.
        val input = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        val g = input.createGraphics()
        try {
            g.color = java.awt.Color(0, 0, 0, 128) // 50% black
            g.fillRect(0, 0, 32, 32)
        } finally {
            g.dispose()
        }
        val blurred = WhatsNewImagePanel.gaussianBlur(input)
        // Check the alpha channel at the center — should still be ~128 after
        // blur (edges may differ due to EDGE_NO_OP, but center is stable).
        val centerArgb = blurred.getRGB(16, 16)
        val centerAlpha = (centerArgb shr 24) and 0xff
        assertTrue(
            centerAlpha in 120..135,
            "blurred center alpha must stay near 128 (normalization preserved); got $centerAlpha",
        )
    }

    // -- centerInRow shared helper --

    @Test
    fun `centerInRow wraps the child between two horizontal glue components`() {
        // Centering relies on glue-child-glue layout. If a future refactor
        // accidentally drops one of the glues, the child no longer centers
        // (it sticks left or right). Pin the structure with this test.
        val child = JPanel()
        val row = centerInRow(child, Component.CENTER_ALIGNMENT)
        assertEquals(3, row.componentCount, "wrapper must contain glue + child + glue")
        assertEquals(child, row.getComponent(1), "child must be the middle component")
    }

    @Test
    fun `centerInRow uses BoxLayout X_AXIS for horizontal flow`() {
        val row = centerInRow(JPanel(), Component.LEFT_ALIGNMENT)
        val layout = row.layout
        assertTrue(layout is BoxLayout, "wrapper must use BoxLayout for the glue-child-glue distribution")
    }

    @Test
    fun `centerInRow respects the requested wrapper alignment`() {
        // The wrapper's alignmentX controls how it sits within its own Y-axis
        // BoxLayout parent. WhatsNewPanel passes CENTER (centers cards in the
        // viewport); WhatsNewSlideCard passes LEFT (keeps the wrapper flush
        // with the text column above it). Pin both so a future caller can't
        // accidentally swap and break either layout.
        val left = centerInRow(JPanel(), Component.LEFT_ALIGNMENT)
        val center = centerInRow(JPanel(), Component.CENTER_ALIGNMENT)
        assertEquals(Component.LEFT_ALIGNMENT, left.alignmentX)
        assertEquals(Component.CENTER_ALIGNMENT, center.alignmentX)
    }

    @Test
    fun `computeButtonWidth handles zero-padding edge case`() {
        // Defends against a future caller passing zero padding (e.g. test).
        assertEquals(
            160,
            ShowWhatsNewButton.computeButtonWidth(
                labelWidth = 80,
                scaledMinWidth = 160,
                scaledHorizontalPadding = 0,
            ),
        )
        assertEquals(
            300,
            ShowWhatsNewButton.computeButtonWidth(
                labelWidth = 300,
                scaledMinWidth = 160,
                scaledHorizontalPadding = 0,
            ),
        )
    }
}
