package dev.ayuislands.rotation

import dev.ayuislands.studio.model.StudioAccentContinuation
import dev.ayuislands.studio.model.StudioList
import java.util.UUID

internal enum class RotationHandoffKind { FOCUS, CONTINUITY }

/** The candidate owner is admitted while its previous accepted history remains available for rollback. */
internal data class RotationContextCommit(
    override val operationId: UUID,
    override val checkpoint: RotationRuntimeCheckpoint,
    override val startedAt: ClockSample,
    val before: RotationSnapshot,
    val incoming: RotationSnapshot,
    val kind: RotationHandoffKind,
    val previous: RotationRuntimeState.Loaded? = null,
    val accent: StudioAccentContinuation? = null,
) : RotationCheckpointCommit {
    // This process-local state is absent on journal recovery;
    // persisted checkpoint obligations remain authoritative.
    val waiting: RotationTicket?
        get() =
            (previous as? RotationRuntimeState.Waiting)
                ?.ticket
                ?.takeIf { checkpoint.schedule === before.checkpoint.schedule }
                ?.copy(owner = checkpoint.owner)
}

internal data class RotationContextSettlement(
    override val commit: RotationContextCommit,
    override val progress: RotationSettlementProgress,
    override val completionId: UUID = commit.operationId,
    override val restoredWait: RotationTicket? = commit.waiting,
) : RotationSettlement

/** Context changes reuse the existing checkpoint transaction and do not simulate enable or startup. */
internal object RotationContextHandoff {
    fun prepare(
        state: RotationRuntimeState.Loaded,
        event: RotationEvent.ContextChange,
        clock: ClockSample,
    ): RotationTransition {
        val kind =
            when (event) {
                is RotationEvent.FocusChanged -> RotationHandoffKind.FOCUS
                is RotationEvent.ContinuityTransfer -> RotationHandoffKind.CONTINUITY
            }
        return prepare(state, event, clock, kind)
    }

    fun retry(
        commit: RotationContextCommit,
        operationId: UUID,
        clock: ClockSample,
    ): RotationTransition {
        val retry = commit.copy(operationId = operationId, startedAt = clock)
        return RotationTransition(
            RotationRuntimeState.Applying(retry.incoming, retry),
            StudioList(listOf(RotationCommand.PersistCheckpoint(retry))),
        )
    }

    private fun prepare(
        state: RotationRuntimeState.Loaded,
        event: RotationEvent.ContextChange,
        clock: ClockSample,
        kind: RotationHandoffKind,
    ): RotationTransition {
        if (event.generation <= state.snapshot.focusGeneration || state is RotationRuntimeState.Applying) {
            return RotationTransition(state)
        }
        if (state is RotationRuntimeState.Faulted.Loaded &&
            state.pendingOperation != null
        ) {
            return RotationTransition(state)
        }
        val inputs = event.inputs
        val before = state.snapshot
        val plan = planCheckpoint(before, inputs, clock, kind == RotationHandoffKind.CONTINUITY)
        if (plan is RotationCompletionPlan.Blocked) {
            val failure = RuntimeFault.DurableRecovery("context-schedule-overflow")
            return RotationTransition(
                RotationRuntimeState.Faulted.Loaded(before, failure),
                StudioList(listOf(RotationCommand.ReportFault(failure))),
            )
        }
        val checkpoint = (plan as RotationCompletionPlan.Ready).checkpoint
        val incoming = before.copy(inputs = inputs, focusGeneration = event.generation)
        val commit =
            RotationContextCommit(
                event.operationId,
                checkpoint,
                clock,
                before,
                incoming,
                kind,
                state,
                (event as? RotationEvent.ContinuityTransfer)?.accent,
            )
        val commands =
            buildList {
                if (state is RotationRuntimeState.Waiting) add(RotationCommand.CancelSchedule(state.ticket))
                add(RotationCommand.PersistCheckpoint(commit))
            }
        return RotationTransition(RotationRuntimeState.Applying(incoming, commit), StudioList(commands))
    }

    private fun planCheckpoint(
        before: RotationSnapshot,
        inputs: RuntimeInputs,
        clock: ClockSample,
        isContinuity: Boolean,
    ): RotationCompletionPlan {
        val continuous = eligible(before.inputs) && eligible(inputs)
        val rebound =
            before.checkpoint.copy(
                owner = inputs.owner,
                output = if (isContinuity) before.checkpoint.output?.copy(owner = inputs.owner) else null,
                retryCandidate =
                    if (isContinuity) {
                        before.checkpoint.retryCandidate?.copy(
                            owner = inputs.owner,
                        )
                    } else {
                        null
                    },
                recoveryFault = if (isContinuity) before.checkpoint.recoveryFault else null,
            )
        return when {
            isContinuity || (continuous && rebound.schedule != null && rebound.resume == RotationResume.KeepDeadline) ->
                RotationCompletionPlan.Ready(rebound)
            eligible(
                inputs,
            ) -> RotationStepPlanner.planSchedule(rebound, inputs.intent.intervalMillis, clock.epochMillis)
            else ->
                RotationCompletionPlan.Ready(
                    rebound.copy(resume = RotationResume.FullInterval(FullIntervalReason.INTERRUPTED_HANDOFF)),
                )
        }
    }

    private fun eligible(inputs: RuntimeInputs): Boolean =
        inputs.intent.enabled && inputs.availability.reasons.isEmpty()
}
