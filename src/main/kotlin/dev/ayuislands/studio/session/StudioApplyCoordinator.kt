package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.AcceptanceBlock
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.ApplyRequest
import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioRuntimeState
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.persistence.CommitResolution
import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.PendingJournal
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPurpose
import dev.ayuislands.studio.persistence.StoreIssue
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.recoveryPlanFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Runs one user Apply on the application queue shared with the store's other writers. */
internal class StudioApplyCoordinator(
    private val store: StudioAppearanceStore,
    private val queue: Mutex,
    private val ioDispatcher: CoroutineDispatcher,
    flush: suspend (StudioSessionRecord) -> DraftWriteResult,
    observe: suspend (ApplyRequest, StudioDocument) -> ApplyInputs,
    private val acquire: suspend (AcceptancePlan.Commit) -> StudioApplyLease,
    private val recover: suspend (PendingJournal, CommitResolution) -> StudioRecoveryLease?,
    private val cancelSettlementTimeout: Duration = 5.seconds,
) {
    private val admission = StudioSubmitAdmission(store, queue, flush, observe, ioDispatcher)
    private val owners = StudioOwnerJournal(store, ioDispatcher)
    private var activeApply: ActiveOperation? = null
    private val outcomes = mutableMapOf<UUID, RecordedApply>()

    suspend fun submit(
        request: ApplyRequest,
        operation: UUID,
    ): Submission<ApplyExecution> =
        admission.submit(
            request,
            operation,
            consume = { plan -> execute(request, operation, plan) },
            beforeAdmission = { settlePrior(request, operation) },
        )

    suspend fun outcome(operation: UUID): ApplyExecution? = queue.withLock { outcomes[operation]?.execution }

    /** The host retires a consumed outcome; unresolved work must remain reachable. */
    suspend fun acknowledge(operation: UUID): Boolean =
        queue.withLock {
            activeApply?.operation != operation && outcomes.remove(operation) != null
        }

    suspend fun retry(operation: UUID): ApplyExecution? =
        queue.withLock {
            val active = activeApply
            when {
                active?.operation == operation -> settle(active)
                else -> outcomes[operation]?.execution ?: if (active == null) recoverPending(operation) else null
            }
        }

    private suspend fun settlePrior(
        request: ApplyRequest,
        operation: UUID,
    ): Submission<ApplyExecution>? {
        val recorded = outcomes[operation]
        if (recorded != null && recorded.request != request) return Submission.Blocked(AcceptanceBlock.INVALID_REQUEST)
        val retained = activeApply
        if (retained != null) {
            if (retained.operation == operation && retained.request != request) {
                return Submission.Blocked(AcceptanceBlock.INVALID_REQUEST)
            }
            val result = settle(retained)
            if (retained.operation == operation) return Submission.Admitted(result)
            if (result.settlement !is ApplySettlement.Finished) {
                return Submission.Retained(retained.operation, result)
            }
        }
        return recorded?.let { Submission.Admitted(it.execution) }
    }

    private suspend fun execute(
        request: ApplyRequest,
        operation: UUID,
        plan: AcceptancePlan.Commit,
    ): ApplyExecution =
        try {
            val work =
                withContext(NonCancellable) {
                    ActiveApply(request, operation, plan, acquire(plan)).also { activeApply = it }
                }
            work.failure = apply(work)
            settle(work)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val pending = activeApply as? ActiveApply
                if (pending == null) {
                    record(request, operation, ApplyExecution(ApplySettlement.NotAccepted, ApplyFailure.Cancelled))
                } else {
                    pending.failure = ApplyFailure.Cancelled
                    settleCancelled(pending)
                }
            }
            throw cancelled
        } catch (failure: RuntimeException) {
            val pending = activeApply as? ActiveApply
            val problem = ApplyFailure.Native(failure)
            if (pending == null) {
                record(request, operation, ApplyExecution(ApplySettlement.NotAccepted, problem))
            } else {
                pending.failure = problem
                settle(pending)
            }
        }

    private suspend fun apply(active: ActiveApply): ApplyFailure? {
        if (!qualifies(active.lease)) return ApplyFailure.LeaseLost
        val journal =
            when (val result = withContext(ioDispatcher) { store.prepare(active.plan) }) {
                is PrepareResult.Prepared -> result.journal
                is PrepareResult.Rejected -> return ApplyFailure.Prepare(result.issue)
            }
        val work = bind(active, journal)
        val result = owners.apply(work, active.lease::qualifies, active.lease::capture)
        if (result != OwnerApply.Completed) return ApplyFailure.Effects(result)
        if (!qualifies(active.lease)) return ApplyFailure.LeaseLost
        val runtime = active.lease.settledRuntime(journal.candidate)
        return withContext(ioDispatcher) {
            if (!qualifies(active.lease)) return@withContext ApplyFailure.LeaseLost
            when (val committed = store.commit(journal.token, runtime)) {
                is CommitResult.Committed -> null
                is CommitResult.NotCommitted -> ApplyFailure.Commit(committed.issue)
                is CommitResult.OutcomeUnknown -> ApplyFailure.Commit(committed.issue)
            }
        }
    }

    private suspend fun qualifies(lease: StudioApplyLease): Boolean {
        currentCoroutineContext().ensureActive()
        val valid = lease.qualifies()
        currentCoroutineContext().ensureActive()
        return valid
    }

    private suspend fun recoverPending(operation: UUID): ApplyExecution? {
        val (observed, decision) =
            withContext(ioDispatcher) {
                val loaded = store.load() as? StoreLoad.Recovering ?: return@withContext null
                val journal = loaded.journal
                if (journal.token.transactionId != operation.toString() ||
                    journal.token.baseRevision < 0 ||
                    journal.recovery.purpose != RecoveryPurpose.UserAcceptance
                ) {
                    return@withContext null
                }
                loaded to store.resolveOutcome(journal.token)
            } ?: return null
        val outcome =
            when (decision) {
                CommitResolution.Committed -> observed.document?.let(ApplySettlement::Accepted)
                CommitResolution.NotCommitted -> ApplySettlement.NotAccepted
                CommitResolution.Unknown -> null
            } ?: return ApplyExecution(ApplySettlement.RecoveryRequired(SettlementProblem.Unconfirmed), null)
        val recovered =
            try {
                withContext(NonCancellable) { attachRecovery(operation, observed.journal, decision) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: RuntimeException) {
                currentCoroutineContext().ensureActive()
                return ApplyExecution(outcome.pending(SettlementProblem.RecoveryLeaseFailed(failure)), null)
            } ?: return ApplyExecution(outcome.pending(SettlementProblem.RecoveryLeaseUnavailable), null)
        currentCoroutineContext().ensureActive()
        return settle(recovered)
    }

    private suspend fun attachRecovery(
        operation: UUID,
        journal: PendingJournal,
        decision: CommitResolution,
    ): RecoveredApply? {
        val lease = recover(journal, decision) ?: return null
        val work = StudioOwnerWork(journal, lease::recover)
        val settlement = StudioApplySettlement(store, owners, ioDispatcher, work, lease::release)
        return RecoveredApply(operation, settlement).also { activeApply = it }
    }

    private suspend fun settle(work: ActiveOperation): ApplyExecution {
        val result =
            when (work) {
                is ActiveApply -> work.settlement?.settle() ?: settleUnbound(work)
                is RecoveredApply -> work.settlement.settle()
            }
        return recordSettlement(work, result)
    }

    private suspend fun settleCancelled(work: ActiveApply) {
        val result = withTimeoutOrNull(cancelSettlementTimeout) { settle(work) }
        if (result == null && activeApply === work) {
            val problem = SettlementProblem.TimedOut
            val pending = work.settlement?.interrupted(problem) ?: ApplySettlement.RecoveryRequired(problem)
            recordSettlement(work, pending)
        }
    }

    private fun recordSettlement(
        work: ActiveOperation,
        result: ApplySettlement,
    ): ApplyExecution {
        val failure =
            (work as? ActiveApply)?.failure.takeUnless {
                result is ApplySettlement.Accepted ||
                    result is ApplySettlement.AcceptedCleanupPending
            }
        if (result is ApplySettlement.Finished) activeApply = null
        return record(work.request, work.operation, ApplyExecution(result, failure))
    }

    private suspend fun settleUnbound(work: ActiveApply): ApplySettlement {
        val observed = withContext(ioDispatcher) { store.load() }
        if (observed is StoreLoad.ReadOnly) {
            return ApplySettlement.RecoveryRequired(
                SettlementProblem.Journal(observed.issue),
            )
        }
        val journal = (observed as? StoreLoad.Recovering)?.journal
        if (journal != null && journal.token.transactionId == work.operation.toString()) {
            val plan = work.plan
            if (journal.candidate != plan.document ||
                journal.token.baseRevision != plan.lease.documentRevision ||
                journal.recovery != recoveryPlanFor(plan)
            ) {
                return ApplySettlement.RecoveryRequired(SettlementProblem.Unconfirmed)
            }
            bind(work, journal)
            return checkNotNull(work.settlement).settle()
        }
        return try {
            work.lease.release(null)
            ApplySettlement.NotAccepted
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            currentCoroutineContext().ensureActive()
            ApplySettlement.RecoveryRequired(SettlementProblem.Release(failure))
        }
    }

    private fun bind(
        active: ActiveApply,
        journal: PendingJournal,
    ): StudioOwnerWork {
        val work = StudioOwnerWork(journal)
        active.settlement = StudioApplySettlement(store, owners, ioDispatcher, work, active.lease::release)
        return work
    }

    private fun record(
        request: ApplyRequest?,
        operation: UUID,
        result: ApplyExecution,
    ): ApplyExecution {
        outcomes[operation] = RecordedApply(request, result)
        return result
    }

    private sealed interface ActiveOperation {
        val operation: UUID
        val request: ApplyRequest?
    }

    private class ActiveApply(
        override val request: ApplyRequest,
        override val operation: UUID,
        val plan: AcceptancePlan.Commit,
        val lease: StudioApplyLease,
    ) : ActiveOperation {
        var failure: ApplyFailure? = null
        var settlement: StudioApplySettlement? = null
    }

    private class RecoveredApply(
        override val operation: UUID,
        val settlement: StudioApplySettlement,
    ) : ActiveOperation {
        override val request: ApplyRequest? get() = null
    }

    private data class RecordedApply(
        val request: ApplyRequest?,
        val execution: ApplyExecution,
    )
}

/** Reattaches durable handles without preparing or applying a new effect. */
internal interface StudioRecoveryLease {
    suspend fun recover(
        owner: EffectOwner,
        receipts: StudioList<PortableReceipt>,
    ): StudioOwnerOperation?

    suspend fun release(accepted: StudioDocument?)
}

/** A returned lease is retained even if it has already lost qualification. */
internal interface StudioApplyLease {
    suspend fun qualifies(): Boolean

    suspend fun capture(owner: EffectOwner): StudioOwnerOperation?

    suspend fun settledRuntime(candidate: StudioDocument): StudioRuntimeState

    suspend fun release(accepted: StudioDocument?)
}

internal data class ApplyExecution(
    val settlement: ApplySettlement,
    val failure: ApplyFailure?,
)

internal sealed interface ApplyFailure {
    data object Cancelled : ApplyFailure

    data object LeaseLost : ApplyFailure

    data class Prepare(
        val issue: StoreIssue,
    ) : ApplyFailure

    data class Commit(
        val issue: StoreIssue,
    ) : ApplyFailure

    data class Effects(
        val result: OwnerApply,
    ) : ApplyFailure

    data class Native(
        val cause: RuntimeException,
    ) : ApplyFailure
}
