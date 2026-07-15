package dev.ayuislands.settings

import dev.ayuislands.vcs.VcsColorCategory
import dev.ayuislands.vcs.VcsColorPreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Tests for the VCS color customization state helpers, including the
 * per-section preset redesign.
 *
 * Covers the same defensive-read pattern Chrome Tinting established in
 * [AyuIslandsStateTest]: every read site for a value the applier consumes
 * routes through an `effective*` helper so a corrupted persisted XML never
 * reaches the HSB blender or applier with a value outside its contract.
 */
class AyuIslandsStateVcsTest {
    private fun freshState(): AyuIslandsState = AyuIslandsState()

    @Test
    fun `default state has VCS disabled and all three sections on Ambient`() {
        val state = freshState()
        assertFalse(state.vcsColorEnabled, "Master kill-switch defaults off (byte-identical to prior release)")
        assertEquals(VcsColorPreset.AMBIENT, state.effectiveVcsPresetFor(VcsColorCategory.DIFF_VIEWER))
        assertEquals(VcsColorPreset.AMBIENT, state.effectiveVcsPresetFor(VcsColorCategory.CONFLICT_MARKERS))
        assertEquals(VcsColorPreset.AMBIENT, state.effectiveVcsPresetFor(VcsColorCategory.BLAME_GUTTER))
        // Master off => effective intensity is always 0, regardless of preset.
        for (category in VcsColorCategory.entries) {
            assertEquals(0, state.effectiveVcsIntensityFor(category).percent)
        }
    }

    @Test
    fun `each section preset resolves its persisted enum name independently`() {
        val state = freshState()
        state.vcsDiffPreset = VcsColorPreset.NEON.name
        state.vcsMergePreset = VcsColorPreset.WHISPER.name
        state.vcsBlamePreset = VcsColorPreset.CYBERPUNK.name
        assertEquals(VcsColorPreset.NEON, state.effectiveVcsPresetFor(VcsColorCategory.DIFF_VIEWER))
        assertEquals(VcsColorPreset.WHISPER, state.effectiveVcsPresetFor(VcsColorCategory.CONFLICT_MARKERS))
        assertEquals(VcsColorPreset.CYBERPUNK, state.effectiveVcsPresetFor(VcsColorCategory.BLAME_GUTTER))
    }

    @Test
    fun `each section preset falls back to Ambient on unknown persisted string`() {
        // Hand-edited / migrated XML scenario: persisted value isn't an enum name.
        // Ambient is the no-op default, keeping a corrupted XML from accidentally
        // tinting surfaces the user never opted into.
        val state = freshState()
        state.vcsDiffPreset = "GARBAGE"
        state.vcsMergePreset = ""
        state.vcsBlamePreset = "UNKNOWN_PRESET_NAME"
        assertEquals(VcsColorPreset.AMBIENT, state.effectiveVcsPresetFor(VcsColorCategory.DIFF_VIEWER))
        assertEquals(VcsColorPreset.AMBIENT, state.effectiveVcsPresetFor(VcsColorCategory.CONFLICT_MARKERS))
        assertEquals(VcsColorPreset.AMBIENT, state.effectiveVcsPresetFor(VcsColorCategory.BLAME_GUTTER))
    }

    @Test
    fun `effectiveVcsPresetFor routes categories to their owning section`() {
        val state = freshState()
        state.vcsDiffPreset = VcsColorPreset.NEON.name
        state.vcsMergePreset = VcsColorPreset.WHISPER.name
        state.vcsBlamePreset = VcsColorPreset.CYBERPUNK.name
        assertEquals(VcsColorPreset.NEON, state.effectiveVcsPresetFor(VcsColorCategory.DIFF_VIEWER))
        assertEquals(VcsColorPreset.NEON, state.effectiveVcsPresetFor(VcsColorCategory.PROJECT_VIEW_FILE_STATUS))
        assertEquals(VcsColorPreset.NEON, state.effectiveVcsPresetFor(VcsColorCategory.EDITOR_GUTTER))
        assertEquals(VcsColorPreset.WHISPER, state.effectiveVcsPresetFor(VcsColorCategory.CONFLICT_MARKERS))
        assertEquals(VcsColorPreset.CYBERPUNK, state.effectiveVcsPresetFor(VcsColorCategory.BLAME_GUTTER))
    }

    @Test
    fun `effectiveVcsIntensityFor returns zero when master toggle is off regardless of any preset`() {
        val state = freshState()
        state.vcsDiffPreset = VcsColorPreset.CYBERPUNK.name
        state.vcsMergePreset = VcsColorPreset.CYBERPUNK.name
        state.vcsBlamePreset = VcsColorPreset.CYBERPUNK.name
        for (category in VcsColorCategory.entries) {
            assertEquals(0, state.effectiveVcsIntensityFor(category).percent)
        }
    }

    @Test
    fun `effectiveVcsIntensityFor maps each section's preset to its canonical slider value`() {
        val state = freshState()
        state.vcsColorEnabled = true
        state.vcsDiffPreset = VcsColorPreset.NEON.name
        state.vcsMergePreset = VcsColorPreset.WHISPER.name
        state.vcsBlamePreset = VcsColorPreset.CYBERPUNK.name

        // Diff section categories pick up NEON_SLIDER.
        assertEquals(
            VcsColorPreset.NEON_SLIDER,
            state.effectiveVcsIntensityFor(VcsColorCategory.DIFF_VIEWER).percent,
        )
        assertEquals(
            VcsColorPreset.NEON_SLIDER,
            state.effectiveVcsIntensityFor(VcsColorCategory.PROJECT_VIEW_FILE_STATUS).percent,
        )
        assertEquals(
            VcsColorPreset.NEON_SLIDER,
            state.effectiveVcsIntensityFor(VcsColorCategory.EDITOR_GUTTER).percent,
        )
        // Merge section picks up WHISPER_SLIDER.
        assertEquals(
            VcsColorPreset.WHISPER_SLIDER,
            state.effectiveVcsIntensityFor(VcsColorCategory.CONFLICT_MARKERS).percent,
        )
        // Blame section picks up CYBERPUNK_SLIDER.
        assertEquals(
            VcsColorPreset.CYBERPUNK_SLIDER,
            state.effectiveVcsIntensityFor(VcsColorCategory.BLAME_GUTTER).percent,
        )
    }

    @Test
    fun `effectiveVcsIntensityFor reads per-category property when section preset is Custom`() {
        val state = freshState()
        state.vcsColorEnabled = true
        state.vcsDiffPreset = VcsColorPreset.CUSTOM.name
        state.vcsBlamePreset = VcsColorPreset.CUSTOM.name
        state.vcsDiffIntensity = 75
        state.vcsBlameIntensity = 25
        assertEquals(75, state.effectiveVcsIntensityFor(VcsColorCategory.DIFF_VIEWER).percent)
        assertEquals(25, state.effectiveVcsIntensityFor(VcsColorCategory.BLAME_GUTTER).percent)
    }

    @Test
    fun `effectiveVcsIntensityFor clamps out-of-range per-category property in Custom mode`() {
        val state = freshState()
        state.vcsColorEnabled = true
        state.vcsDiffPreset = VcsColorPreset.CUSTOM.name
        state.vcsBlamePreset = VcsColorPreset.CUSTOM.name
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
