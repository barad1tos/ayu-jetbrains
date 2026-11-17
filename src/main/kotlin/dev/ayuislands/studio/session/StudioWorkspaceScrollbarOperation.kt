package dev.ayuislands.studio.session

import dev.ayuislands.accent.AccentCheckpoint
import dev.ayuislands.accent.combineUiFailures
import dev.ayuislands.accent.runCatchingPreservingCancellation
import dev.ayuislands.editor.EditorScrollbarManager
import dev.ayuislands.editor.EditorScrollbarSnapshot
import dev.ayuislands.editor.EditorScrollbarWrite
import dev.ayuislands.editor.ScrollbarSize
import dev.ayuislands.projectview.ProjectScrollbarOwner
import dev.ayuislands.projectview.ProjectViewScrollbarManager
import dev.ayuislands.settings.WorkspaceToggle
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Journals selected Editor and Project View scrollbar effects under one Workspace owner. */
internal class StudioWorkspaceScrollbarOperation private constructor(
    private val checkpoints: List<ScrollbarCheckpoint>,
    private val tokens: StudioNativeTokens,
    private val nativeDispatcher: CoroutineDispatcher,
    captured: List<PortableReceipt>,
) : StudioOwnerOperation {
    override var receipts: StudioList<PortableReceipt> = StudioList(captured)
        private set

    internal data class Targets(
        val editors: List<EditorScrollbarManager>,
        val projects: List<ProjectViewScrollbarManager>,
    )

    override suspend fun apply(writes: StudioNativeWrite): Boolean =
        withContext(nativeDispatcher) {
            try {
                for (checkpoint in checkpoints) {
                    val applied =
                        writes.execute(nativeDispatcher) {
                            check(checkpoint.apply()) { "Prepared Workspace scrollbars changed before Apply" }
                        }
                    if (!applied) return@withContext false
                }
                true
            } finally {
                observeReceipts()
            }
        }

    override suspend fun restore(blocked: List<PortableReceipt>): RestoreContinuation =
        withContext(nativeDispatcher) {
            try {
                var failure: Throwable? = null
                for (checkpoint in checkpoints.asReversed()) {
                    val problem =
                        runCatchingPreservingCancellation {
                            check(!checkpoint.native.hasPending || !isBlocked(checkpoint, blocked)) {
                                "Workspace scrollbar restoration is blocked by a newer write"
                            }
                            checkpoint.native.restore()
                        }.exceptionOrNull()
                    if (problem != null) failure = combineUiFailures(failure, problem)
                }
                failure?.let { throw it }
                RestoreContinuation.COMPLETE
            } finally {
                observeReceipts()
            }
        }

    override suspend fun accept() =
        withContext(nativeDispatcher) {
            checkpoints.forEach { it.native.commit() }
            checkpoints.forEach { it.native.releaseResources() }
        }

    private fun isBlocked(
        checkpoint: ScrollbarCheckpoint,
        newer: List<PortableReceipt>,
    ): Boolean =
        checkpoint.statuses(tokens).any { (resource, status) ->
            status == RestoreStatus.OWNED &&
                checkpoint.fields.any { StudioWorkspaceReceipts.isBlocked(resource, it.name, newer) }
        }

    private fun observeReceipts() {
        val states = checkpoints.flatMap { it.statuses(tokens) }.toMap()
        receipts = StudioList(receipts.map { it.copy(status = states.getValue(it.identity.resource)) })
    }

    private sealed interface ScrollbarCheckpoint {
        val native: AccentCheckpoint
        val fields: List<WorkspaceNativeField>

        fun apply(): Boolean

        fun evidence(context: ReceiptContext): List<PortableReceipt>

        fun statuses(tokens: StudioNativeTokens): List<Pair<String, RestoreStatus>>
    }

    private class EditorCheckpoint(
        override val native: EditorScrollbarManager.Checkpoint,
    ) : ScrollbarCheckpoint {
        override val fields get() = FIELDS

        override fun apply(): Boolean = native.apply()

        override fun evidence(context: ReceiptContext): List<PortableReceipt> =
            native.records.flatMap { entry ->
                val resource = context.tokens.token(entry.bar)
                fields.map { field ->
                    context.receipt(
                        resource,
                        field,
                        value(field, entry.before, context.tokens),
                        value(field, entry.planned, context.tokens),
                    )
                }
            }

        override fun statuses(tokens: StudioNativeTokens): List<Pair<String, RestoreStatus>> =
            native.records.map { tokens.token(it.bar) to status(it.state) }

        private companion object {
            val FIELDS = listOf(WorkspaceNativeField.EDITOR_SIZE, WorkspaceNativeField.EDITOR_BASELINE)

            fun value(
                field: WorkspaceNativeField,
                snapshot: EditorScrollbarSnapshot,
                tokens: StudioNativeTokens,
            ): RawValue =
                when (field) {
                    WorkspaceNativeField.EDITOR_SIZE -> size(snapshot.preferredSize)
                    WorkspaceNativeField.EDITOR_BASELINE ->
                        StudioNativeEvidence.record(
                            "reference" to StudioNativeEvidence.reference(snapshot.baseline?.let(tokens::token)),
                            "dimensions" to size(snapshot.baselineSize),
                        )
                    else -> error("Not an editor scrollbar field: $field")
                }

            fun size(size: ScrollbarSize?): RawValue =
                size?.let {
                    StudioNativeEvidence.record(
                        "width" to RawValue.Integer(it.width),
                        "height" to RawValue.Integer(it.height),
                    )
                } ?: RawValue.Null

            fun status(state: EditorScrollbarWrite): RestoreStatus =
                when (state) {
                    EditorScrollbarWrite.PREPARED -> RestoreStatus.PENDING
                    EditorScrollbarWrite.OWNED -> RestoreStatus.OWNED
                    EditorScrollbarWrite.RELINQUISHED -> RestoreStatus.FOREIGN
                    EditorScrollbarWrite.RESTORED -> RestoreStatus.RESTORED
                }
        }
    }

    private class ProjectCheckpoint(
        override val native: ProjectScrollbarOwner.Checkpoint,
    ) : ScrollbarCheckpoint {
        override val fields get() = FIELDS

        override fun apply(): Boolean = native.apply()

        override fun evidence(context: ReceiptContext): List<PortableReceipt> {
            val resource = context.tokens.token(native.pane)
            return listOf(
                context.receipt(
                    resource,
                    WorkspaceNativeField.PROJECT_SCROLLBAR_POLICY,
                    RawValue.Integer(native.before.policy),
                    RawValue.Integer(native.planned.policy),
                ),
                context.receipt(
                    resource,
                    WorkspaceNativeField.PROJECT_SCROLLBAR_BASELINE,
                    baseline(native.before),
                    baseline(native.planned),
                ),
            )
        }

        override fun statuses(tokens: StudioNativeTokens): List<Pair<String, RestoreStatus>> =
            listOf(tokens.token(native.pane) to status(native.phase))

        private companion object {
            val FIELDS =
                listOf(WorkspaceNativeField.PROJECT_SCROLLBAR_POLICY, WorkspaceNativeField.PROJECT_SCROLLBAR_BASELINE)

            fun baseline(policy: ProjectScrollbarOwner.Policy): RawValue =
                policy.baseline?.let(RawValue::Integer) ?: RawValue.Null

            fun status(phase: ProjectScrollbarOwner.Phase): RestoreStatus =
                when (phase) {
                    ProjectScrollbarOwner.Phase.PREPARED -> RestoreStatus.PENDING
                    ProjectScrollbarOwner.Phase.OWNED, ProjectScrollbarOwner.Phase.ACCEPTED -> RestoreStatus.OWNED
                    ProjectScrollbarOwner.Phase.FOREIGN -> RestoreStatus.FOREIGN
                    ProjectScrollbarOwner.Phase.RESTORED -> RestoreStatus.RESTORED
                }
        }
    }

    private class ReceiptContext(
        private val owner: String,
        private val process: StudioProcess,
    ) {
        val tokens = StudioNativeTokens(owner)

        fun receipt(
            resource: String,
            field: WorkspaceNativeField,
            before: RawValue,
            planned: RawValue,
        ): PortableReceipt =
            StudioWorkspaceReceipts.create(NativeFieldIdentity(resource, field.name, owner), process, before, planned)
    }

    companion object {
        suspend fun capture(
            targets: Targets,
            selection: StudioWorkspaceSelection,
            process: StudioProcess,
            transactionId: String,
            nativeDispatcher: CoroutineDispatcher,
        ): StudioOwnerOperation? {
            val vertical = selected(selection, WorkspaceToggle.EDITOR_VERTICAL_SCROLLBAR)
            val horizontal = selected(selection, WorkspaceToggle.EDITOR_HORIZONTAL_SCROLLBAR)
            val projectHorizontal = selected(selection, WorkspaceToggle.PROJECT_HORIZONTAL_SCROLLBAR)
            if (vertical == null && horizontal == null && projectHorizontal == null) return null
            return withContext(nativeDispatcher) {
                val editors =
                    if (vertical == null && horizontal == null) {
                        emptyList()
                    } else {
                        targets.editors
                            .map { it.capture(vertical, horizontal) }
                            .filter { it.records.isNotEmpty() }
                            .map(::EditorCheckpoint)
                    }
                val projects =
                    projectHorizontal
                        ?.let { hide ->
                            targets.projects
                                .mapNotNull { it.prepareScrollbar(hide) }
                                .map(::ProjectCheckpoint)
                        }.orEmpty()
                val checkpoints = editors + projects
                if (checkpoints.isEmpty()) return@withContext null
                val context = ReceiptContext("workspace/$transactionId/scrollbars", process)
                val captured = checkpoints.flatMap { it.evidence(context) }
                StudioWorkspaceScrollbarOperation(checkpoints, context.tokens, nativeDispatcher, captured)
            }
        }

        private fun selected(
            selection: StudioWorkspaceSelection,
            toggle: WorkspaceToggle,
        ): Boolean? = selection.settings.isHidden(toggle).takeIf { toggle in selection.toggles }
    }
}
