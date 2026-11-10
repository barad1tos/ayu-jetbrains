package dev.ayuislands.licensing

import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.syntax.SyntaxIntensityBaseState
import dev.ayuislands.syntax.SyntaxPreset
import dev.ayuislands.vcs.VcsColorPreset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LicenseCheckerVcsRevokeTest {
    private lateinit var state: AyuIslandsState
    private lateinit var syntax: SyntaxIntensityBaseState

    @BeforeTest fun setUp() {
        state = AyuIslandsState()
        syntax = SyntaxIntensityBaseState()
    }

    // ---------- 11 intensity sliders ----------

    @Test
    fun `accepted license loss preserves all 11 VCS intensity fields`() {
        // Seed every per-category intensity to a non-default value so the revert
        // is observable. 80 is well outside `AMBIENT_SLIDER` and inside the
        // 0..100 slider range, so the field accepts the write.
        val seed = 80
        state.vcsDiffIntensity = seed
        state.vcsProjectViewIntensity = seed
        state.vcsGutterIntensity = seed
        state.vcsConflictMarkerIntensity = seed
        state.vcsMerge3WayIntensity = seed
        state.vcsInlineDiffIntensity = seed
        state.vcsBlameIntensity = seed
        state.vcsLocalHistoryIntensity = seed
        state.vcsBranchIndicatorIntensity = seed
        state.vcsBranchesPopupIntensity = seed
        state.vcsCommitHighlightIntensity = seed

        val accepted = confirmUnlicensed(state).settings

        assertEquals(seed, accepted.vcsDiffIntensity, "vcsDiffIntensity")
        assertEquals(seed, accepted.vcsProjectViewIntensity, "vcsProjectViewIntensity")
        assertEquals(seed, accepted.vcsGutterIntensity, "vcsGutterIntensity")
        assertEquals(seed, accepted.vcsConflictMarkerIntensity, "vcsConflictMarkerIntensity")
        assertEquals(seed, accepted.vcsMerge3WayIntensity, "vcsMerge3WayIntensity")
        assertEquals(seed, accepted.vcsInlineDiffIntensity, "vcsInlineDiffIntensity")
        assertEquals(seed, accepted.vcsBlameIntensity, "vcsBlameIntensity")
        assertEquals(seed, accepted.vcsLocalHistoryIntensity, "vcsLocalHistoryIntensity")
        assertEquals(seed, accepted.vcsBranchIndicatorIntensity, "vcsBranchIndicatorIntensity")
        assertEquals(seed, accepted.vcsBranchesPopupIntensity, "vcsBranchesPopupIntensity")
        assertEquals(seed, accepted.vcsCommitHighlightIntensity, "vcsCommitHighlightIntensity")
    }

    // ---------- 3 preset names ----------

    @Test
    fun `accepted license loss preserves all 3 VCS preset names`() {
        // Seed each section preset to `NEON` so we can see the revert ran. Using
        // `VcsColorPreset.NEON.name` (round-tripped through `byName`) instead of
        // a raw literal protects the assertion from a future enum-name change.
        state.vcsDiffPreset = VcsColorPreset.NEON.name
        state.vcsMergePreset = VcsColorPreset.NEON.name
        state.vcsBlamePreset = VcsColorPreset.NEON.name

        val accepted = confirmUnlicensed(state).settings

        val expected = VcsColorPreset.NEON.name
        assertEquals(expected, accepted.vcsDiffPreset)
        assertEquals(expected, accepted.vcsMergePreset)
        assertEquals(expected, accepted.vcsBlamePreset)
    }

    // ---------- 1 master toggle ----------

    @Test
    fun `accepted license loss preserves vcsColorEnabled`() {
        state.vcsColorEnabled = true
        val accepted = confirmUnlicensed(state).settings
        assertTrue(accepted.vcsColorEnabled, "Runtime entitlement must not overwrite the saved toggle")
    }

    // ---------- 3 section-expanded flags ----------

    @Test
    fun `accepted license loss preserves section-expanded flags`() {
        // Seed all three to false so the diff flag has to flip back to true and
        // the merge/blame flags stay at false — exercises both branches of the
        // expanded-default contract.
        state.vcsDiffSectionExpanded = false
        state.vcsMergeSectionExpanded = false
        state.vcsBlameSectionExpanded = false

        val accepted = confirmUnlicensed(state).settings

        assertFalse(accepted.vcsDiffSectionExpanded)
        assertFalse(accepted.vcsMergeSectionExpanded, "merge section collapses on downgrade")
        assertFalse(accepted.vcsBlameSectionExpanded, "blame section collapses on downgrade")
    }

    @Test
    fun `accepted license loss preserves syntax intensity state`() {
        // Break caught: license revocation must retain both the legacy replacement layer and additive emphasis layer.
        syntax.selectedPreset = SyntaxPreset.CUSTOM.name
        syntax.subordinatePreset = SyntaxPreset.NEON.name
        syntax.customOverrides["Java|KEYWORD"] = "85"
        syntax.customStyles["Java|KEYWORD"] = "BOLD"
        syntax.customEmphasis["Java|KEYWORD"] = "ITALIC"
        syntax.dimComments = true
        syntax.softenDocumentation = true
        syntax.quietOperators = true
        syntax.emphasizeDeclarations = true

        val accepted = confirmUnlicensed(state, syntax).syntax

        assertEquals(SyntaxPreset.CUSTOM.name, accepted.selectedPreset)
        assertEquals(SyntaxPreset.NEON.name, accepted.subordinatePreset)
        assertEquals("85", accepted.customOverrides["Java|KEYWORD"])
        assertEquals("BOLD", accepted.customStyles["Java|KEYWORD"])
        assertEquals("ITALIC", accepted.customEmphasis["Java|KEYWORD"])
        assertTrue(accepted.dimComments)
        assertTrue(accepted.softenDocumentation)
        assertTrue(accepted.quietOperators)
        assertTrue(accepted.emphasizeDeclarations)
    }
}
