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
    fun `default state has VCS disabled and Ambient preset at slider 33`() {
        val state = freshState()
        assertFalse(state.vcsColorEnabled, "Master kill-switch defaults off (2.6.2 byte-identical)")
        assertEquals(VcsColorPreset.AMBIENT, state.effectiveVcsColorPreset())
        // Master off => effective intensity is always 0, regardless of preset.
        for (category in VcsColorCategory.entries) {
            assertEquals(0, state.effectiveVcsIntensityFor(category).percent)
        }
    }

    @Test
    fun `effectiveVcsColorPreset resolves persisted enum name`() {
        val state = freshState()
        state.vcsColorPreset = VcsColorPreset.WHISPER.name
        assertEquals(VcsColorPreset.WHISPER, state.effectiveVcsColorPreset())

        state.vcsColorPreset = VcsColorPreset.NEON.name
        assertEquals(VcsColorPreset.NEON, state.effectiveVcsColorPreset())

        state.vcsColorPreset = VcsColorPreset.CYBERPUNK.name
        assertEquals(VcsColorPreset.CYBERPUNK, state.effectiveVcsColorPreset())

        state.vcsColorPreset = VcsColorPreset.CUSTOM.name
        assertEquals(VcsColorPreset.CUSTOM, state.effectiveVcsColorPreset())
    }

    @Test
    fun `effectiveVcsColorPreset falls back to Ambient on unknown persisted string`() {
        // Hand-edited / migrated XML scenario: persisted value isn't an enum name.
        // Ambient is the no-op default, keeping a corrupted XML from accidentally
        // tinting surfaces the user never opted into.
        val state = freshState()
        state.vcsColorPreset = "GARBAGE_FROM_OLDER_SCHEMA"
        assertEquals(VcsColorPreset.AMBIENT, state.effectiveVcsColorPreset())
    }

    @Test
    fun `effectiveVcsIntensityFor returns zero when master toggle is off regardless of preset`() {
        val state = freshState()
        state.vcsColorPreset = VcsColorPreset.CYBERPUNK.name
        // Master toggle still off — applier must see zero intensity for every category.
        for (category in VcsColorCategory.entries) {
            assertEquals(0, state.effectiveVcsIntensityFor(category).percent)
        }
    }

    @Test
    fun `effectiveVcsIntensityFor maps each preset to its canonical slider value`() {
        val state = freshState()
        state.vcsColorEnabled = true

        state.vcsColorPreset = VcsColorPreset.WHISPER.name
        for (category in VcsColorCategory.entries) {
            assertEquals(VcsColorPreset.WHISPER_SLIDER, state.effectiveVcsIntensityFor(category).percent)
        }

        state.vcsColorPreset = VcsColorPreset.AMBIENT.name
        for (category in VcsColorCategory.entries) {
            assertEquals(VcsColorPreset.AMBIENT_SLIDER, state.effectiveVcsIntensityFor(category).percent)
        }

        state.vcsColorPreset = VcsColorPreset.NEON.name
        for (category in VcsColorCategory.entries) {
            assertEquals(VcsColorPreset.NEON_SLIDER, state.effectiveVcsIntensityFor(category).percent)
        }

        state.vcsColorPreset = VcsColorPreset.CYBERPUNK.name
        for (category in VcsColorCategory.entries) {
            assertEquals(VcsColorPreset.CYBERPUNK_SLIDER, state.effectiveVcsIntensityFor(category).percent)
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
    fun `effectiveVcsPerCategoryIntensities returns every category at the Ambient default`() {
        val state = freshState()
        val map = state.effectiveVcsPerCategoryIntensities()
        for (category in VcsColorCategory.entries) {
            assertEquals(
                VcsColorPreset.AMBIENT_SLIDER,
                map[category],
                "category $category missing or not at Ambient default",
            )
        }
    }
}
