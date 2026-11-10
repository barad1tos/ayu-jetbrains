package dev.ayuislands.studio.session

import dev.ayuislands.settings.PanelWidthMode
import dev.ayuislands.settings.WorkspacePanelEvent
import dev.ayuislands.settings.WorkspacePaths
import dev.ayuislands.settings.WorkspaceSettings
import dev.ayuislands.settings.WorkspaceSurface
import dev.ayuislands.settings.WorkspaceWidth
import dev.ayuislands.studio.fields.StudioFieldEdits
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.StudioField
import dev.ayuislands.studio.model.StudioFields

/** Project shared working values and emit only fields changed by an explicit Workspace action. */
internal object StudioWorkspaceEditing {
    fun settings(fields: StudioFields): WorkspaceSettings =
        WorkspaceSettings(
            hideEditorVertical = fields.boolean("hideEditorVScrollbar"),
            hideEditorHorizontal = fields.boolean("hideEditorHScrollbar"),
            hideProjectRootPath = fields.boolean("hideProjectRootPath"),
            hideProjectHorizontal = fields.boolean("hideProjectViewHScrollbar"),
            project = fields.width(WorkspaceSurface.PROJECT),
            commit = fields.width(WorkspaceSurface.COMMIT),
            git = fields.width(WorkspaceSurface.GIT),
            paths =
                WorkspacePaths(
                    fields.text("commitPanelPathDisplayMode"),
                    fields.integer("commitPanelPathMinHiddenLevels"),
                    fields.integer("commitPanelPathMaxHiddenLevels"),
                ),
        )

    fun edits(
        fields: StudioFields,
        event: WorkspacePanelEvent,
    ): List<SessionIntent> =
        changes(settings(fields), event).mapNotNull { (name, value) ->
            val before = fields.field(name)
            val after = StudioFieldEdits.edit(before, FieldPart.Value, FieldValue.Present(value))
            if (before == after) null else SessionIntent.Shared(SharedIntent.Field(before, after))
        }

    private fun changes(
        settings: WorkspaceSettings,
        event: WorkspacePanelEvent,
    ): Map<String, RawValue> =
        when (event) {
            is WorkspacePanelEvent.Toggle -> {
                mapOf(event.target.field to RawValue.BooleanValue(event.value))
            }

            is WorkspacePanelEvent.Mode -> {
                modeChanges(event)
            }

            is WorkspacePanelEvent.Minimum -> {
                bounds(
                    event.surface.minimumField,
                    event.value,
                    event.surface.maximumField,
                    maxOf(event.value, settings.width(event.surface).maximum),
                )
            }

            is WorkspacePanelEvent.Maximum -> {
                bounds(
                    event.surface.maximumField,
                    event.value,
                    event.surface.minimumField,
                    minOf(event.value, settings.width(event.surface).minimum),
                )
            }

            is WorkspacePanelEvent.Fixed -> {
                mapOf(event.surface.fixedField to RawValue.Integer(event.value))
            }

            is WorkspacePanelEvent.PathMode -> {
                mapOf("commitPanelPathDisplayMode" to RawValue.Text(event.value.name))
            }

            is WorkspacePanelEvent.PathMinimum -> {
                bounds(
                    "commitPanelPathMinHiddenLevels",
                    event.value,
                    "commitPanelPathMaxHiddenLevels",
                    maxOf(event.value, settings.paths.maximum),
                )
            }

            is WorkspacePanelEvent.PathMaximum -> {
                bounds(
                    "commitPanelPathMaxHiddenLevels",
                    event.value,
                    "commitPanelPathMinHiddenLevels",
                    minOf(event.value, settings.paths.minimum),
                )
            }

            WorkspacePanelEvent.Unlock -> {
                emptyMap()
            }
        }

    private fun modeChanges(event: WorkspacePanelEvent.Mode): Map<String, RawValue> =
        buildMap {
            put(event.surface.modeField, RawValue.Text(event.value.name))
            event.surface.legacyField?.let { put(it, RawValue.BooleanValue(event.value == PanelWidthMode.AUTO_FIT)) }
        }

    private fun bounds(
        changed: String,
        value: Int,
        sibling: String,
        siblingValue: Int,
    ): Map<String, RawValue> = mapOf(changed to RawValue.Integer(value), sibling to RawValue.Integer(siblingValue))

    private fun StudioFields.width(surface: WorkspaceSurface): WorkspaceWidth =
        WorkspaceWidth(
            text(surface.modeField),
            integer(surface.minimumField),
            integer(surface.maximumField),
            integer(surface.fixedField),
        )

    private fun StudioFields.field(name: String): StudioField = records.single { it.key.name == name }

    private fun StudioFields.text(name: String): String? =
        when (val value = field(name).value) {
            RawValue.Null -> null
            else -> (value as RawValue.Text).value
        }

    private fun StudioFields.integer(name: String): Int = (field(name).value as RawValue.Integer).value

    private fun StudioFields.boolean(name: String): Boolean = (field(name).value as RawValue.BooleanValue).value
}
