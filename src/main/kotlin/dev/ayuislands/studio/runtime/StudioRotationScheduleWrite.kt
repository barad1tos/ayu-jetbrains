package dev.ayuislands.studio.runtime

import dev.ayuislands.rotation.RotationRuntimeCommand
import dev.ayuislands.rotation.RotationRuntimeSnapshot
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.PreparedToken
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

/** Write a runtime-only appointment from a fresh observation on the shared I/O lane. */
internal fun commitRotationSchedule(
    store: StudioAppearanceStore,
    request: RotationRuntimeCommand.PersistCheckpoint,
    current: RotationRuntimeSnapshot,
    expectedRevision: Long,
    operation: UUID,
): ApplyExecution {
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
        is PrepareResult.Prepared -> settleScheduleWrite(store, prepared.token, candidate)
        is PrepareResult.Rejected -> observeScheduleRejection(store, prepared.issue)
    }
}

private fun settleScheduleWrite(
    store: StudioAppearanceStore,
    token: PreparedToken,
    proposed: StudioDocument,
): ApplyExecution =
    when (val result = store.commit(token, proposed.runtime)) {
        is CommitResult.Committed ->
            if (result.document == proposed) {
                finishSchedule(ApplySettlement.Accepted(result.document), store.finishAccepted(token))
            } else {
                ApplyExecution(
                    ApplySettlement.RecoveryRequired(SettlementProblem.Unconfirmed),
                    ApplyFailure.Commit(StoreIssue.CONFLICT),
                )
            }
        is CommitResult.NotCommitted ->
            finishSchedule(ApplySettlement.NotAccepted, store.finishRollback(token), ApplyFailure.Commit(result.issue))
        is CommitResult.OutcomeUnknown ->
            ApplyExecution(
                ApplySettlement.RecoveryRequired(SettlementProblem.Journal(result.issue)),
                ApplyFailure.Commit(result.issue),
            )
    }

private fun finishSchedule(
    settled: ApplySettlement.Finished,
    result: JournalResult,
    failure: ApplyFailure? = null,
): ApplyExecution =
    when (result) {
        JournalResult.Acknowledged -> ApplyExecution(settled, failure)
        is JournalResult.Rejected -> ApplyExecution(settled.pending(SettlementProblem.Journal(result.issue)), failure)
    }

private fun staleSchedule(): ApplyExecution = ApplyExecution(ApplySettlement.NotAccepted, ApplyFailure.LeaseLost)

private fun observeScheduleRejection(
    store: StudioAppearanceStore,
    issue: StoreIssue,
): ApplyExecution =
    if (store.load() is StoreLoad.Ready) {
        ApplyExecution(ApplySettlement.NotAccepted, ApplyFailure.Prepare(issue))
    } else {
        prepareFailed(issue)
    }

private fun prepareFailed(issue: StoreIssue): ApplyExecution =
    ApplyExecution(ApplySettlement.RecoveryRequired(SettlementProblem.Journal(issue)), ApplyFailure.Prepare(issue))
