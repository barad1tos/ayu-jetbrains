package dev.ayuislands.studio.session

import dev.ayuislands.accent.combineUiFailures
import dev.ayuislands.accent.runCatchingPreservingCancellation
import dev.ayuislands.editor.EditorScrollbarManager
import dev.ayuislands.editor.EditorScrollbarRecord
import dev.ayuislands.editor.EditorScrollbarSnapshot
import dev.ayuislands.editor.EditorScrollbarWrite
import dev.ayuislands.editor.ScrollbarSize
import dev.ayuislands.settings.WorkspaceToggle
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Keeps Swing ownership on EDT and presents only portable values to the journal. */
internal class StudioEditorScrollbarOperation private constructor(
    private val checkpoints: List<EditorScrollbarManager.Checkpoint>,
    private val tokens: StudioNativeTokens,
    private val nativeDispatcher: CoroutineDispatcher,
    captured: List<PortableReceipt>,
) : StudioOwnerOperation {
    override var receipts: StudioList<PortableReceipt> = StudioList(captured)
        private set

    override suspend fun apply(writes: StudioNativeWrite): Boolean =
        withContext(nativeDispatcher) {
            try {
                for (checkpoint in checkpoints) {
                    val applied =
                        writes.execute(nativeDispatcher) {
                            check(checkpoint.apply()) { "Prepared editor scrollbars changed before Apply" }
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
                            check(!checkpoint.hasPending || checkpoint.records.none { isBlocked(it, blocked) }) {
                                "Editor scrollbar restoration is blocked by a newer write"
                            }
                            checkpoint.restore()
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
            for (checkpoint in checkpoints) {
                checkpoint.commit()
                checkpoint.releaseResources()
            }
        }

    private fun isBlocked(
        entry: EditorScrollbarRecord,
        newer: List<PortableReceipt>,
    ): Boolean {
        if (entry.state != EditorScrollbarWrite.OWNED) return false
        val resource = tokens.token(entry.bar)
        return fields.any { StudioWorkspaceReceipts.isBlocked(resource, it.name, newer) }
    }

    private fun observeReceipts() {
        val states = checkpoints.flatMap { it.records }.associate { tokens.token(it.bar) to status(it.state) }
        receipts = StudioList(receipts.map { it.copy(status = states.getValue(it.identity.resource)) })
    }

    companion object {
        private val fields = listOf(WorkspaceNativeField.EDITOR_SIZE, WorkspaceNativeField.EDITOR_BASELINE)

        suspend fun capture(
            managers: List<EditorScrollbarManager>,
            selection: StudioWorkspaceSelection,
            process: StudioProcess,
            transactionId: String,
            nativeDispatcher: CoroutineDispatcher,
        ): StudioOwnerOperation? {
            val vertical = selected(selection, WorkspaceToggle.EDITOR_VERTICAL_SCROLLBAR)
            val horizontal = selected(selection, WorkspaceToggle.EDITOR_HORIZONTAL_SCROLLBAR)
            if (vertical == null && horizontal == null) return null
            return withContext(nativeDispatcher) {
                val checkpoints = managers.map { it.capture(vertical, horizontal) }.filter { it.records.isNotEmpty() }
                if (checkpoints.isEmpty()) return@withContext null
                val owner = "workspace/$transactionId/editor"
                val tokens = StudioNativeTokens(owner)
                val captured =
                    checkpoints.flatMap { checkpoint ->
                        checkpoint.records.flatMap { entry ->
                            fields.map { field -> evidence(entry, field, owner, process, tokens) }
                        }
                    }
                StudioEditorScrollbarOperation(checkpoints, tokens, nativeDispatcher, captured)
            }
        }

        private fun selected(
            selection: StudioWorkspaceSelection,
            toggle: WorkspaceToggle,
        ): Boolean? = selection.settings.isHidden(toggle).takeIf { toggle in selection.toggles }

        private fun evidence(
            entry: EditorScrollbarRecord,
            field: WorkspaceNativeField,
            owner: String,
            process: StudioProcess,
            tokens: StudioNativeTokens,
        ): PortableReceipt =
            StudioWorkspaceReceipts.create(
                NativeFieldIdentity(tokens.token(entry.bar), field.name, owner),
                process,
                value(field, entry.before, tokens),
                value(field, entry.planned, tokens),
            )

        private fun value(
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

        private fun size(size: ScrollbarSize?): RawValue =
            size?.let {
                StudioNativeEvidence.record(
                    "width" to RawValue.Integer(it.width),
                    "height" to RawValue.Integer(it.height),
                )
            } ?: RawValue.Null

        private fun status(state: EditorScrollbarWrite): RestoreStatus =
            when (state) {
                EditorScrollbarWrite.PREPARED -> RestoreStatus.PENDING
                EditorScrollbarWrite.OWNED -> RestoreStatus.OWNED
                EditorScrollbarWrite.RELINQUISHED -> RestoreStatus.FOREIGN
                EditorScrollbarWrite.RESTORED -> RestoreStatus.RESTORED
            }
    }
}
