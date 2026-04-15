package dev.ayuislands.whatsnew

import kotlin.test.Test
import kotlin.test.assertEquals
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
            small < mid && mid < large && large < capped,
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
