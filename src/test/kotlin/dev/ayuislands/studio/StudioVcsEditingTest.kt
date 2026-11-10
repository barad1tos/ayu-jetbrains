package dev.ayuislands.studio

import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.VcsPanelEvent
import dev.ayuislands.settings.VcsSection
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.FieldOrigin
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SourcePresence
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.session.StudioDraftPolicy
import dev.ayuislands.studio.session.StudioVcsEditing
import dev.ayuislands.studio.session.StudioVcsProjection
import dev.ayuislands.vcs.VcsColorCategory
import dev.ayuislands.vcs.VcsColorPreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioVcsEditingTest {
    @Test
    fun `raw view retains all eleven values and nullable unknown preset names without changing appearance`() {
        val initial = draft()
        val before = initial.appearance
        val view = StudioVcsProjection.settings(before)
        assertTrue(view.enabled)
        assertEquals("FUTURE_DIFF", view.diffPreset)
        assertEquals(null, view.mergePreset)
        assertEquals("CUSTOM", view.blamePreset)
        assertEquals(
            mapOf(
                VcsColorCategory.DIFF_VIEWER to -19,
                VcsColorCategory.PROJECT_VIEW_FILE_STATUS to 128,
                VcsColorCategory.EDITOR_GUTTER to 37,
                VcsColorCategory.CONFLICT_MARKERS to 46,
                VcsColorCategory.MERGE_3WAY to 63,
                VcsColorCategory.INLINE_DIFF_POPUP to 74,
                VcsColorCategory.BLAME_GUTTER to 85,
                VcsColorCategory.LOCAL_HISTORY to 96,
                VcsColorCategory.BRANCH_INDICATOR to -311,
                VcsColorCategory.BRANCHES_POPUP to 507,
                VcsColorCategory.COMMIT_HIGHLIGHTS to -219,
            ),
            view.intensities,
        )
        assertEquals(before, initial.appearance)
    }

    @Test
    fun `master off and on changes only the gate and restores original metadata`() {
        val initial = draft()
        val disabled = edit(initial, VcsPanelEvent.Enabled(false))
        assertAppearance(initial, disabled, mapOf("vcsColorEnabled" to RawValue.BooleanValue(false)))
        val restored = edit(disabled, VcsPanelEvent.Enabled(true))
        assertEquals(initial.appearance, restored.appearance)
        assertTrue(restored.tracking.touched.isEmpty())
    }

    @Test
    fun `each named preset replaces only its own exposed memories and preserves hidden categories`() {
        val initial = draft()
        val changes =
            mapOf(
                VcsSection.DIFF to
                    mapOf(
                        "vcsDiffPreset" to RawValue.Text("NEON"),
                        "vcsDiffIntensity" to
                            RawValue.Integer(VcsColorPreset.NEON.intensityFor(VcsColorCategory.DIFF_VIEWER)),
                        "vcsProjectViewIntensity" to
                            RawValue.Integer(
                                VcsColorPreset.NEON.intensityFor(VcsColorCategory.PROJECT_VIEW_FILE_STATUS),
                            ),
                        "vcsGutterIntensity" to
                            RawValue.Integer(VcsColorPreset.NEON.intensityFor(VcsColorCategory.EDITOR_GUTTER)),
                    ),
                VcsSection.MERGE to
                    mapOf(
                        "vcsMergePreset" to RawValue.Text("NEON"),
                        "vcsConflictMarkerIntensity" to
                            RawValue.Integer(VcsColorPreset.NEON.intensityFor(VcsColorCategory.CONFLICT_MARKERS)),
                    ),
                VcsSection.BLAME to
                    mapOf(
                        "vcsBlamePreset" to RawValue.Text("NEON"),
                        "vcsBlameIntensity" to
                            RawValue.Integer(VcsColorPreset.NEON.intensityFor(VcsColorCategory.BLAME_GUTTER)),
                    ),
            )
        for ((section, expected) in changes) {
            assertAppearance(initial, edit(initial, VcsPanelEvent.Preset(section, VcsColorPreset.NEON)), expected)
        }
    }

    @Test
    fun `Custom changes the section name while retaining every raw remembered intensity`() {
        val initial = draft()
        val changed = edit(initial, VcsPanelEvent.Preset(VcsSection.DIFF, VcsColorPreset.CUSTOM))
        assertAppearance(initial, changed, mapOf("vcsDiffPreset" to RawValue.Text("CUSTOM")))
        assertEquals(
            StudioVcsProjection.settings(initial.appearance).intensities,
            StudioVcsProjection.settings(changed.appearance).intensities,
        )
    }

    @Test
    fun `slider gestures promote only the owning section and retain sibling slider memories`() {
        val initial = draft()
        val changed = edit(initial, VcsPanelEvent.Intensity(VcsColorCategory.EDITOR_GUTTER, 57))
        assertAppearance(
            initial,
            changed,
            mapOf("vcsDiffPreset" to RawValue.Text("CUSTOM"), "vcsGutterIntensity" to RawValue.Integer(57)),
        )
        val merge = edit(initial, VcsPanelEvent.Intensity(VcsColorCategory.CONFLICT_MARKERS, 31))
        assertAppearance(
            initial,
            merge,
            mapOf("vcsMergePreset" to RawValue.Text("CUSTOM"), "vcsConflictMarkerIntensity" to RawValue.Integer(31)),
        )
    }

    @Test
    fun `expansion and license actions never create appearance edits`() {
        val initial = draft()
        for (event in listOf(VcsPanelEvent.Expanded(VcsSection.MERGE, true), VcsPanelEvent.Unlock)) {
            assertTrue(StudioVcsEditing.edits(initial, event).isEmpty())
        }
    }

    private fun draft(): DraftRecord {
        val settings =
            AyuIslandsState().apply {
                vcsColorEnabled = true
                vcsDiffPreset = "FUTURE_DIFF"
                vcsMergePreset = null
                vcsBlamePreset = "CUSTOM"
                vcsDiffIntensity = -19
                vcsProjectViewIntensity = 128
                vcsGutterIntensity = 37
                vcsConflictMarkerIntensity = 46
                vcsMerge3WayIntensity = 63
                vcsInlineDiffIntensity = 74
                vcsBlameIntensity = 85
                vcsLocalHistoryIntensity = 96
                vcsBranchIndicatorIntensity = -311
                vcsBranchesPopupIntensity = 507
                vcsCommitHighlightIntensity = -219
            }
        val document =
            assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(StudioFixtures.capture(settings))).document
        return sessionRecord(document).work.drafts.single()
    }

    private fun edit(
        initial: DraftRecord,
        event: VcsPanelEvent,
    ): DraftRecord =
        StudioVcsEditing.edits(initial, event).fold(initial) { current, intent ->
            val field = assertIs<SessionIntent.EditField>(intent)
            assertEquals(initial.id, field.draft)
            StudioDraftPolicy.edit(current, field.address, field.value)
        }

    private fun assertAppearance(
        before: DraftRecord,
        after: DraftRecord,
        changes: Map<String, RawValue>,
    ) {
        val expected =
            before.appearance.vcs.copy(
                records =
                    StudioList(
                        before.appearance.vcs.records.map { field ->
                            val requested = changes[field.key.name]
                            if (requested == null || requested == field.value) {
                                field
                            } else {
                                field.copy(value = requested, origin = FieldOrigin(SourcePresence.VALUE))
                            }
                        },
                    ),
            )
        assertEquals(before.appearance.copy(vcs = expected), after.appearance)
    }
}
