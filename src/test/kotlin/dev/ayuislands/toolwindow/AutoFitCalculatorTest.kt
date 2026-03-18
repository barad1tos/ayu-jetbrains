package dev.ayuislands.toolwindow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoFitCalculatorTest {
    @Test
    fun `calculateDesiredWidth adds padding to max row width`() {
        val result =
            AutoFitCalculator.calculateDesiredWidth(
                maxRowWidth = 200,
                maxWidth = 500,
                minWidth = 100,
            )
        assertEquals(220, result)
    }

    @Test
    fun `calculateDesiredWidth clamps to max width`() {
        val result =
            AutoFitCalculator.calculateDesiredWidth(
                maxRowWidth = 600,
                maxWidth = 500,
                minWidth = 100,
            )
        assertEquals(500, result)
    }

    @Test
    fun `calculateDesiredWidth clamps to min width`() {
        val result =
            AutoFitCalculator.calculateDesiredWidth(
                maxRowWidth = 30,
                maxWidth = 500,
                minWidth = 200,
            )
        assertEquals(200, result)
    }

    @Test
    fun `calculateDesiredWidth with zero row width returns min`() {
        val result =
            AutoFitCalculator.calculateDesiredWidth(
                maxRowWidth = 0,
                maxWidth = 500,
                minWidth = 100,
            )
        assertEquals(100, result)
    }

    @Test
    fun `calculateDesiredWidth min takes precedence over max when min greater than max`() {
        val result =
            AutoFitCalculator.calculateDesiredWidth(
                maxRowWidth = 100,
                maxWidth = 50,
                minWidth = 200,
            )
        assertEquals(200, result)
    }

    @Test
    fun `isJitterOnly returns true for small delta`() {
        assertTrue(AutoFitCalculator.isJitterOnly(currentWidth = 300, desiredWidth = 305))
    }

    @Test
    fun `isJitterOnly returns true at threshold boundary`() {
        assertTrue(AutoFitCalculator.isJitterOnly(currentWidth = 300, desiredWidth = 308))
    }

    @Test
    fun `isJitterOnly returns false for large delta`() {
        assertFalse(AutoFitCalculator.isJitterOnly(currentWidth = 300, desiredWidth = 320))
    }

    @Test
    fun `isJitterOnly handles negative delta`() {
        assertTrue(AutoFitCalculator.isJitterOnly(currentWidth = 300, desiredWidth = 295))
    }

    @Test
    fun `isJitterOnly returns false just above threshold`() {
        assertFalse(AutoFitCalculator.isJitterOnly(currentWidth = 300, desiredWidth = 309))
    }

    @Test
    fun `isJitterOnly returns true for equal widths`() {
        assertTrue(AutoFitCalculator.isJitterOnly(currentWidth = 300, desiredWidth = 300))
    }
}
