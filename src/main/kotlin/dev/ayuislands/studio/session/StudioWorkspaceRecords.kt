package dev.ayuislands.studio.session

import dev.ayuislands.accent.AccentWriteState
import dev.ayuislands.commitpanel.CommitRendererRecord
import dev.ayuislands.editor.EditorScrollbarRecord
import dev.ayuislands.editor.EditorScrollbarSnapshot
import dev.ayuislands.editor.ScrollbarSize
import dev.ayuislands.gitpanel.GitListenerRecord
import dev.ayuislands.gitpanel.GitSplitterRecord
import dev.ayuislands.projectview.ProjectRootRendererRecord
import dev.ayuislands.projectview.ProjectViewScrollbarManager
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.session.StudioNativeEvidence.record
import dev.ayuislands.toolwindow.ToolWindowListenerRecord
import dev.ayuislands.toolwindow.ToolWindowWidthField
import dev.ayuislands.toolwindow.ToolWindowWidthRecord
import dev.ayuislands.toolwindow.ToolWindowWidthValue

internal data class WorkspaceNativeRecord(
    val resource: String,
    val field: WorkspaceNativeField,
    val before: RawValue,
    val planned: RawValue,
    val state: AccentWriteState,
)

/** Projects existing owner facts without resolving new platform targets or interpreting user preferences. */
internal object WorkspaceRecords {
    fun editor(
        records: List<EditorScrollbarRecord>,
        references: WorkspaceReferences,
    ): List<WorkspaceNativeRecord> =
        records.flatMap { entry ->
            val resource = references.token(entry.bar)
            listOf(
                WorkspaceNativeRecord(
                    resource,
                    WorkspaceNativeField.EDITOR_SIZE,
                    size(entry.before.preferredSize),
                    size(entry.planned.preferredSize),
                    entry.state,
                ),
                WorkspaceNativeRecord(
                    resource,
                    WorkspaceNativeField.EDITOR_BASELINE,
                    baseline(entry.before, references),
                    baseline(entry.planned, references),
                    entry.state,
                ),
            )
        }

    fun scrollbar(
        native: ProjectViewScrollbarManager.ScrollbarCheckpoint,
        references: WorkspaceReferences,
    ): List<WorkspaceNativeRecord> {
        val resource = references.token(native.scrollPane)
        return listOf(
            WorkspaceNativeRecord(
                resource,
                WorkspaceNativeField.PROJECT_SCROLLBAR_POLICY,
                RawValue.Integer(native.before.policy),
                RawValue.Integer(native.planned.policy),
                native.state,
            ),
            WorkspaceNativeRecord(
                resource,
                WorkspaceNativeField.PROJECT_SCROLLBAR_BASELINE,
                number(native.before.originalPolicy),
                number(native.planned.originalPolicy),
                native.state,
            ),
        )
    }

    fun renderer(
        entry: ProjectRootRendererRecord,
        references: WorkspaceReferences,
    ): WorkspaceNativeRecord =
        WorkspaceNativeRecord(
            references.token(entry.tree),
            WorkspaceNativeField.PROJECT_RENDERER,
            references.reference(entry.before),
            references.reference(entry.planned),
            entry.state,
        )

    fun renderer(
        entry: CommitRendererRecord,
        references: WorkspaceReferences,
    ): WorkspaceNativeRecord =
        WorkspaceNativeRecord(
            references.token(entry.tree),
            WorkspaceNativeField.COMMIT_RENDERER,
            references.reference(entry.before),
            references.reference(entry.planned),
            entry.state,
        )

    fun width(
        geometry: List<ToolWindowWidthRecord>,
        listeners: List<ToolWindowListenerRecord>,
        references: WorkspaceReferences,
    ): List<WorkspaceNativeRecord> =
        geometry.map { entry ->
            WorkspaceNativeRecord(
                references.token(entry.component),
                widthField(entry.field),
                widthValue(entry.before),
                widthValue(entry.planned),
                entry.state,
            )
        } +
            listeners.flatMap { entry ->
                val resource = references.token(entry.listener)
                listOf(
                    WorkspaceNativeRecord(
                        resource,
                        WorkspaceNativeField.WIDTH_LISTENER_ATTACHMENT,
                        RawValue.BooleanValue(entry.before.attached),
                        RawValue.BooleanValue(entry.planned.attached),
                        entry.state,
                    ),
                    WorkspaceNativeRecord(
                        resource,
                        WorkspaceNativeField.WIDTH_LISTENER_ENABLED,
                        RawValue.BooleanValue(entry.before.enabled),
                        RawValue.BooleanValue(entry.planned.enabled),
                        entry.state,
                    ),
                )
            }

    fun git(
        geometry: List<GitSplitterRecord>,
        listeners: List<GitListenerRecord>,
        references: WorkspaceReferences,
    ): List<WorkspaceNativeRecord> =
        geometry.map { entry ->
            WorkspaceNativeRecord(
                references.token(entry.splitter),
                WorkspaceNativeField.GIT_PROPORTION,
                RawValue.FloatBits(entry.before),
                RawValue.FloatBits(entry.planned),
                entry.state,
            )
        } +
            listeners.map { entry ->
                WorkspaceNativeRecord(
                    references.token(entry.listener),
                    WorkspaceNativeField.GIT_LISTENER,
                    RawValue.Integer(entry.before),
                    RawValue.Integer(entry.planned),
                    entry.state,
                )
            }

    private fun baseline(
        value: EditorScrollbarSnapshot,
        references: WorkspaceReferences,
    ): RawValue =
        record("reference" to references.reference(value.originalSize), "dimensions" to size(value.originalDimensions))

    private fun size(value: ScrollbarSize?): RawValue =
        value?.let { record("width" to RawValue.Integer(it.width), "height" to RawValue.Integer(it.height)) }
            ?: RawValue.Null

    private fun number(value: Int?): RawValue = value?.let(RawValue::Integer) ?: RawValue.Null

    private fun widthField(field: ToolWindowWidthField): WorkspaceNativeField =
        when (field) {
            ToolWindowWidthField.FIRST_SIZE -> WorkspaceNativeField.WIDTH_FIRST_SIZE
            ToolWindowWidthField.LAST_SIZE -> WorkspaceNativeField.WIDTH_LAST_SIZE
            ToolWindowWidthField.BOUNDS -> WorkspaceNativeField.WIDTH_BOUNDS
        }

    private fun widthValue(value: ToolWindowWidthValue): RawValue =
        when (value) {
            is ToolWindowWidthValue.Size -> RawValue.Integer(value.pixels)
            is ToolWindowWidthValue.Bounds ->
                record(
                    "x" to RawValue.Integer(value.rectangle.x),
                    "y" to RawValue.Integer(value.rectangle.y),
                    "width" to RawValue.Integer(value.rectangle.width),
                    "height" to RawValue.Integer(value.rectangle.height),
                )
        }
}
