package dev.ayuislands.studio.runtime

import dev.ayuislands.rotation.ClockSample
import dev.ayuislands.rotation.RotationCommitOutcome
import dev.ayuislands.rotation.RotationCompletionPlan
import dev.ayuislands.rotation.RotationEvent
import dev.ayuislands.rotation.RotationLoadOutcome
import dev.ayuislands.rotation.RotationRuntimeMachine
import dev.ayuislands.rotation.RotationRuntimeState
import dev.ayuislands.rotation.RotationScheduleCommit
import dev.ayuislands.rotation.RotationScheduleSettlement
import dev.ayuislands.rotation.RotationSettlement
import dev.ayuislands.rotation.RotationSettlementProgress
import dev.ayuislands.rotation.RotationSnapshot
import dev.ayuislands.rotation.RotationStepCommit
import dev.ayuislands.rotation.RotationStepPlanner
import dev.ayuislands.rotation.RotationStepSettlement
import dev.ayuislands.rotation.RotationTicket
import dev.ayuislands.rotation.RotationTransition
import dev.ayuislands.rotation.RuntimeOwner
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.ApplyFailure
import dev.ayuislands.studio.model.SessionEffect
import dev.ayuislands.studio.model.StudioApplyUpdate
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioRuntimeState
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.session.StudioContinuationPolicy
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Runs on the existing owner lane; the session coordinator alone owns persistence and native effects. */
internal class StudioRuntimeCoordinator private constructor(
    initial: RotationRuntimeState,
    private var focus: FocusSnapshot,
) {
    constructor(focus: FocusSnapshot) : this(RotationRuntimeState.Loading(), focus)

    constructor(initial: RotationRuntimeState.Loaded, focus: FocusSnapshot) : this(
        initial as RotationRuntimeState,
        focus,
    )

    var state: RotationRuntimeState = initial
        private set
    private var acceptance: RuntimeAcceptance? = null
    private var pendingDue: RotationTicket? = null
    private var ownerFocus = focus

    /** Admission coalesces only unmaterialized tickets; the shared transaction gate still owns all work. */
    fun claim(dispatch: RuntimeDispatch): Boolean {
        if (dispatch is RuntimeDispatch.ContextChange) observeFocus(dispatch.focus)
        if (dispatch is RuntimeDispatch.NativeChange) observeFocus(dispatch.focus)
        if (dispatch !is RuntimeDispatch.Due) return true
        if (pendingDue != null || !qualifies(dispatch)) return false
        pendingDue = dispatch.ticket
        return true
    }

    fun releaseDispatch(dispatch: RuntimeDispatch) {
        if (dispatch is RuntimeDispatch.Due && pendingDue == dispatch.ticket) pendingDue = null
    }

    fun qualifies(dispatch: RuntimeDispatch): Boolean {
        if (acceptance != null) return false
        return when (dispatch) {
            is RuntimeDispatch.Start -> state is RotationRuntimeState.Loading

            is RuntimeDispatch.NativeChange -> {
                val loaded = idleSnapshot(dispatch.focus)
                loaded != null &&
                    isCurrentOwner(loaded.inputs.owner)
            }

            is RuntimeDispatch.ContextChange -> {
                val loaded = idleSnapshot(dispatch.focus)
                loaded != null &&
                    dispatch.focus.generation > loaded.focusGeneration
            }

            is RuntimeDispatch.Due -> {
                val waiting = state as? RotationRuntimeState.Waiting
                waiting?.ticket == dispatch.ticket && isCurrentOwner(dispatch.ticket.owner)
            }

            is RuntimeDispatch.Availability -> {
                state is RotationRuntimeState.Loaded && dispatch.generation > snapshot().observationGeneration
            }

            RuntimeDispatch.Recover -> {
                state is RotationRuntimeState.Loaded || state is RotationRuntimeState.Faulted.Unloaded
            }
        }
    }

    private fun idleSnapshot(observed: FocusSnapshot): RotationSnapshot? {
        if (observed != focus || state is RotationRuntimeState.Applying) return null
        if ((state as? RotationRuntimeState.Faulted.Loaded)?.pendingOperation != null) return null
        return (state as? RotationRuntimeState.Loaded)?.snapshot
    }

    fun isCurrentOwner(owner: RuntimeOwner): Boolean =
        (state as? RotationRuntimeState.Loaded)?.snapshot?.inputs?.owner == owner &&
            focus.target == owner.target &&
            ownerFocus == focus

    fun snapshot(): RotationSnapshot = checkNotNull(state as? RotationRuntimeState.Loaded).snapshot

    fun project(
        document: StudioDocument,
        observation: RuntimeDispatch.Start,
        target: StudioTarget = focus.target,
    ): RotationLoadOutcome {
        val projected = StudioRotationProjection.load(document, target, observation)
        return if (projected is RotationLoadOutcome.Ready && target == focus.target) {
            projected.copy(snapshot = projected.snapshot.copy(focusGeneration = focus.generation))
        } else {
            projected
        }
    }

    fun initialize(
        outcome: RotationLoadOutcome,
        observation: RuntimeDispatch.Start,
        operationId: UUID,
        clock: ClockSample,
    ): RotationTransition {
        val begin =
            if (state is RotationRuntimeState.Faulted.Unloaded) {
                RotationEvent.RetryRecovery(operationId)
            } else {
                RotationEvent.Initialize(operationId)
            }
        transition(begin, clock)
        transition(
            RotationEvent.AvailabilityObserved(observation.generation, observation.availability, operationId),
            clock,
        )
        return transition(RotationEvent.Loaded(operationId, outcome), clock)
    }

    fun accept(
        event: RotationEvent,
        clock: ClockSample,
    ): RotationTransition = transition(event, clock)

    /** Rebinds only facts loaded from the existing journal after the prior coordinator was lost. */
    fun restore(
        outcome: RotationLoadOutcome,
        operationId: UUID,
        clock: ClockSample,
    ): RotationTransition {
        state = RotationRuntimeState.Loading(operationId)
        return transition(RotationEvent.Loaded(operationId, outcome), clock)
    }

    fun prepare(
        request: RuntimeAcceptanceRequest,
        clock: ClockSample,
        randomHex: String? = null,
    ): RuntimePreparationPlan {
        if (acceptance != null) return RuntimePreparationPlan.Blocked(ApplyFailure("runtime-acceptance-pending"))
        val previous =
            state as? RotationRuntimeState.Loaded
                ?: return RuntimePreparationPlan.Blocked(ApplyFailure("runtime-unavailable"))
        val result = RuntimeAcceptancePolicy.plan(request, previous.snapshot, focus, clock, randomHex)
        if (result is RuntimePreparationPlan.Ready) {
            acceptance =
                RuntimeAcceptance.Prepared(
                    request.acceptance.transactionId,
                    request.operationId,
                    result.plan,
                    focus,
                    previous,
                )
        }
        return result
    }

    fun isValid(transactionId: String): Boolean {
        val pending = acceptance as? RuntimeAcceptance.Prepared ?: return false
        return pending.transactionId == transactionId && pending.focus == focus
    }

    fun observeFocus(observed: FocusSnapshot) {
        if (observed.generation > focus.generation) focus = observed
    }

    fun settledRuntime(
        transactionId: String,
        candidate: StudioDocument,
        clock: ClockSample,
    ): StudioRuntimeState {
        val pending = checkNotNull(acceptance as? RuntimeAcceptance.Prepared)
        check(pending.transactionId == transactionId)
        val step = pending.plan.rotationStep
        val runtime =
            if (step == null) {
                candidate.runtime
            } else {
                val checkpoint = checkNotNull(candidate.runtime.rotation)
                val inputs =
                    RuntimeAcceptancePolicy.projectInputs(
                        candidate,
                        checkpoint.owner.target,
                        pending.previous.snapshot.inputs,
                    )
                when (
                    val completed = RotationStepPlanner.planCompletion(checkpoint, inputs, step, clock.epochMillis)
                ) {
                    is RotationCompletionPlan.Ready ->
                        StudioContinuationPolicy.releaseAccent(
                            candidate.runtime.copy(rotation = completed.checkpoint),
                        )
                    is RotationCompletionPlan.Blocked -> error("Rotation completion blocked: ${completed.reason}")
                }
            }
        acceptance = pending.copy(completedAt = clock)
        return runtime
    }

    fun release(
        transactionId: String,
        accepted: StudioDocument?,
        clock: ClockSample,
    ): RotationTransition {
        val pending = acceptance
        if (pending?.transactionId != transactionId) return RotationTransition(state)
        if (accepted != null) return observe(StudioApplyUpdate.Committed(transactionId, accepted), clock)
        if (pending is RuntimeAcceptance.Prepared) {
            state = pending.previous
            acceptance = null
        }
        return RotationTransition(state)
    }

    fun observe(
        update: StudioApplyUpdate,
        clock: ClockSample,
    ): RotationTransition {
        val pending = acceptance ?: return RotationTransition(state)
        if (pending.transactionId != update.operationId) return RotationTransition(state)
        return when (update) {
            is StudioApplyUpdate.Committed -> {
                if (pending is RuntimeAcceptance.Prepared) {
                    committed(pending, update.document, clock)
                } else {
                    RotationTransition(state)
                }
            }

            is StudioApplyUpdate.Effect -> {
                if (update.result == SessionEffect.CleanupSettled && pending is RuntimeAcceptance.Accepted) {
                    val event = RotationEvent.CommitResolved(pending.operationId, RotationCommitOutcome.Settled)
                    val result = transition(event, clock)
                    acceptance = null
                    result
                } else {
                    RotationTransition(state)
                }
            }

            is StudioApplyUpdate.Journal -> {
                RotationTransition(state)
            }
        }
    }

    private fun committed(
        pending: RuntimeAcceptance.Prepared,
        document: StudioDocument,
        clock: ClockSample,
    ): RotationTransition {
        val checkpoint = checkNotNull(document.runtime.rotation)
        val previous = pending.previous.snapshot
        val snapshot =
            previous.copy(
                checkpoint = checkpoint,
                inputs = RuntimeAcceptancePolicy.projectInputs(document, checkpoint.owner.target, previous.inputs),
                consecutiveFailures = 0,
            )
        val settlement = settlement(pending, snapshot, clock)
        val result = transition(RotationEvent.UserAccepted(snapshot, settlement), clock)
        acceptance = RuntimeAcceptance.Accepted(pending.transactionId, pending.operationId)
        return result
    }

    private fun settlement(
        pending: RuntimeAcceptance.Prepared,
        snapshot: RotationSnapshot,
        clock: ClockSample,
    ): RotationSettlement {
        val completedAt = checkNotNull(pending.completedAt)
        val step = pending.plan.rotationStep
        return if (step != null) {
            RotationStepSettlement(
                RotationStepCommit(pending.operationId, snapshot.checkpoint, completedAt, step),
                RotationSettlementProgress.CLEANUP_PENDING,
            )
        } else {
            RotationScheduleSettlement(
                RotationScheduleCommit(pending.operationId, snapshot.checkpoint, completedAt),
                RotationSettlementProgress.CLEANUP_PENDING,
                restoredWait = restoredWait(snapshot, clock),
            )
        }
    }

    private fun restoredWait(
        snapshot: RotationSnapshot,
        clock: ClockSample,
    ): RotationTicket? {
        val schedule = snapshot.checkpoint.schedule ?: return null
        val remaining =
            if (schedule.deadlineEpochMillis <=
                clock.epochMillis
            ) {
                0L
            } else {
                schedule.deadlineEpochMillis - clock.epochMillis
            }
        return RotationTicket(
            snapshot.inputs.owner,
            schedule.scheduleSerial,
            minOf(remaining, TimeUnit.DAYS.toMillis(1)),
            clock.monotonicNanos,
            remaining,
        )
    }

    private fun transition(
        event: RotationEvent,
        clock: ClockSample,
    ): RotationTransition =
        RotationRuntimeMachine.transition(state, event, clock).also { result ->
            state = result.state
            val current = state as? RotationRuntimeState.Loaded
            if (current != null && current.snapshot.focusGeneration > ownerFocus.generation) {
                ownerFocus = FocusSnapshot(current.snapshot.inputs.owner.target, current.snapshot.focusGeneration)
            }
        }
}

private sealed interface RuntimeAcceptance {
    val transactionId: String
    val operationId: UUID

    data class Prepared(
        override val transactionId: String,
        override val operationId: UUID,
        val plan: AcceptancePlan.Commit,
        val focus: FocusSnapshot,
        val previous: RotationRuntimeState.Loaded,
        val completedAt: ClockSample? = null,
    ) : RuntimeAcceptance

    data class Accepted(
        override val transactionId: String,
        override val operationId: UUID,
    ) : RuntimeAcceptance
}
