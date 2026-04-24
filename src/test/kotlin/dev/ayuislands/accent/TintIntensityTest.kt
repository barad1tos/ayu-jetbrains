package dev.ayuislands.accent

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [TintIntensity] — the typed wrapper that lifts the
 * `chromeTintIntensity.coerceIn(MIN, MAX)` read-boundary clamp into the type
 * system so raw integers can't reach [ChromeTintBlender.blend].
 */
class TintIntensityTest {
    @Test
    fun `of clamps negative to MIN`() {
        assertEquals(TintIntensity.MIN, TintIntensity.of(-1).percent)
        assertEquals(TintIntensity.MIN, TintIntensity.of(-100).percent)
    }

    @Test
    fun `of clamps above MAX to MAX`() {
        assertEquals(TintIntensity.MAX, TintIntensity.of(TintIntensity.MAX + 1).percent)
        assertEquals(TintIntensity.MAX, TintIntensity.of(100).percent)
        assertEquals(TintIntensity.MAX, TintIntensity.of(500).percent)
    }

    @Test
    fun `of preserves values in-range`() {
        assertEquals(0, TintIntensity.of(0).percent)
        assertEquals(25, TintIntensity.of(25).percent)
        assertEquals(TintIntensity.MAX, TintIntensity.of(TintIntensity.MAX).percent)
    }

    @Test
    fun `DEFAULT returns a value with percent=40`() {
        assertEquals(40, TintIntensity.DEFAULT.percent)
    }
}
