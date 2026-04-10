package dev.ayuislands.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals

class OnboardingSharedRenderingTest {
    // --- computeCoverDimensions ---

    @Test
    fun `cover dimensions with wider panel than image aspect ratio`() {
        // Panel 800x600, viewBox 1600x1000
        // widthScale = 800/1600 = 0.5, heightScale = 600/1000 = 0.6
        // maxOf(0.5, 0.6) = 0.6 -> covers height, overflows width
        val (scaledW, scaledH) = computeCoverDimensions(800, 600, 1600.0, 1000.0)

        assertEquals((1600.0 * 0.6).toInt(), scaledW)
        assertEquals((1000.0 * 0.6).toInt(), scaledH)
    }

    @Test
    fun `cover dimensions with taller panel than image aspect ratio`() {
        // Panel 400x800, viewBox 1600x1000
        // widthScale = 400/1600 = 0.25, heightScale = 800/1000 = 0.8
        // maxOf(0.25, 0.8) = 0.8 -> covers height, overflows width
        val (scaledW, scaledH) = computeCoverDimensions(400, 800, 1600.0, 1000.0)

        assertEquals((1600.0 * 0.8).toInt(), scaledW)
        assertEquals((1000.0 * 0.8).toInt(), scaledH)
    }

    @Test
    fun `cover dimensions with square panel and wide image`() {
        // Panel 500x500, viewBox 1000x500
        // widthScale = 500/1000 = 0.5, heightScale = 500/500 = 1.0
        // maxOf(0.5, 1.0) = 1.0 -> covers height at full size
        val (scaledW, scaledH) = computeCoverDimensions(500, 500, 1000.0, 500.0)

        assertEquals(1000, scaledW)
        assertEquals(500, scaledH)
    }

    @Test
    fun `cover dimensions with square panel and tall image`() {
        // Panel 500x500, viewBox 500x1000
        // widthScale = 500/500 = 1.0, heightScale = 500/1000 = 0.5
        // maxOf(1.0, 0.5) = 1.0 -> covers width at full size
        val (scaledW, scaledH) = computeCoverDimensions(500, 500, 500.0, 1000.0)

        assertEquals(500, scaledW)
        assertEquals(1000, scaledH)
    }

    @Test
    fun `cover dimensions with panel matching image aspect ratio`() {
        // Panel 800x500, viewBox 1600x1000
        // widthScale = 800/1600 = 0.5, heightScale = 500/1000 = 0.5
        // maxOf(0.5, 0.5) = 0.5 -> exact fit, no overflow
        val (scaledW, scaledH) = computeCoverDimensions(800, 500, 1600.0, 1000.0)

        assertEquals(800, scaledW)
        assertEquals(500, scaledH)
    }

    @Test
    fun `cover dimensions with panel larger than viewBox`() {
        // Panel 3200x2000, viewBox 1600x1000 -> scale = 2.0
        val (scaledW, scaledH) = computeCoverDimensions(3200, 2000, 1600.0, 1000.0)

        assertEquals(3200, scaledW)
        assertEquals(2000, scaledH)
    }

    @Test
    fun `cover dimensions always covers both axes`() {
        // For any input, scaled dimensions must be >= panel dimensions
        val cases =
            listOf(
                Triple(800, 600, 1600.0 to 1000.0),
                Triple(400, 800, 1600.0 to 1000.0),
                Triple(1920, 1080, 1600.0 to 1000.0),
                Triple(100, 100, 1600.0 to 1000.0),
            )
        for ((panelW, panelH, viewBox) in cases) {
            val (scaledW, scaledH) = computeCoverDimensions(panelW, panelH, viewBox.first, viewBox.second)
            assertTrue(
                scaledW >= panelW,
                "scaledW=$scaledW should be >= panelW=$panelW",
            )
            assertTrue(
                scaledH >= panelH,
                "scaledH=$scaledH should be >= panelH=$panelH",
            )
        }
    }

    private fun assertTrue(
        condition: Boolean,
        message: String,
    ) {
        kotlin.test.assertTrue(condition, message)
    }
}
