package dev.ayuislands.settings

import dev.ayuislands.vcs.VcsColorCategory
import dev.ayuislands.vcs.VcsColorPreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Tests for the Phase 40.2 VCS color customization state helpers.
 *
 * Covers the same defensive-read pattern Chrome Tinting established in
 * [AyuIslandsStateTest]: every read site for a value the applier consumes
 * routes through an `effective*` helper so a corrupted persisted XML never
 * reaches the HSB blender or applier with a value outside its contract.
 */
class AyuIslandsStateVcsTest {
    private fun freshState(): AyuIslandsState = AyuIslandsState()

    @Test
    fun `default state has VCS disabled and Muted preset`() {
        val state = freshState()
        assertFalse(state.vcsColorEnabled, "Master kill-switch defaults off (2.6.2 byte-identical)")
        assertEquals(VcsColorPreset.MUTED, state.effectiveVcsColorPreset())
        for (category in VcsColorCategory.entries) {
            assertEquals(0, state.effectiveVcsIntensityFor(category).percent)
        }
    }

    @Test
    fun `effectiveVcsColorPreset resolves persisted enum name`() {
        val state = freshState()
        state.vcsColorPreset = VcsColorPreset.BALANCED.name
        assertEquals(VcsColorPreset.BALANCED, state.effectiveVcsColorPreset())

        state.vcsColorPreset = VcsColorPreset.VIBRANT.name
        assertEquals(VcsColorPreset.VIBRANT, state.effectiveVcsColorPreset())

        state.vcsColorPreset = VcsColorPreset.CUSTOM.name
        assertEquals(VcsColorPreset.CUSTOM, state.effectiveVcsColorPreset())
    }

    @Test
    fun `effectiveVcsColorPreset falls back to Muted on unknown persisted string`() {
        // Hand-edited / migrated XML scenario: persisted value isn't an enum name.
        val state = freshState()
        state.vcsColorPreset = "GARBAGE_FROM_OLDER_SCHEMA"
        assertEquals(VcsColorPreset.MUTED, state.effectiveVcsColorPreset())
    }

    @Test
    fun `effectiveVcsIntensityFor returns zero when master toggle is off regardless of preset`() {
        val state = freshState()
        state.vcsColorPreset = VcsColorPreset.VIBRANT.name
        // Master toggle still off — applier must see zero intensity for every category.
        for (category in VcsColorCategory.entries) {
            assertEquals(0, state.effectiveVcsIntensityFor(category).percent)
        }
    }

    @Test
    fun `effectiveVcsIntensityFor reads preset table for non-Custom presets`() {
        val state = freshState()
        state.vcsColorEnabled = true
        state.vcsColorPreset = VcsColorPreset.BALANCED.name
        // Balanced preset declares 50% across all categories.
        for (category in VcsColorCategory.entries) {
            assertEquals(50, state.effectiveVcsIntensityFor(category).percent)
        }
    }

    @Test
    fun `effectiveVcsIntensityFor reads per-category property when preset is Custom`() {
        val state = freshState()
        state.vcsColorEnabled = true
        state.vcsColorPreset = VcsColorPreset.CUSTOM.name
        state.vcsDiffIntensity = 75
        state.vcsBlameIntensity = 25
        assertEquals(75, state.effectiveVcsIntensityFor(VcsColorCategory.DIFF_VIEWER).percent)
        assertEquals(25, state.effectiveVcsIntensityFor(VcsColorCategory.BLAME_GUTTER).percent)
    }

    @Test
    fun `effectiveVcsIntensityFor clamps out-of-range per-category property in Custom mode`() {
        val state = freshState()
        state.vcsColorEnabled = true
        state.vcsColorPreset = VcsColorPreset.CUSTOM.name
        state.vcsDiffIntensity = 250 // Hand-edited XML / migration artifact
        state.vcsBlameIntensity = -50
        assertEquals(100, state.effectiveVcsIntensityFor(VcsColorCategory.DIFF_VIEWER).percent)
        assertEquals(0, state.effectiveVcsIntensityFor(VcsColorCategory.BLAME_GUTTER).percent)
    }

    @Test
    fun `effectiveVcsPerCategoryIntensities returns every category`() {
        val state = freshState()
        val map = state.effectiveVcsPerCategoryIntensities()
        for (category in VcsColorCategory.entries) {
            assertEquals(
                0,
                map[category],
                "category $category missing or non-zero on default state",
            )
        }
    }
}
