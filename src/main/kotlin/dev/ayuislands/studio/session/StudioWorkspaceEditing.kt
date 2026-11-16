package dev.ayuislands.studio.session

import dev.ayuislands.settings.WorkspacePanelEvent
import dev.ayuislands.settings.WorkspacePathField
import dev.ayuislands.settings.WorkspacePaths
import dev.ayuislands.settings.WorkspaceSettings
import dev.ayuislands.settings.WorkspaceSurface
import dev.ayuislands.settings.WorkspaceToggle
import dev.ayuislands.settings.WorkspaceWidth
import dev.ayuislands.studio.fields.StudioFieldEdits
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.StudioField
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList

/** Project shared working values and emit only fields changed by an explicit Workspace action. */
internal object StudioWorkspaceEditing {
    fun settings(fields: StudioFields): WorkspaceSettings =
        WorkspaceSettings(
            hideEditorVertical = fields.boolean(WorkspaceToggle.EDITOR_VERTICAL_SCROLLBAR.field),
            hideEditorHorizontal = fields.boolean(WorkspaceToggle.EDITOR_HORIZONTAL_SCROLLBAR.field),
            hideProjectRootPath = fields.boolean(WorkspaceToggle.PROJECT_ROOT_PATH.field),
            hideProjectHorizontal = fields.boolean(WorkspaceToggle.PROJECT_HORIZONTAL_SCROLLBAR.field),
            project = fields.width(WorkspaceSurface.PROJECT),
            commit = fields.width(WorkspaceSurface.COMMIT),
            git = fields.width(WorkspaceSurface.GIT),
            paths =
                WorkspacePaths(
                    fields.text(WorkspacePathField.MODE.field),
                    fields.integer(WorkspacePathField.MINIMUM.field),
                    fields.integer(WorkspacePathField.MAXIMUM.field),
                ),
        )

    fun edits(
        fields: StudioFields,
        event: WorkspacePanelEvent,
    ): StudioList<SharedIntent.Field> =
        StudioList(
            changes(settings(fields), event).mapNotNull { (name, value) ->
                val before = fields.field(name)
                val after = StudioFieldEdits.edit(before, FieldPart.Value, FieldValue.Present(value))
                if (before == after) null else SharedIntent.Field(before, after)
            },
        )

    private fun changes(
        settings: WorkspaceSettings,
        event: WorkspacePanelEvent,
    ): Map<String, RawValue> =
        when (event) {
            is WorkspacePanelEvent.Toggle -> {
                mapOf(event.target.field to RawValue.BooleanValue(event.value))
            }

            is WorkspacePanelEvent.Mode -> {
                mapOf(event.surface.modeField to RawValue.Text(event.value.name))
            }

            is WorkspacePanelEvent.Minimum -> {
                val width = settings.width(event.surface)
                bounds(
                    event.surface.minimumField,
                    event.value,
                    width.minimum,
                    event.surface.maximumField,
                    maxOf(event.value, width.maximum),
                )
            }

            is WorkspacePanelEvent.Maximum -> {
                val width = settings.width(event.surface)
                bounds(
                    event.surface.maximumField,
                    event.value,
                    width.maximum,
                    event.surface.minimumField,
                    minOf(event.value, width.minimum),
                )
            }

            is WorkspacePanelEvent.Fixed -> {
                mapOf(event.surface.fixedField to RawValue.Integer(event.value))
            }

            is WorkspacePanelEvent.PathMode -> {
                mapOf(WorkspacePathField.MODE.field to RawValue.Text(event.value.name))
            }

            is WorkspacePanelEvent.PathMinimum -> {
                bounds(
                    WorkspacePathField.MINIMUM.field,
                    event.value,
                    settings.paths.minimum,
                    WorkspacePathField.MAXIMUM.field,
                    maxOf(event.value, settings.paths.maximum),
                )
            }

            is WorkspacePanelEvent.PathMaximum -> {
                bounds(
                    WorkspacePathField.MAXIMUM.field,
                    event.value,
                    settings.paths.maximum,
                    WorkspacePathField.MINIMUM.field,
                    minOf(event.value, settings.paths.minimum),
                )
            }

            WorkspacePanelEvent.Unlock -> {
                emptyMap()
            }
        }

    private fun bounds(
        field: String,
        value: Int,
        previousValue: Int,
        sibling: String,
        siblingValue: Int,
    ): Map<String, RawValue> =
        if (value == previousValue) {
            emptyMap()
        } else {
            mapOf(field to RawValue.Integer(value), sibling to RawValue.Integer(siblingValue))
        }

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
