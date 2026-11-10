package dev.ayuislands.studio.session

import dev.ayuislands.font.FontApplyOrigin
import dev.ayuislands.rotation.RotationTransition
import dev.ayuislands.studio.fields.StudioRuntimeFields
import dev.ayuislands.studio.model.AcceptanceBlock
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.AcceptancePlanResult
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.ApplyFailure
import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.SessionEffect
import dev.ayuislands.studio.model.SessionJournalEvent
import dev.ayuislands.studio.model.StudioApplyUpdate
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.CommitResolution
import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.PendingJournal
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.RecoveryPurpose
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.runtime.RuntimeDispatch
import dev.ayuislands.studio.runtime.StudioRuntimeCoordinator
import dev.ayuislands.studio.runtime.StudioRuntimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

private const val DIFFERENT_OPERATION = "different-operation"

/** One acceptance entry for Settings and popup; journal flush never holds the store mutex it needs. */
internal class StudioApplyCoordinator(
    private val store: StudioAppearanceStore,
    private val environment: StudioApplyEnvironment,
    private val participants: List<StudioEffectParticipant>,
    private val transactionMutex: Mutex,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) {
    private val submissions = Mutex()
    private val recovery = StudioApplyRecovery(store, participants, environment, ioContext)
    private val results = ConcurrentHashMap<String, AcceptanceResult>()
    private val requests = ConcurrentHashMap<String, AcceptanceRequest>()
    private var active: ApplyStage? = null

    init {
        require(participants.map { it.owner }.distinct().size == participants.size)
    }

    suspend fun submit(request: AcceptanceRequest): AcceptanceResult {
        var admitted = false
        return try {
            submissions.withLock {
                results[request.transactionId]?.let { previous ->
                    return@withLock if (requests[request.transactionId] == request) previous else invalidRequest()
                }
                currentCoroutineContext().ensureActive()
                requests[request.transactionId] = request
                admitted = true
                val result = runSubmission(request)
                results[request.transactionId] = result
                result
            }
        } catch (cancelled: CancellationException) {
            if (admitted) {
                val result = outcome(request.transactionId) ?: AcceptanceResult.NotAccepted(ApplyFailure("cancelled"))
                results.putIfAbsent(request.transactionId, result)
            }
            throw cancelled
        }
    }

    suspend fun retryRecovery(operationId: String): AcceptanceResult =
        submissions.withLock {
            transactionMutex.withLock {
                withContext(NonCancellable) {
                    val pending = loadWork(operationId)
                    if (pending != null) return@withContext pending
                    val work =
                        (active as? ApplyStage.Prepared)?.work ?: return@withContext outcome(operationId)
                            ?: AcceptanceResult.NotAccepted(ApplyFailure("operation-not-found"))
                    settle(work)
                }
            }
        }

    /** Admits the entire runtime dispatch before clocks, random draws, or native preparation. */
    suspend fun executeRuntime(
        runtime: StudioRuntimeCoordinator,
        dispatch: RuntimeDispatch,
        source: StudioRuntimeSource,
        onAdmission: () -> Unit = {},
    ): RotationTransition {
        if (!runtime.claim(dispatch)) return RotationTransition(runtime.state)
        return try {
            submissions.withLock {
                transactionMutex.withLock admission@{
                    currentCoroutineContext().ensureActive()
                    if (!runtime.qualifies(dispatch)) return@admission RotationTransition(runtime.state)
                    if (active != null && dispatch != RuntimeDispatch.Recover) {
                        return@admission RotationTransition(runtime.state)
                    }
                    val execution =
                        StudioRuntimeExecution(
                            runtime,
                            source,
                            load = { withContext(ioContext) { store.load() } },
                            perform = { latest, plan, purpose, progress ->
                                applyRuntime(runtime, latest, plan, purpose, progress)
                            },
                            recover = ::recoverExisting,
                            outcome = ::outcome,
                            retireOutcome = ::acknowledgeOutcome,
                        )
                    execution.execute(dispatch, onAdmission)
                }
            }
        } finally {
            runtime.releaseDispatch(dispatch)
        }
    }

    private suspend fun applyRuntime(
        runtime: StudioRuntimeCoordinator,
        latest: StudioDocument,
        plan: AcceptancePlan.Commit,
        purpose: RecoveryPurpose.Automatic,
        progress: StudioApplyProgress?,
    ): AcceptanceResult {
        val snapshot = runtime.snapshot()
        return when (
            val prepared =
                if (purpose is RecoveryPurpose.RotationSchedule) {
                    StudioCheckpointLease.prepare(runtime, latest, plan, purpose)
                } else {
                    withContext(NonCancellable) {
                        environment.acquireRuntime(purpose.operationId, snapshot, plan)
                    }
                }
        ) {
            is StudioAcceptancePreparation.Blocked -> {
                AcceptanceResult.NotAccepted(prepared.failure)
            }

            is StudioAcceptancePreparation.Prepared -> {
                applyPrepared(
                    plan.candidate.header.transactionId,
                    latest,
                    prepared,
                    RecoveryPlan(
                        snapshot.inputs.owner.target,
                        prepared.plan.effects,
                        prepared.plan.limitations,
                        prepared.plan.rotationStep,
                        purpose,
                    ),
                    progress,
                )
            }
        }
    }

    private suspend fun recoverExisting(transactionId: String): AcceptanceResult {
        val blocked = loadWork(transactionId)
        if (blocked != null) return blocked
        val work = (active as? ApplyStage.Prepared)?.work
        return if (work != null) {
            withContext(NonCancellable) { settle(work) }
        } else {
            outcome(transactionId) ?: AcceptanceResult.NotAccepted(ApplyFailure("operation-not-found"))
        }
    }

    fun outcome(operationId: String): AcceptanceResult? = results[operationId]

    /** The host acknowledges a consumed terminal outcome; unresolved ownership must stay reachable. */
    suspend fun acknowledge(operationId: String): Boolean = submissions.withLock { acknowledgeOutcome(operationId) }

    /** Called only after consumption while the existing submission gate is held. */
    private fun acknowledgeOutcome(operationId: String): Boolean {
        if (active?.id == operationId) return false
        return when (results[operationId]) {
            null, is AcceptanceResult.RecoveryRequired, is AcceptanceResult.AcceptedCleanupPending -> {
                false
            }

            else -> {
                results.remove(operationId)
                requests.remove(operationId)
                true
            }
        }
    }

    private suspend fun runSubmission(request: AcceptanceRequest): AcceptanceResult =
        try {
            submitNew(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            val retained = active
            when {
                retained == null -> reject(request.transactionId, ApplyFailure("preparation-failed"))
                retained.id != request.transactionId -> block(request.transactionId)
                else -> AcceptanceResult.RecoveryRequired(retained.id, ApplyFailure("preparation-failed"))
            }
        }

    private suspend fun submitNew(request: AcceptanceRequest): AcceptanceResult {
        val pending = transactionMutex.withLock { settlePrior() }
        if (pending != null) return block(request.transactionId, pending)
        val flushed = environment.flush(request.session)
        if (flushed is DraftWriteResult.Failed) {
            return reject(request.transactionId, ApplyFailure(flushed.code))
        }
        return transactionMutex.withLock {
            currentCoroutineContext().ensureActive()
            val blocked = settlePrior()
            if (blocked != null) return@withLock block(request.transactionId, blocked)
            val loaded = withContext(ioContext) { store.load() }
            val latest =
                (loaded as? StoreLoad.Ready)?.document
                    ?: return@withLock block(
                        request.transactionId,
                        AcceptanceResult.Blocked(StudioList(listOf(AcceptanceBlock.READ_ONLY))),
                    )
            val context = environment.observe(request, latest)
            val planned = StudioAcceptancePlanner.plan(request, latest, context)
            environment.publish(
                StudioApplyUpdate.Effect(request.transactionId, "preflight", SessionEffect.PreflightCompleted(planned)),
            )
            when (planned) {
                is AcceptancePlanResult.Blocked -> {
                    AcceptanceResult.Blocked(planned.reasons)
                }

                is AcceptancePlanResult.ReviewRequired -> {
                    AcceptanceResult.ReviewRequired(planned.items)
                }

                is AcceptancePlanResult.Ready -> {
                    when (val plan = planned.plan) {
                        AcceptancePlan.Unchanged -> AcceptanceResult.NoChanges
                        is AcceptancePlan.Commit -> apply(request, latest, plan)
                    }
                }
            }
        }
    }

    private suspend fun apply(
        request: AcceptanceRequest,
        latest: StudioDocument,
        validated: AcceptancePlan.Commit,
    ): AcceptanceResult {
        val preparedAcceptance =
            when (val preparation = withContext(NonCancellable) { environment.prepareAcceptance(request, validated) }) {
                is StudioAcceptancePreparation.Blocked -> return reject(request.transactionId, preparation.failure)
                is StudioAcceptancePreparation.Prepared -> preparation
            }
        return applyPrepared(
            request.transactionId,
            latest,
            preparedAcceptance,
            RecoveryPlan(
                request.session.target,
                preparedAcceptance.plan.effects,
                preparedAcceptance.plan.limitations,
                preparedAcceptance.plan.rotationStep,
            ),
        )
    }

    private suspend fun applyPrepared(
        transactionId: String,
        latest: StudioDocument,
        preparation: StudioAcceptancePreparation.Prepared,
        recoveryPlan: RecoveryPlan,
        progress: StudioApplyProgress? = null,
    ): AcceptanceResult {
        val lease = preparation.lease
        active = ApplyStage.Leased(transactionId, lease, ApplyFailure("interrupted"))
        try {
            requireLease(lease)
            val plan = preparation.plan
            val prepared =
                withContext(ioContext) {
                    store.prepare(
                        latest.header.revision,
                        plan.candidate,
                        recoveryPlan,
                    )
                }
            if (prepared is PrepareResult.Rejected) {
                return withContext(NonCancellable) {
                    failedAttempt(transactionId, ApplyFailure("prepare-${prepared.issue.name.lowercase()}"))
                }
            }
            val loaded = withContext(ioContext) { store.load() }
            val journal =
                (loaded as? StoreLoad.Recovering)?.journal
                    ?: throw ApplyAttemptFailure(ApplyFailure("prepare-unconfirmed"))
            val work = StudioApplyWork(journal, lease)
            active = ApplyStage.Prepared(work)
            environment.publish(
                StudioApplyUpdate.Journal(transactionId, "prepared", SessionJournalEvent.Prepared),
            )
            environment.publish(
                StudioApplyUpdate.Effect(transactionId, "effects-started", SessionEffect.Prepared),
            )
            progress?.prepared()
            applyOwners(work, plan)
            requireLease(work.lease)
            val output = work.operations.values.flatMap { it.runtimeChanges }
            val candidate = plan.candidate.copy(runtime = StudioRuntimeFields.merge(plan.candidate.runtime, output))
            val settled = lease.settledRuntime(candidate)
            val runtime = progress?.settled(settled) ?: settled
            requireLease(work.lease)
            work.journal = work.journal.copy(candidate = work.journal.candidate.copy(runtime = runtime))
            environment.publish(
                StudioApplyUpdate.Effect(transactionId, "surfaces-settled", SessionEffect.SurfacesSettled),
            )
            requireLease(work.lease)
            val committed = withContext(ioContext) { store.commit(journal.token, runtime) }
            work.decision =
                when (committed) {
                    is CommitResult.Committed -> CommitResolution.Committed
                    is CommitResult.NotCommitted -> CommitResolution.NotCommitted
                    is CommitResult.OutcomeUnknown -> CommitResolution.Unknown
                }
            work.failure = ApplyFailure("commit-failed")
            return withContext(NonCancellable) { settle(work) }
        } catch (cancelled: CancellationException) {
            val result = withContext(NonCancellable) { failedAttempt(transactionId, ApplyFailure("cancelled")) }
            results[transactionId] = result
            throw cancelled
        } catch (failure: ApplyAttemptFailure) {
            return withContext(NonCancellable) { failedAttempt(transactionId, failure.failure) }
        } catch (_: RuntimeException) {
            return withContext(NonCancellable) { failedAttempt(transactionId, ApplyFailure("effect-failed")) }
        }
    }

    private suspend fun applyOwners(
        work: StudioApplyWork,
        plan: AcceptancePlan.Commit,
    ) {
        val input =
            StudioEffectInput(
                plan.candidate,
                work.journal.recovery.target,
                plan.nativeChoices,
                plan.glowSelection,
                plan.themeChange,
                plan.integrationSelection,
                plan.workspaceFields,
                plan.rotationStep,
                fontOrigin =
                    if (work.journal.recovery.purpose == RecoveryPurpose.UserAcceptance) {
                        FontApplyOrigin.EXPLICIT
                    } else {
                        FontApplyOrigin.AUTOMATIC
                    },
            )
        for (owner in plan.effects) {
            requireLease(work.lease)
            val operation = participants.single { it.owner == owner }.prepare(input, work.lease)
            require(operation.receipts.all { it.owner == owner })
            work.operations[owner] = operation
            work.journal = work.journal.copy(receipts = StudioList(work.journal.receipts + operation.receipts))
            if (recovery.record(work) != JournalResult.Acknowledged) {
                throw ApplyAttemptFailure(ApplyFailure("receipt-not-saved", owner))
            }
            environment.publish(
                StudioApplyUpdate.Journal(
                    work.journal.token.transactionId,
                    "receipt-$owner",
                    SessionJournalEvent.ReceiptSaved,
                ),
            )
            requireLease(work.lease)
            operation.apply()
            if (recovery.record(work) != JournalResult.Acknowledged) {
                throw ApplyAttemptFailure(ApplyFailure("receipt-not-saved", owner))
            }
        }
    }

    private suspend fun requireLease(lease: StudioRuntimeLease) {
        currentCoroutineContext().ensureActive()
        if (!lease.isValid()) throw ApplyAttemptFailure(ApplyFailure("lease-invalid"))
    }

    private suspend fun failedAttempt(
        transactionId: String,
        failure: ApplyFailure,
    ): AcceptanceResult {
        when (val stage = active) {
            is ApplyStage.Prepared -> stage.work.failure = failure
            is ApplyStage.Leased -> active = stage.copy(failure = failure)
            null -> return AcceptanceResult.NotAccepted(failure)
        }
        loadWork(transactionId)?.let { return it }
        val work =
            (active as? ApplyStage.Prepared)?.work
                ?: return outcome(transactionId) ?: AcceptanceResult.NotAccepted(failure)
        work.failure = failure
        return settle(work)
    }

    private suspend fun settlePrior(): AcceptanceResult? {
        val blocked = loadWork()
        if (blocked != null) return blocked
        val work = (active as? ApplyStage.Prepared)?.work ?: return null
        return when (val result = withContext(NonCancellable) { settle(work) }) {
            is AcceptanceResult.Accepted, is AcceptanceResult.NotAccepted -> null
            else -> result
        }
    }

    private suspend fun loadWork(expectedId: String? = null): AcceptanceResult? {
        val retained = active
        if (expectedId != null && retained != null && retained.id != expectedId) {
            return AcceptanceResult.RecoveryRequired(retained.id, ApplyFailure(DIFFERENT_OPERATION))
        }
        val loaded = withContext(ioContext) { store.load() }
        val journal = (loaded as? StoreLoad.Recovering)?.journal
        if (expectedId != null && journal != null && journal.token.transactionId != expectedId) {
            return AcceptanceResult.RecoveryRequired(journal.token.transactionId, ApplyFailure(DIFFERENT_OPERATION))
        }
        return when (loaded) {
            is StoreLoad.ReadOnly -> {
                unavailableStore(retained)
            }

            is StoreLoad.Recovering -> {
                bindJournal(loaded.journal)
            }

            is StoreLoad.Ready, StoreLoad.Empty -> {
                when (retained) {
                    is ApplyStage.Leased -> releaseUnprepared(retained)
                    else -> null
                }
            }
        }
    }

    private fun unavailableStore(retained: ApplyStage?): AcceptanceResult {
        if (retained == null) return AcceptanceResult.Blocked(StudioList(listOf(AcceptanceBlock.READ_ONLY)))
        val failure = ApplyFailure("store-unavailable")
        val result =
            if (retained is ApplyStage.Prepared && retained.work.decision == CommitResolution.Committed) {
                AcceptanceResult.AcceptedCleanupPending(
                    retained.work.journal.token.targetRevision,
                    retained.id,
                    failure,
                )
            } else {
                AcceptanceResult.RecoveryRequired(retained.id, failure)
            }
        results[retained.id] = result
        return result
    }

    private suspend fun bindJournal(journal: PendingJournal): AcceptanceResult? {
        val retained = active
        if (retained != null && retained.id != journal.token.transactionId) {
            return AcceptanceResult.RecoveryRequired(retained.id, ApplyFailure(DIFFERENT_OPERATION))
        }
        if (retained is ApplyStage.Prepared) return null
        return try {
            val lease =
                (retained as? ApplyStage.Leased)?.lease
                    ?: withContext(NonCancellable) { environment.resume(journal) }
            active = ApplyStage.Prepared(StudioApplyWork(journal, lease))
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            AcceptanceResult.RecoveryRequired(journal.token.transactionId, ApplyFailure("runtime-unavailable"))
        }
    }

    private suspend fun releaseUnprepared(stage: ApplyStage.Leased): AcceptanceResult? =
        try {
            stage.lease.release(null)
            results[stage.id] = reject(stage.id, stage.failure)
            active = null
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            environment.publish(
                StudioApplyUpdate.Journal(
                    stage.id,
                    "not-committed",
                    SessionJournalEvent.NotCommitted(stage.failure.code),
                ),
            )
            environment.publish(
                StudioApplyUpdate.Effect(stage.id, "release-failed", SessionEffect.Failed("runtime-release")),
            )
            AcceptanceResult.RecoveryRequired(stage.id, ApplyFailure("runtime-release"))
        }

    private suspend fun block(
        operationId: String,
        pending: AcceptanceResult? = null,
    ): AcceptanceResult.Blocked {
        val reasons =
            (pending as? AcceptanceResult.Blocked)?.reasons ?: StudioList(listOf(AcceptanceBlock.RECOVERY))
        environment.publish(
            StudioApplyUpdate.Effect(
                operationId,
                "preflight",
                SessionEffect.PreflightCompleted(AcceptancePlanResult.Blocked(reasons)),
            ),
        )
        return AcceptanceResult.Blocked(reasons)
    }

    private suspend fun reject(
        operationId: String,
        failure: ApplyFailure,
    ): AcceptanceResult.NotAccepted {
        environment.publish(
            StudioApplyUpdate.Journal(operationId, "not-committed", SessionJournalEvent.NotCommitted(failure.code)),
        )
        environment.publish(StudioApplyUpdate.Effect(operationId, "rollback-settled", SessionEffect.RollbackSettled))
        return AcceptanceResult.NotAccepted(failure)
    }

    private suspend fun settle(work: StudioApplyWork): AcceptanceResult {
        val result =
            try {
                recovery.settle(work)
            } catch (_: RuntimeException) {
                currentCoroutineContext().ensureActive()
                val failure = ApplyFailure("settlement-incomplete")
                if (work.decision == CommitResolution.Committed) {
                    AcceptanceResult.AcceptedCleanupPending(
                        work.journal.token.targetRevision,
                        work.journal.token.transactionId,
                        failure,
                    )
                } else {
                    AcceptanceResult.RecoveryRequired(work.journal.token.transactionId, failure)
                }
            }
        results[work.journal.token.transactionId] = result
        if (result is AcceptanceResult.Accepted || result is AcceptanceResult.NotAccepted) active = null
        return result
    }

    private fun invalidRequest(): AcceptanceResult =
        AcceptanceResult.Blocked(StudioList(listOf(AcceptanceBlock.INVALID_REQUEST)))
}

private class ApplyAttemptFailure(
    val failure: ApplyFailure,
) : RuntimeException()

private sealed interface ApplyStage {
    val id: String

    data class Leased(
        override val id: String,
        val lease: StudioRuntimeLease,
        val failure: ApplyFailure,
    ) : ApplyStage

    data class Prepared(
        val work: StudioApplyWork,
    ) : ApplyStage {
        override val id: String get() = work.journal.token.transactionId
    }
}
