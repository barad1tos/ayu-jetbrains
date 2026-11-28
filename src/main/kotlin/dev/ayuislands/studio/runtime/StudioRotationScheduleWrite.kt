package dev.ayuislands.studio.runtime

import dev.ayuislands.rotation.RotationRuntimeCommand
import dev.ayuislands.rotation.RotationRuntimeSnapshot
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.PendingJournal
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.RecoveryPurpose
import dev.ayuislands.studio.persistence.StoreIssue
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.session.ApplyExecution
import dev.ayuislands.studio.session.ApplyFailure
import dev.ayuislands.studio.session.ApplySettlement
import dev.ayuislands.studio.session.SettlementProblem
import dev.ayuislands.studio.session.pending
import java.util.UUID

internal data class RotationScheduleWrite(
    val execution: ApplyExecution,
    val recovery: RotationScheduleRecovery? = null,
)

internal data class RotationScheduleRecovery(
    val journal: PendingJournal,
    val cleanup: ApplySettlement.Finished? = null,
)

/** Write a runtime-only appointment from a fresh observation on the shared I/O lane. */
internal fun commitRotationSchedule(
    store: StudioAppearanceStore,
    request: RotationRuntimeCommand.PersistCheckpoint,
    current: RotationRuntimeSnapshot,
    expectedRevision: Long,
    operation: UUID,
): RotationScheduleWrite {
    if (current != request.source) return staleSchedule()
    val accepted =
        when (val load = store.load()) {
            is StoreLoad.Ready -> load.document
            is StoreLoad.ReadOnly -> return prepareFailed(load.issue)
            is StoreLoad.Recovering -> return prepareFailed(StoreIssue.RECOVERY_REQUIRED)
            StoreLoad.Empty -> return staleSchedule()
        }
    if (accepted.header.revision != expectedRevision ||
        accepted.runtime.rotation != current.checkpoint
    ) {
        return staleSchedule()
    }
    val candidate =
        accepted.copy(
            header =
                accepted.header.copy(
                    revision = accepted.header.revision + 1,
                    transactionId = operation.toString(),
                ),
            runtime = accepted.runtime.copy(rotation = request.proposed.checkpoint),
        )
    val recovery =
        RecoveryPlan(
            target = current.checkpoint.owner.target,
            owners = StudioList(emptyList()),
            purpose = RecoveryPurpose.RotationSchedule(operation),
        )
    return when (val prepared = store.prepare(expectedRevision, candidate, recovery)) {
        is PrepareResult.Prepared -> settleScheduleWrite(store, prepared.journal, candidate)
        is PrepareResult.Rejected -> observeScheduleRejection(store, prepared)
    }
}

private fun settleScheduleWrite(
    store: StudioAppearanceStore,
    journal: PendingJournal,
    proposed: StudioDocument,
): RotationScheduleWrite =
    when (val result = store.commit(journal.token, proposed.runtime)) {
        is CommitResult.Committed ->
            if (result.document == proposed) {
                finishSchedule(ApplySettlement.Accepted(result.document), journal, store.finishAccepted(journal.token))
            } else {
                RotationScheduleWrite(
                    ApplyExecution(
                        ApplySettlement.RecoveryRequired(SettlementProblem.Unconfirmed),
                        ApplyFailure.Commit(StoreIssue.CONFLICT),
                    ),
                    RotationScheduleRecovery(journal),
                )
            }
        is CommitResult.NotCommitted ->
            finishSchedule(
                ApplySettlement.NotAccepted,
                journal,
                store.finishRollback(journal.token),
                ApplyFailure.Commit(result.issue),
            )
        is CommitResult.OutcomeUnknown ->
            RotationScheduleWrite(
                ApplyExecution(
                    ApplySettlement.RecoveryRequired(SettlementProblem.Journal(result.issue)),
                    ApplyFailure.Commit(result.issue),
                ),
                RotationScheduleRecovery(journal),
            )
    }

private fun finishSchedule(
    settled: ApplySettlement.Finished,
    journal: PendingJournal,
    result: JournalResult,
    failure: ApplyFailure? = null,
): RotationScheduleWrite =
    when (result) {
        JournalResult.Acknowledged -> RotationScheduleWrite(ApplyExecution(settled, failure))
        is JournalResult.Rejected ->
            RotationScheduleWrite(
                ApplyExecution(settled.pending(SettlementProblem.Journal(result.issue)), failure),
                RotationScheduleRecovery(journal, cleanup = settled),
            )
    }

private fun staleSchedule(): RotationScheduleWrite =
    RotationScheduleWrite(ApplyExecution(ApplySettlement.NotAccepted, ApplyFailure.LeaseLost))

private fun observeScheduleRejection(
    store: StudioAppearanceStore,
    rejected: PrepareResult.Rejected,
): RotationScheduleWrite =
    if (store.load() is StoreLoad.Ready) {
        RotationScheduleWrite(ApplyExecution(ApplySettlement.NotAccepted, ApplyFailure.Prepare(rejected.issue)))
    } else {
        prepareFailed(rejected.issue, rejected.attemptedJournal)
    }

private fun prepareFailed(
    issue: StoreIssue,
    journal: PendingJournal? = null,
): RotationScheduleWrite =
    RotationScheduleWrite(
        ApplyExecution(ApplySettlement.RecoveryRequired(SettlementProblem.Journal(issue)), ApplyFailure.Prepare(issue)),
        journal?.let { RotationScheduleRecovery(it) },
    )
