package dev.ayuislands.studio.session

import dev.ayuislands.accent.AccentWriteCheckpoint
import dev.ayuislands.accent.AccentWriteState
import dev.ayuislands.commitpanel.CommitPanelAutoFitManager
import dev.ayuislands.editor.EditorScrollbarManager
import dev.ayuislands.gitpanel.GitPanelAutoFitManager
import dev.ayuislands.projectview.ProjectViewScrollbarManager
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.persistence.StudioReceiptPolicy
import dev.ayuislands.toolwindow.ToolWindowAutoFitter

/** Native object identities belong to one retained Workspace operation, never to saved preferences. */
internal class WorkspaceReferences(
    val ownerToken: String,
    val process: StudioProcess,
) {
    private val tokens = StudioNativeTokens("workspace/$ownerToken")

    fun token(value: Any): String = tokens.token(value)

    fun reference(value: Any?): RawValue = StudioNativeEvidence.reference(value?.let(::token))
}

/** Keeps existing native checkpoints alive; only their own per-cell evidence determines restoration status. */
internal class StudioWorkspaceFields private constructor(
    private val native: AccentWriteCheckpoint,
    references: WorkspaceReferences,
    private val pendingGeometry: () -> Boolean = { false },
    private val records: () -> List<WorkspaceNativeRecord>,
) {
    private var phase = Phase.PREPARED
    private val evidence =
        records().map { record ->
            StudioWorkspaceReceipts.create(
                NativeFieldIdentity(record.resource, record.field.name, references.token(native)),
                references.process,
                record.before,
                record.planned,
            )
        }

    val receipts: StudioList<PortableReceipt>
        get() {
            val current = records().associateBy { it.resource to it.field.name }
            return StudioList(
                evidence.map { receipt ->
                    val record = checkNotNull(current[receipt.identity.resource to receipt.identity.field])
                    receipt.copy(status = status(record.state))
                },
            )
        }

    val hasPendingGeometry: Boolean
        get() = pendingGeometry()

    fun apply() {
        check(phase == Phase.PREPARED) { "Workspace native application cannot be replayed" }
        phase = Phase.APPLYING
        check(native.apply()) { "Prepared Workspace surface changed before application" }
        phase = Phase.APPLIED
    }

    fun restore(blockedBy: List<PortableReceipt>) {
        check(phase != Phase.ACCEPTING && phase != Phase.ACCEPTED) { "Accepted Workspace fields cannot be restored" }
        if (phase == Phase.RESTORED) return
        phase = Phase.RESTORING
        check(
            !native.hasPending ||
                receipts.none { receipt ->
                    !StudioReceiptPolicy.isTerminal(receipt) &&
                        StudioWorkspaceReceipts.isBlocked(receipt.identity.resource, receipt.identity.field, blockedBy)
                },
        ) { "Workspace restoration is blocked by a newer write" }
        native.restore()
        check(!native.hasPending) { "Workspace native cleanup remains incomplete" }
        native.releaseResources()
        phase = Phase.RESTORED
    }

    fun accept() {
        if (phase == Phase.ACCEPTED) return
        check(phase == Phase.APPLIED || phase == Phase.ACCEPTING) { "Workspace native application has not completed" }
        phase = Phase.ACCEPTING
        native.commit()
        native.releaseResources()
        phase = Phase.ACCEPTED
    }

    private fun status(state: AccentWriteState): RestoreStatus =
        when (state) {
            AccentWriteState.PREPARED -> RestoreStatus.PENDING
            AccentWriteState.OWNED -> if (phase == Phase.APPLYING) RestoreStatus.PENDING else RestoreStatus.OWNED
            AccentWriteState.RELINQUISHED -> RestoreStatus.FOREIGN
            AccentWriteState.RESTORED -> RestoreStatus.RESTORED
        }

    companion object {
        fun editor(
            native: EditorScrollbarManager.Checkpoint,
            references: WorkspaceReferences,
        ): StudioWorkspaceFields =
            StudioWorkspaceFields(native, references) { WorkspaceRecords.editor(native.records, references) }

        fun projectScrollbar(
            native: ProjectViewScrollbarManager.ScrollbarCheckpoint,
            references: WorkspaceReferences,
        ): StudioWorkspaceFields =
            StudioWorkspaceFields(native, references) { WorkspaceRecords.scrollbar(native, references) }

        fun projectRenderer(
            native: ProjectViewScrollbarManager.RootRendererCheckpoint,
            references: WorkspaceReferences,
        ): StudioWorkspaceFields =
            StudioWorkspaceFields(native, references) {
                native.records.map { WorkspaceRecords.renderer(it, references) }
            }

        fun commitRenderer(
            native: CommitPanelAutoFitManager.RendererCheckpoint,
            references: WorkspaceReferences,
        ): StudioWorkspaceFields =
            StudioWorkspaceFields(native, references) {
                native.records.map { WorkspaceRecords.renderer(it, references) }
            }

        fun width(
            native: ToolWindowAutoFitter.WidthCheckpoint,
            references: WorkspaceReferences,
        ): StudioWorkspaceFields =
            StudioWorkspaceFields(
                native,
                references,
            ) { WorkspaceRecords.width(native.records, native.listenerRecords, references) }

        fun git(
            native: GitPanelAutoFitManager.Checkpoint,
            references: WorkspaceReferences,
        ): StudioWorkspaceFields =
            StudioWorkspaceFields(
                native,
                references,
                { native.records.any { it.state == AccentWriteState.OWNED } },
                { WorkspaceRecords.git(native.records, native.listenerRecords, references) },
            )
    }

    private enum class Phase { PREPARED, APPLYING, APPLIED, RESTORING, RESTORED, ACCEPTING, ACCEPTED }
}
