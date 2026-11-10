package dev.ayuislands.studio

import dev.ayuislands.settings.CommitPathDisplayMode
import dev.ayuislands.settings.PanelWidthMode
import dev.ayuislands.settings.WorkspacePanelEvent
import dev.ayuislands.settings.WorkspaceSurface
import dev.ayuislands.settings.WorkspaceToggle
import dev.ayuislands.studio.model.FieldOrigin
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.OpaqueRecord
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.SourcePresence
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioField
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.session.StudioWorkspaceEditing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioWorkspaceEditingTest {
    @Test
    fun `independent toggles preserve unknown modes dormant bounds ordering and source evidence`() {
        val original = fields()
        val cases =
            listOf(
                WorkspaceToggle.EDITOR_VERTICAL_SCROLLBAR to "hideEditorVScrollbar",
                WorkspaceToggle.EDITOR_HORIZONTAL_SCROLLBAR to "hideEditorHScrollbar",
                WorkspaceToggle.PROJECT_ROOT_PATH to "hideProjectRootPath",
                WorkspaceToggle.PROJECT_HORIZONTAL_SCROLLBAR to "hideProjectViewHScrollbar",
            )

        for ((target, name) in cases) {
            val changed = assertChanges(original, WorkspacePanelEvent.Toggle(target, true), mapOf(name to bool(true)))
            val restored = assertChanges(changed, WorkspacePanelEvent.Toggle(target, false), mapOf(name to bool(false)))
            assertEquals(original.records.map { it.key to it.value }, restored.records.map { it.key to it.value })
            assertEquals(original.extensions, restored.extensions)
        }
    }

    @Test
    fun `explicit width modes change only their own mode and corresponding historical flag`() {
        val original = fields()
        assertChanges(
            original,
            WorkspacePanelEvent.Mode(WorkspaceSurface.PROJECT, PanelWidthMode.AUTO_FIT),
            mapOf("projectPanelWidthMode" to text("AUTO_FIT"), "autoFitProjectPanelWidth" to bool(true)),
        )
        assertChanges(
            original,
            WorkspacePanelEvent.Mode(WorkspaceSurface.COMMIT, PanelWidthMode.DEFAULT),
            mapOf("commitPanelWidthMode" to text("DEFAULT"), "autoFitCommitPanelWidth" to bool(false)),
        )
        assertChanges(
            original,
            WorkspacePanelEvent.Mode(WorkspaceSurface.GIT, PanelWidthMode.FIXED),
            mapOf("gitPanelWidthMode" to text("FIXED")),
        )
    }

    @Test
    fun `width edits route all three surfaces without touching sibling panels or dormant modes`() {
        val original = fields()
        val cases =
            listOf(
                Triple(WorkspaceSurface.PROJECT, "projectPanelAutoFitMinWidth", "autoFitMaxWidth"),
                Triple(WorkspaceSurface.COMMIT, "commitPanelAutoFitMinWidth", "autoFitCommitMaxWidth"),
                Triple(WorkspaceSurface.GIT, "gitPanelAutoFitMinWidth", "gitPanelAutoFitMaxWidth"),
            )
        for ((surface, minimum, maximum) in cases) {
            assertChanges(original, WorkspacePanelEvent.Minimum(surface, 150), mapOf(minimum to number(150)))
            assertChanges(original, WorkspacePanelEvent.Maximum(surface, 550), mapOf(maximum to number(550)))
            assertChanges(
                original,
                WorkspacePanelEvent.Minimum(surface, 600),
                mapOf(minimum to number(600), maximum to number(600)),
            )
            assertChanges(
                original,
                WorkspacePanelEvent.Maximum(surface, 300),
                mapOf(minimum to number(300), maximum to number(300)),
            )
        }
        for ((surface, name) in listOf(
            WorkspaceSurface.PROJECT to "projectPanelFixedWidth",
            WorkspaceSurface.COMMIT to "commitPanelFixedWidth",
            WorkspaceSurface.GIT to "gitPanelFixedWidth",
        )) {
            assertChanges(original, WorkspacePanelEvent.Fixed(surface, 475), mapOf(name to number(475)))
        }
    }

    @Test
    fun `path visibility changes retain raw bounds and explicit bound edits alone correct their pair`() {
        val original = fields()
        val tooltip =
            assertChanges(
                original,
                WorkspacePanelEvent.PathMode(CommitPathDisplayMode.TOOLTIP),
                mapOf("commitPanelPathDisplayMode" to text("TOOLTIP")),
            )
        assertChanges(
            tooltip,
            WorkspacePanelEvent.PathMode(CommitPathDisplayMode.INLINE),
            mapOf("commitPanelPathDisplayMode" to text("INLINE")),
        )
        assertChanges(
            original,
            WorkspacePanelEvent.PathMinimum(7),
            mapOf("commitPanelPathMinHiddenLevels" to number(7), "commitPanelPathMaxHiddenLevels" to number(7)),
        )
        assertChanges(
            original,
            WorkspacePanelEvent.PathMaximum(2),
            mapOf("commitPanelPathMinHiddenLevels" to number(2), "commitPanelPathMaxHiddenLevels" to number(2)),
        )
        assertChanges(
            original,
            WorkspacePanelEvent.PathMinimum(1),
            mapOf("commitPanelPathMinHiddenLevels" to number(1)),
        )
        assertChanges(
            original,
            WorkspacePanelEvent.PathMaximum(8),
            mapOf("commitPanelPathMaxHiddenLevels" to number(8)),
        )
    }

    @Test
    fun `projection retains unknown and null enum names and out of range historical numbers`() {
        val original = fields()
        val view = StudioWorkspaceEditing.settings(original)

        assertEquals("FUTURE_PROJECT_WIDTH", view.project.mode)
        assertEquals(null, view.commit.mode)
        assertEquals("FUTURE_GIT_WIDTH", view.git.mode)
        assertEquals("FUTURE_PATH_DISPLAY", view.paths.mode)
        assertEquals(1199, view.project.fixed)
        assertEquals(-40, view.commit.fixed)
        assertEquals(917, view.git.fixed)
        assertEquals(3, view.paths.minimum)
        assertEquals(5, view.paths.maximum)
    }

    @Test
    fun `unchanged values and unlock actions produce no persistence intent`() {
        val original = fields()
        assertTrue(StudioWorkspaceEditing.edits(original, WorkspacePanelEvent.Unlock).isEmpty())
        assertTrue(
            StudioWorkspaceEditing
                .edits(
                    original,
                    WorkspacePanelEvent.Toggle(WorkspaceToggle.EDITOR_VERTICAL_SCROLLBAR, false),
                ).isEmpty(),
        )
    }

    private fun assertChanges(
        original: StudioFields,
        event: WorkspacePanelEvent,
        expected: Map<String, RawValue>,
    ): StudioFields {
        val changes =
            StudioWorkspaceEditing.edits(original, event).map { intent ->
                assertIs<SharedIntent.Field>(assertIs<SessionIntent.Shared>(intent).intent)
            }
        assertEquals(expected, changes.associate { it.after.key.name to it.after.value })
        assertEquals(expected.size, changes.size)
        for ((before, after) in changes) {
            assertEquals(original.records.single { it.key == before.key }, before)
            assertEquals(before.key, after.key)
            assertEquals(FieldOrigin(SourcePresence.VALUE), after.origin)
        }
        val actual =
            original.copy(
                records =
                    StudioList(
                        original.records.map { record ->
                            changes.singleOrNull { it.before.key == record.key }?.after
                                ?: record
                        },
                    ),
            )
        val wanted =
            original.copy(
                records =
                    StudioList(
                        original.records.map { record ->
                            expected[record.key.name]?.let {
                                record.copy(
                                    value = it,
                                    origin = FieldOrigin(SourcePresence.VALUE),
                                )
                            }
                                ?: record
                        },
                    ),
            )
        assertEquals(wanted, actual)
        return actual
    }

    private fun fields(): StudioFields {
        val values =
            linkedMapOf(
                "hideProjectRootPath" to bool(false),
                "hideProjectViewHScrollbar" to bool(false),
                "hideEditorVScrollbar" to bool(false),
                "hideEditorHScrollbar" to bool(false),
                "autoFitProjectPanelWidth" to bool(false),
                "autoFitCommitPanelWidth" to bool(true),
                "autoFitMaxWidth" to number(500),
                "autoFitCommitMaxWidth" to number(500),
                "projectPanelAutoFitMinWidth" to number(350),
                "projectPanelFixedWidth" to number(1199),
                "commitPanelAutoFitMinWidth" to number(350),
                "commitPanelFixedWidth" to number(-40),
                "commitPanelPathMinHiddenLevels" to number(3),
                "commitPanelPathMaxHiddenLevels" to number(5),
                "gitPanelAutoFitMaxWidth" to number(500),
                "gitPanelAutoFitMinWidth" to number(350),
                "gitPanelFixedWidth" to number(917),
                "projectPanelWidthMode" to text("FUTURE_PROJECT_WIDTH"),
                "commitPanelWidthMode" to RawValue.Null,
                "commitPanelPathDisplayMode" to text("FUTURE_PATH_DISPLAY"),
                "gitPanelWidthMode" to text("FUTURE_GIT_WIDTH"),
            )
        return StudioFields(
            StudioList(
                values.map { (name, value) ->
                    StudioField(
                        LegacyFieldKey(LegacyStore.SETTINGS, name),
                        value,
                        FieldOrigin(
                            if (value == RawValue.Null) SourcePresence.NULL else SourcePresence.VALUE,
                            StudioBytes("exact source for $name".encodeToByteArray()),
                        ),
                    )
                },
            ),
            StudioList(listOf(OpaqueRecord("future-workspace", StudioBytes(byteArrayOf(0, 7, -1, 4))))),
        )
    }

    private fun text(value: String): RawValue = RawValue.Text(value)

    private fun bool(value: Boolean): RawValue = RawValue.BooleanValue(value)

    private fun number(value: Int): RawValue = RawValue.Integer(value)
}
