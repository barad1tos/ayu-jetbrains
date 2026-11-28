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
import dev.ayuislands.studio.persistence.PreparedToken
import dev.ayuislands.studio.persistence.RecoveryPurpose
import dev.ayuislands.studio.persistence.StoreIssue
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.recoveryPlanFor
import dev.ayuislands.studio.runtime.RotationCommandOutcome
import dev.ayuislands.studio.runtime.RotationEffectsOutcome
import dev.ayuislands.studio.runtime.RotationScheduleWrite
import dev.ayuislands.studio.runtime.RotationStepAdmission
import dev.ayuislands.studio.runtime.RotationStepPhase
import dev.ayuislands.studio.runtime.RotationStepRestoration
import dev.ayuislands.studio.runtime.RotationStepWork
import dev.ayuislands.studio.runtime.admitRotationEffects
import dev.ayuislands.studio.runtime.commitRotationRetry
import dev.ayuislands.studio.runtime.settleRotationEffects
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

    suspend fun submitPreparedRotation(
        prepared: RotationStepAdmission.Prepared,
        acquire: suspend (RotationStepWork) -> StudioApplyLease,
        completedAt: () -> Long,
    ): ApplyExecution =
        queue.withLock {
            val operation = prepared.phase.work.operationId
            val retained = activeApply
            if (retained != null) {
                return@withLock if (retained is ActiveRotation && retained.token == prepared.journal.token) {
                    settle(retained)
                } else {
                    unsettledRotation()
                }
            }
            outcomes[operation]?.let { recorded ->
                return@withLock if (recorded.request == null) recorded.execution else unsettledRotation()
            }
            val observed = withContext(ioDispatcher) { store.load() }
            if (observed is StoreLoad.ReadOnly) {
                return@withLock unsettledRotation(SettlementProblem.Journal(observed.issue))
            }
            if (observed !is StoreLoad.Recovering ||
                observed.journal != prepared.journal ||
                observed.document?.header?.revision != observed.journal.token.baseRevision
            ) {
                return@withLock unsettledRotation()
            }
            val checkpoint = observed.document.runtime.rotation ?: return@withLock unsettledRotation()
            val context =
                prepared.phase.work.context
                    .copy(checkpoint = checkpoint)
            val phase = admitRotationEffects(prepared.phase, observed.journal, context)
            if (phase !is RotationStepPhase.Applying) return@withLock unsettledRotation()
            executeRotation(phase, observed.journal, acquire, completedAt)
        }

    /** Keep preparation and ownership handoff on the same lane, including during cancellation. */
    suspend fun submitRotation(
        prepare: suspend () -> RotationStepAdmission,
        acquire: suspend (RotationStepWork) -> StudioApplyLease,
        completedAt: () -> Long,
    ): RotationCommandOutcome =
        submitRotationCommand {
            when (val prepared = withContext(NonCancellable) { prepare() }) {
                is RotationStepAdmission.Prepared -> {
                    val phase = RotationStepPhase.Applying(prepared.phase.work, prepared.journal.token)
                    val execution = executeRotation(phase, prepared.journal, acquire, completedAt)
                    if (execution.settlement is ApplySettlement.Finished) outcomes.remove(phase.work.operationId)
                    RotationCommandOutcome.Executed(execution, phase.work)
                }
                is RotationStepAdmission.StoreUnavailable -> {
                    val journal =
                        prepared.attemptedJournal
                            ?: return@submitRotationCommand RotationCommandOutcome.Rejected(prepared)
                    val active = PendingRotationWrite(journal, ApplyFailure.Prepare(prepared.issue))
                    activeApply = active
                    RotationCommandOutcome.Executed(settle(active))
                }
                else -> RotationCommandOutcome.Rejected(prepared)
            }
        }

    suspend fun submitRotationSchedule(write: suspend () -> RotationScheduleWrite?): RotationCommandOutcome =
        submitRotationCommand {
            withContext(NonCancellable) {
                val result = write() ?: return@withContext RotationCommandOutcome.Rejected(RotationStepAdmission.Stale)
                val recovery = result.recovery
                if (recovery != null) {
                    val journal = recovery.journal
                    val active = PendingRotationWrite(journal, result.execution.failure)
                    if (recovery.cleanup != null) {
                        active.settlement =
                            StudioApplySettlement(
                                store,
                                owners,
                                ioDispatcher,
                                StudioOwnerWork(journal),
                                confirmedCleanup = recovery.cleanup,
                            ) {}
                    }
                    activeApply = active
                    record(null, active.operation, result.execution)
                }
                RotationCommandOutcome.Executed(result.execution)
            }
        }

    /** All automatic commands settle earlier Apply work before reading authority. */
    private suspend fun submitRotationCommand(execute: suspend () -> RotationCommandOutcome): RotationCommandOutcome =
        queue.withLock {
            val retained = activeApply
            if (retained != null) {
                val settled = settle(retained)
                if (settled.settlement !is ApplySettlement.Finished) {
                    val unavailable = RotationStepAdmission.StoreUnavailable(StoreIssue.RECOVERY_REQUIRED)
                    return@withLock RotationCommandOutcome.Rejected(unavailable)
                }
                val automatic =
                    when (retained) {
                        is ActiveRotation -> true
                        is RecoveredApply -> retained.purpose is RecoveryPurpose.RotationStep
                        is ActiveApply, is PendingRotationWrite -> false
                    }
                if (automatic) outcomes.remove(retained.operation)
            }
            execute()
        }

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
        applyOwners(work, active.lease)?.let { return it }
        return commit(journal.token, active.lease, active.lease.settledRuntime(journal.candidate))
    }

    private suspend fun executeRotation(
        phase: RotationStepPhase.Applying,
        journal: PendingJournal,
        acquire: suspend (RotationStepWork) -> StudioApplyLease,
        completedAt: () -> Long,
    ): ApplyExecution {
        val work = StudioOwnerWork(journal)
        var captured: StudioApplyLease? = null
        val settlement =
            StudioApplySettlement(store, owners, ioDispatcher, work) { accepted -> captured?.release(accepted) }
        val active =
            ActiveRotation(phase.work.operationId, journal.token, settlement) { failure ->
                withContext(ioDispatcher) {
                    val canRetry = captured != null && failure.canRetryRotation()
                    commitRotationRetry(store, journal.token, canRetry, completedAt)
                }
            }
        activeApply = active
        return try {
            val lease = withContext(NonCancellable) { acquire(phase.work).also { captured = it } }
            active.failure = applyRotation(phase, work, lease, completedAt)
            settle(active)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                active.failure = ApplyFailure.Cancelled
                settleCancelled(active)
            }
            throw cancelled
        } catch (failure: RuntimeException) {
            active.failure = ApplyFailure.Native(failure)
            settle(active)
        }
    }

    private suspend fun applyRotation(
        phase: RotationStepPhase.Applying,
        work: StudioOwnerWork,
        lease: StudioApplyLease,
        completedAt: () -> Long,
    ): ApplyFailure? {
        if (!qualifies(lease)) return ApplyFailure.LeaseLost
        applyOwners(work, lease)?.let { failure ->
            return if (failure.canRetryRotation() && !qualifies(lease)) ApplyFailure.LeaseLost else failure
        }
        val outcome = RotationEffectsOutcome.Applied(completedAt())
        return when (val proposal = settleRotationEffects(phase, phase.token, outcome, phase.work.context)) {
            is RotationStepPhase.Committing -> {
                val current = work.journal.candidate.runtime
                val runtime =
                    current.copy(
                        rotation = proposal.checkpoint,
                        effectiveContinuation = current.effectiveContinuation?.copy(accent = null),
                    )
                commit(phase.token, lease, runtime)
            }
            is RotationStepPhase.Restoring -> ApplyFailure.Rotation(proposal.reason)
            else -> error("Applied rotation did not produce a settlement plan")
        }
    }

    private suspend fun applyOwners(
        work: StudioOwnerWork,
        lease: StudioApplyLease,
    ): ApplyFailure? {
        val result = owners.apply(work, lease::qualifies, lease::capture)
        if (result != OwnerApply.Completed) return ApplyFailure.Effects(result)
        return if (qualifies(lease)) null else ApplyFailure.LeaseLost
    }

    private suspend fun commit(
        token: PreparedToken,
        lease: StudioApplyLease,
        runtime: StudioRuntimeState,
    ): ApplyFailure? =
        withContext(ioDispatcher) {
            if (!qualifies(lease)) return@withContext ApplyFailure.LeaseLost
            when (val committed = store.commit(token, runtime)) {
                is CommitResult.Committed -> null
                is CommitResult.NotCommitted -> ApplyFailure.Commit(committed.issue)
                is CommitResult.OutcomeUnknown -> ApplyFailure.Commit(committed.issue)
            }
        }

    private suspend fun qualifies(lease: StudioApplyLease): Boolean {
        currentCoroutineContext().ensureActive()
        val valid = lease.qualifies()
        currentCoroutineContext().ensureActive()
        return valid
    }

    private suspend fun recoverPending(operation: UUID): ApplyExecution? {
        val observed =
            when (val loaded = withContext(ioDispatcher) { store.load() }) {
                is StoreLoad.Recovering -> loaded
                is StoreLoad.ReadOnly ->
                    return ApplyExecution(
                        ApplySettlement.RecoveryRequired(SettlementProblem.Journal(loaded.issue)),
                        null,
                    )

                StoreLoad.Empty, is StoreLoad.Ready -> return null
            }
        val journal = observed.journal
        if (!journal.matchesApplyRecovery(operation)) return null
        val decision = withContext(ioDispatcher) { store.resolveOutcome(journal.token) }
        val outcome =
            when (decision) {
                CommitResolution.Committed -> observed.document?.let(ApplySettlement::Accepted)
                CommitResolution.NotCommitted -> ApplySettlement.NotAccepted
                CommitResolution.Unknown -> null
            } ?: return ApplyExecution(ApplySettlement.RecoveryRequired(SettlementProblem.Unconfirmed), null)
        val recovered =
            try {
                withContext(NonCancellable) { attachRecovery(operation, journal, decision) }
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
        val settlement = StudioApplySettlement(store, owners, ioDispatcher, work, release = lease::release)
        return RecoveredApply(operation, journal.recovery.purpose, settlement).also { activeApply = it }
    }

    private suspend fun settle(work: ActiveOperation): ApplyExecution {
        val result =
            when (work) {
                is ActiveApply -> work.settlement?.settle() ?: settleUnbound(work)
                is ActiveRotation -> work.settlement.settle { work.retry(work.failure) }
                is PendingRotationWrite -> work.settle(store, owners, ioDispatcher)
                is RecoveredApply -> work.settlement.settle()
            }
        return recordSettlement(work, result)
    }

    private suspend fun settleCancelled(work: ActiveOperation) {
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
            work.failure.takeUnless {
                result is ApplySettlement.Accepted ||
                    result is ApplySettlement.AcceptedCleanupPending
            }
        val execution = ApplyExecution(result, failure)
        if (result is ApplySettlement.Finished) {
            activeApply = null
            if (work is PendingRotationWrite) {
                outcomes.remove(work.operation)
                return execution
            }
        }
        return record(work.request, work.operation, execution)
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
        active.settlement = StudioApplySettlement(store, owners, ioDispatcher, work, release = active.lease::release)
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
        val settlement: StudioApplySettlement?
        val failure: ApplyFailure? get() = null
    }

    private class ActiveApply(
        override val request: ApplyRequest,
        override val operation: UUID,
        val plan: AcceptancePlan.Commit,
        val lease: StudioApplyLease,
    ) : ActiveOperation {
        override var failure: ApplyFailure? = null
        override var settlement: StudioApplySettlement? = null
    }

    private class ActiveRotation(
        override val operation: UUID,
        val token: PreparedToken,
        override val settlement: StudioApplySettlement,
        val retry: suspend (ApplyFailure?) -> ApplySettlement,
    ) : ActiveOperation {
        override val request: ApplyRequest? get() = null
        override var failure: ApplyFailure? = null
    }

    private class PendingRotationWrite(
        private val journal: PendingJournal,
        override val failure: ApplyFailure?,
    ) : ActiveOperation {
        override val operation: UUID = UUID.fromString(journal.token.transactionId)
        override val request: ApplyRequest? get() = null
        override var settlement: StudioApplySettlement? = null

        suspend fun settle(
            store: StudioAppearanceStore,
            owners: StudioOwnerJournal,
            ioDispatcher: CoroutineDispatcher,
        ): ApplySettlement {
            settlement?.let { return it.settle() }
            return when (val observed = withContext(ioDispatcher) { store.load() }) {
                is StoreLoad.ReadOnly -> ApplySettlement.RecoveryRequired(SettlementProblem.Journal(observed.issue))
                is StoreLoad.Recovering -> {
                    if (observed.journal !=
                        journal
                    ) {
                        return ApplySettlement.RecoveryRequired(SettlementProblem.Unconfirmed)
                    }
                    val attached = StudioApplySettlement(store, owners, ioDispatcher, StudioOwnerWork(journal)) {}
                    settlement = attached
                    attached.settle()
                }
                StoreLoad.Empty, is StoreLoad.Ready ->
                    if (failure is ApplyFailure.Prepare) {
                        ApplySettlement.NotAccepted
                    } else {
                        ApplySettlement.RecoveryRequired(SettlementProblem.Unconfirmed)
                    }
            }
        }
    }

    private class RecoveredApply(
        override val operation: UUID,
        val purpose: RecoveryPurpose,
        override val settlement: StudioApplySettlement,
    ) : ActiveOperation {
        override val request: ApplyRequest? get() = null
    }

    private data class RecordedApply(
        val request: ApplyRequest?,
        val execution: ApplyExecution,
    )
}

private fun unsettledRotation(problem: SettlementProblem = SettlementProblem.Unconfirmed): ApplyExecution =
    ApplyExecution(ApplySettlement.RecoveryRequired(problem), null)

private fun ApplyFailure?.canRetryRotation(): Boolean =
    when (this) {
        is ApplyFailure.Native, is ApplyFailure.Commit -> true
        is ApplyFailure.Effects -> result is OwnerApply.OwnerFailed || result is OwnerApply.ReceiptFailed
        else -> false
    }

private fun PendingJournal.matchesApplyRecovery(operation: UUID): Boolean {
    if (token.transactionId != operation.toString() || token.baseRevision < 0) return false
    return when (val purpose = recovery.purpose) {
        RecoveryPurpose.UserAcceptance -> true
        is RecoveryPurpose.RotationStep -> purpose.operationId == operation
        else -> false
    }
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

    data class Rotation(
        val reason: RotationStepRestoration,
    ) : ApplyFailure

    data class Native(
        val cause: RuntimeException,
    ) : ApplyFailure
}
