package dev.ayuislands.studio

import dev.ayuislands.settings.WorkspaceSurface
import dev.ayuislands.settings.WorkspaceToggle
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.session.StudioWorkspaceEditing
import dev.ayuislands.studio.session.StudioWorkspaceProjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioWorkspaceProjectionTest {
    @Test
    fun `each toggle selects only its own native surface`() {
        val fields = sessionDocument().shared.workspace
        for (toggle in WorkspaceToggle.entries) {
            val selection = StudioWorkspaceProjection.resolve(fields, selected(fields, toggle.field), true)

            assertEquals(setOf(toggle), selection.toggles)
            assertTrue(selection.widths.isEmpty())
            assertFalse(selection.isCommitRendererSelected)
            assertEquals(StudioWorkspaceEditing.settings(fields), selection.settings)
        }
    }

    @Test
    fun `width parameters do not replay sibling widths or unrelated path rendering`() {
        val fields = sessionDocument().shared.workspace
        for (surface in WorkspaceSurface.entries) {
            for (name in listOf(surface.modeField, surface.minimumField, surface.maximumField, surface.fixedField)) {
                val selection = StudioWorkspaceProjection.resolve(fields, selected(fields, name), true)

                assertEquals(setOf(surface), selection.widths, name)
                assertTrue(selection.toggles.isEmpty(), name)
                assertEquals(
                    surface == WorkspaceSurface.COMMIT && name == surface.modeField,
                    selection.isCommitRendererSelected,
                    name,
                )
            }
            surface.legacyField?.let { name ->
                val selection = StudioWorkspaceProjection.resolve(fields, selected(fields, name), true)
                assertTrue(selection.widths.isEmpty())
                assertFalse(selection.isCommitRendererSelected)
            }
        }
    }

    @Test
    fun `path edits select rendering without moving Commit geometry`() {
        val fields = sessionDocument().shared.workspace
        for (name in listOf(
            "commitPanelPathDisplayMode",
            "commitPanelPathMinHiddenLevels",
            "commitPanelPathMaxHiddenLevels",
        )) {
            val selection = StudioWorkspaceProjection.resolve(fields, selected(fields, name), true)

            assertTrue(selection.isCommitRendererSelected)
            assertTrue(selection.toggles.isEmpty())
            assertTrue(selection.widths.isEmpty())
        }
    }

    @Test
    fun `entitlement gates effective values without replacing raw modes or dormant settings`() {
        val fields =
            values(
                mapOf(
                    "hideEditorVScrollbar" to RawValue.BooleanValue(true),
                    "hideEditorHScrollbar" to RawValue.BooleanValue(true),
                    "hideProjectRootPath" to RawValue.BooleanValue(true),
                    "hideProjectViewHScrollbar" to RawValue.BooleanValue(true),
                    "projectPanelWidthMode" to RawValue.Text("FUTURE_MODE"),
                    "commitPanelWidthMode" to RawValue.Text("AUTO_FIT"),
                    "gitPanelWidthMode" to RawValue.Text("FIXED"),
                    "gitPanelFixedWidth" to RawValue.Integer(637),
                    "commitPanelPathDisplayMode" to RawValue.Text("FUTURE_PATHS"),
                    "commitPanelPathMinHiddenLevels" to RawValue.Integer(4),
                    "commitPanelPathMaxHiddenLevels" to RawValue.Integer(11),
                ),
            )
        val requested = StudioWorkspaceEditing.settings(fields)
        val addresses = fields.records.map { FieldAddress(it.key) }
        val gated = StudioWorkspaceProjection.resolve(fields, addresses, false)
        val enabled = StudioWorkspaceProjection.resolve(fields, addresses, true)

        assertEquals(requested, enabled.settings)
        assertEquals(WorkspaceToggle.entries.toSet(), gated.toggles)
        assertEquals(WorkspaceSurface.entries.toSet(), gated.widths)
        assertTrue(gated.isCommitRendererSelected)
        WorkspaceToggle.entries.forEach { assertFalse(gated.settings.isHidden(it)) }
        WorkspaceSurface.entries.forEach { surface ->
            assertEquals(requested.width(surface).copy(mode = "DEFAULT"), gated.settings.width(surface))
        }
        assertEquals(requested.paths, gated.settings.paths)
        assertEquals(requested, StudioWorkspaceEditing.settings(fields))
    }

    @Test
    fun `a selected address must belong to a complete Workspace field`() {
        val document = sessionDocument()
        val fields = document.shared.workspace
        val unrelated =
            document.shared.system.records
                .first()
                .key
        val partial = FieldAddress(fields.records.first().key, FieldPart.MapEntry("unrelated"))

        assertFailsWith<IllegalArgumentException> {
            StudioWorkspaceProjection.resolve(fields, listOf(FieldAddress(unrelated)), true)
        }
        assertFailsWith<IllegalArgumentException> {
            StudioWorkspaceProjection.resolve(fields, listOf(partial), true)
        }
    }

    private fun selected(
        fields: StudioFields,
        name: String,
    ): List<FieldAddress> = listOf(FieldAddress(fields.records.single { it.key.name == name }.key))

    private fun values(changes: Map<String, RawValue>): StudioFields {
        val fields = sessionDocument().shared.workspace
        return fields.copy(
            records = StudioList(fields.records.map { it.copy(value = changes[it.key.name] ?: it.value) }),
        )
    }
}
