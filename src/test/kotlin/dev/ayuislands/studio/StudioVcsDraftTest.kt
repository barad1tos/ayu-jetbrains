package dev.ayuislands.studio

import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.VcsSection
import dev.ayuislands.settings.freshSettingsState
import dev.ayuislands.settings.mappings.AccentMappingsState
import dev.ayuislands.studio.migration.LegacySnapshot
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.LegacyArchive
import dev.ayuislands.studio.model.LegacyFile
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.MigrationSource
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioVcsEdit
import dev.ayuislands.studio.persistence.DecodeResult
import dev.ayuislands.studio.persistence.StudioLegacyFields
import dev.ayuislands.studio.persistence.StudioStateCodec
import dev.ayuislands.studio.profiles.StudioFactoryProfiles
import dev.ayuislands.studio.session.StudioDraftPolicy
import dev.ayuislands.studio.session.StudioVcsEditing
import dev.ayuislands.studio.session.StudioVcsProjection
import dev.ayuislands.syntax.SyntaxIntensityBaseState
import dev.ayuislands.vcs.VcsColorCategory
import dev.ayuislands.vcs.VcsColorPreset
import dev.ayuislands.vcs.VcsColorSnapshot
import dev.ayuislands.vcs.VcsIntensity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class StudioVcsDraftTest {
    @Test
    fun `migration and reload retain raw VCS settings independently of effective colors`() {
        val legacy = customizedState().apply { vcsDiffPreset = "FUTURE_PRESET" }
        val document = migrate(legacy)
        val appearance = document.currents.global.appearance
        val settings = StudioVcsProjection.settings(appearance)
        val snapshot = StudioVcsProjection.snapshot(appearance)

        assertEquals("FUTURE_PRESET", settings.presets.getValue(VcsSection.DIFF))
        assertEquals(-9, settings.intensities.getValue(VcsColorCategory.DIFF_VIEWER))
        assertEquals(111, settings.intensities.getValue(VcsColorCategory.LOCAL_HISTORY))
        assertEquals(33, snapshot.intensityFor(VcsColorCategory.DIFF_VIEWER).percent)
        assertEquals(63, snapshot.intensityFor(VcsColorCategory.MERGE_3WAY).percent)
        assertEquals(100, snapshot.intensityFor(VcsColorCategory.LOCAL_HISTORY).percent)
        assertEquals(33, snapshot.intensityFor(VcsColorCategory.BRANCH_INDICATOR).percent)
        assertEquals(VcsColorSnapshot.fromState(legacy), snapshot)
        val reloaded = assertIs<DecodeResult.Ready>(StudioStateCodec.decode(StudioStateCodec.encode(document)))
        assertEquals(appearance.vcs, reloaded.document.currents.global.appearance.vcs)

        legacy.vcsDiffPreset = null
        val missingPreset = migrate(legacy).currents.global.appearance
        assertNull(StudioVcsProjection.settings(missingPreset).presets.getValue(VcsSection.DIFF))
        assertEquals(snapshot, StudioVcsProjection.snapshot(missingPreset))
    }

    @Test
    fun `turning VCS off and back on preserves every stored field`() {
        val draft = catalogTestWork(migrate(customizedState())).drafts.single()
        val before = StudioVcsProjection.settings(draft.appearance)
        val disabled = StudioVcsEditing.edit(draft, StudioVcsEdit.Enable(false))
        assertEquals(before.copy(enabled = false), StudioVcsProjection.settings(disabled.appearance))
        val snapshot = StudioVcsProjection.snapshot(disabled.appearance)
        assertEquals(VcsColorCategory.entries.associateWith { 0 }, snapshot.perCategoryIntensities)
        val restored = StudioVcsEditing.edit(disabled, StudioVcsEdit.Enable(true))
        assertEquals(draft.appearance.vcs, restored.appearance.vcs)
        assertFalse(StudioDraftPolicy.isDirty(restored))
    }

    @Test
    fun `a named preset changes only its own visible sliders and keeps hidden memories`() {
        val draft = catalogTestWork(migrate(customizedState())).drafts.single()
        val before = StudioVcsProjection.settings(draft.appearance)
        for (group in VcsSection.entries) {
            val edited = StudioVcsEditing.edit(draft, StudioVcsEdit.Preset(group, VcsColorPreset.NEON))
            val expected =
                before.copy(
                    presets = before.presets + (group to "NEON"),
                    intensities = before.intensities + group.sliders.associate { (category, _) -> category to 67 },
                )
            assertEquals(expected, StudioVcsProjection.settings(edited.appearance))
            assertEquals(draft.appearance.glow, edited.appearance.glow)
        }
    }

    @Test
    fun `manual edit selects Custom only for its own group and leaves hidden categories unwritable`() {
        val legacy = customizedState().apply { vcsMergePreset = VcsColorPreset.NEON.name }
        val draft = catalogTestWork(migrate(legacy)).drafts.single()
        val before = StudioVcsProjection.settings(draft.appearance)
        val custom = StudioVcsEditing.edit(draft, StudioVcsEdit.Preset(VcsSection.MERGE, VcsColorPreset.CUSTOM))
        val requested = before.copy(presets = before.presets + (VcsSection.MERGE to "CUSTOM"))
        assertEquals(requested, StudioVcsProjection.settings(custom.appearance))
        val edit = StudioVcsEdit.Intensity(VcsColorCategory.CONFLICT_MARKERS, VcsIntensity.of(92))
        val edited = StudioVcsEditing.edit(draft, edit)
        assertEquals(
            requested.copy(intensities = requested.intensities + (VcsColorCategory.CONFLICT_MARKERS to 92)),
            StudioVcsProjection.settings(edited.appearance),
        )
        val hidden = StudioVcsEdit.Intensity(VcsColorCategory.MERGE_3WAY, VcsIntensity.of(92))
        assertEquals(edited, StudioVcsEditing.edit(edited, hidden))
    }

    private fun migrate(state: AyuIslandsState): StudioDocument {
        val archives = StudioList(LegacyStore.entries.map { LegacyArchive(it, LegacyFile.Absent) })
        return assertIs<MigrationResult.Ready>(
            StudioLegacyMigration.plan(
                LegacySnapshot(
                    MigrationSource("vcs", archives),
                    capture(state),
                    StudioFactoryProfiles.create(capture(freshSettingsState())),
                    "vcs-migration",
                ),
            ),
        ).document
    }

    private fun capture(state: AyuIslandsState) =
        StudioLegacyFields.capture(state, SyntaxIntensityBaseState(), AccentMappingsState(), origins = emptyMap())

    private fun customizedState(): AyuIslandsState =
        AyuIslandsState().apply {
            vcsColorEnabled = true
            vcsDiffPreset = VcsColorPreset.CUSTOM.name
            vcsMergePreset = VcsColorPreset.CUSTOM.name
            vcsBlamePreset = VcsColorPreset.CUSTOM.name
            vcsDiffIntensity = -9
            vcsConflictMarkerIntensity = 46
            vcsMerge3WayIntensity = 63
            vcsInlineDiffIntensity = 74
            vcsLocalHistoryIntensity = 111
            vcsBranchIndicatorIntensity = 11
        }
}
