package dev.ayuislands.studio

import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.freshSettingsState
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.persistence.DecodeResult
import dev.ayuislands.studio.persistence.StudioStateCodec
import dev.ayuislands.studio.session.StudioVcsProjection
import dev.ayuislands.vcs.VcsColorCategory
import dev.ayuislands.vcs.VcsColorPreset
import dev.ayuislands.vcs.VcsColorSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StudioVcsProjectionTest {
    @Test
    fun `independent section presets match every legacy runtime category`() {
        for (diff in VcsColorPreset.entries) {
            for (merge in VcsColorPreset.entries) {
                for (blame in VcsColorPreset.entries) {
                    assertLegacyProjection(
                        customizedState().apply {
                            vcsDiffPreset = diff.name
                            vcsMergePreset = merge.name
                            vcsBlamePreset = blame.name
                        },
                    )
                }
            }
        }
    }

    @Test
    fun `disable and preset round trips retain dormant and hidden intensities`() {
        val state = customizedState()
        val enabled = assertLegacyProjection(state)
        assertEquals(63, enabled.intensityFor(VcsColorCategory.MERGE_3WAY).percent)
        assertEquals(74, enabled.intensityFor(VcsColorCategory.INLINE_DIFF_POPUP).percent)
        assertEquals(96, enabled.intensityFor(VcsColorCategory.LOCAL_HISTORY).percent)
        assertEquals(33, enabled.intensityFor(VcsColorCategory.BRANCH_INDICATOR).percent)

        state.vcsColorEnabled = false
        assertEquals(
            VcsColorSnapshot(false, VcsColorCategory.entries.associateWith { 0 }),
            assertLegacyProjection(state),
        )
        state.vcsColorEnabled = true
        assertEquals(enabled, assertLegacyProjection(state))

        state.vcsMergePreset = VcsColorPreset.WHISPER.name
        assertLegacyProjection(state)
        state.vcsMergePreset = VcsColorPreset.CUSTOM.name
        assertEquals(enabled, assertLegacyProjection(state))
    }

    @Test
    fun `unknown presets and out of range values remain raw through reload`() {
        for (preset in listOf(null, "", "FUTURE_PRESET")) {
            assertLegacyProjection(
                customizedState().apply {
                    vcsDiffPreset = preset
                    vcsMergePreset = preset
                    vcsBlamePreset = preset
                },
            )
        }
        assertLegacyProjection(
            customizedState().apply {
                vcsDiffIntensity = Int.MIN_VALUE
                vcsProjectViewIntensity = Int.MAX_VALUE
                vcsGutterIntensity = -1
                vcsConflictMarkerIntensity = 101
                vcsMerge3WayIntensity = Int.MAX_VALUE
                vcsInlineDiffIntensity = Int.MIN_VALUE
                vcsBlameIntensity = 100
                vcsLocalHistoryIntensity = 0
                vcsBranchIndicatorIntensity = -311
                vcsBranchesPopupIntensity = 507
                vcsCommitHighlightIntensity = -219
            },
        )
    }

    @Test
    fun `legacy and fresh defaults keep their original runtime meaning`() {
        assertLegacyProjection(AyuIslandsState())
        assertLegacyProjection(freshSettingsState())
    }

    private fun assertLegacyProjection(state: AyuIslandsState): VcsColorSnapshot {
        val capture = StudioFixtures.capture(state)
        val document = assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(capture)).document
        val appearance = document.currents.global.appearance
        val rawBefore = appearance.vcs.records.toList()
        val projected = StudioVcsProjection.snapshot(appearance)
        assertEquals(VcsColorSnapshot.fromState(state), projected)
        assertEquals(VcsColorCategory.entries.toSet(), projected.perCategoryIntensities.keys)
        for (category in VcsColorCategory.entries) {
            assertEquals(state.effectiveVcsIntensityFor(category), projected.intensityFor(category), category.name)
        }
        assertEquals(rawBefore, appearance.vcs.records.toList())
        assertEquals(capture.fields, StudioFixtures.fields(state))
        val reopened = assertIs<DecodeResult.Ready>(StudioStateCodec.decode(StudioStateCodec.encode(document)))
        assertEquals(document, reopened.document)
        assertEquals(projected, StudioVcsProjection.snapshot(reopened.document.currents.global.appearance))
        return projected
    }

    private fun customizedState(): AyuIslandsState =
        AyuIslandsState().apply {
            vcsColorEnabled = true
            vcsDiffPreset = VcsColorPreset.CUSTOM.name
            vcsMergePreset = VcsColorPreset.CUSTOM.name
            vcsBlamePreset = VcsColorPreset.CUSTOM.name
            vcsDiffIntensity = 19
            vcsProjectViewIntensity = 28
            vcsGutterIntensity = 37
            vcsConflictMarkerIntensity = 46
            vcsMerge3WayIntensity = 63
            vcsInlineDiffIntensity = 74
            vcsBlameIntensity = 85
            vcsLocalHistoryIntensity = 96
            vcsBranchIndicatorIntensity = 11
            vcsBranchesPopupIntensity = 22
            vcsCommitHighlightIntensity = 44
            vcsDiffSectionExpanded = false
            vcsMergeSectionExpanded = true
            vcsBlameSectionExpanded = true
        }
}
