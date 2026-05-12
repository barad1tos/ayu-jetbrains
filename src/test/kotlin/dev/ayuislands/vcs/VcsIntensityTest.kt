package dev.ayuislands.vcs

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Behavioural coverage for [VcsIntensity] — the typed wrapper that guards the
 * 0..100 slider range and the [VcsIntensity.DEFAULT] no-op constant.
 *
 * The clamp behaviour matters because persisted XML values can drift outside
 * the slider range (hand-edited config, schema migration artifact); the
 * blender must never see an out-of-range value. The DEFAULT constant matters
 * because the freshly-installed Pro flow starts every category at it — if a
 * future refactor pegs DEFAULT to a non-stock slider position, the master
 * toggle would visibly change colours the moment a user enables it.
 */
class VcsIntensityTest {
    @Test
    fun `of clamps below MIN to MIN`() {
        assertEquals(VcsIntensity.MIN, VcsIntensity.of(-1).percent)
        assertEquals(VcsIntensity.MIN, VcsIntensity.of(Int.MIN_VALUE).percent)
    }

    @Test
    fun `of clamps above MAX to MAX`() {
        assertEquals(VcsIntensity.MAX, VcsIntensity.of(101).percent)
        assertEquals(VcsIntensity.MAX, VcsIntensity.of(Int.MAX_VALUE).percent)
    }

    @Test
    fun `of preserves in-range values`() {
        assertEquals(0, VcsIntensity.of(0).percent)
        assertEquals(33, VcsIntensity.of(33).percent)
        assertEquals(67, VcsIntensity.of(67).percent)
        assertEquals(100, VcsIntensity.of(100).percent)
    }

    @Test
    fun `DEFAULT equals AMBIENT_SLIDER so the master toggle is visibly a no-op on install`() {
        assertEquals(VcsColorPreset.AMBIENT_SLIDER, VcsIntensity.DEFAULT.percent)
    }
}
