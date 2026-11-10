package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.AcceptancePlanResult
import dev.ayuislands.studio.model.CloseRequest
import dev.ayuislands.studio.model.OperationStage
import dev.ayuislands.studio.model.SessionCommand
import dev.ayuislands.studio.model.SessionEffect
import dev.ayuislands.studio.model.SessionInputs
import dev.ayuislands.studio.model.SessionJournalStatus
import dev.ayuislands.studio.model.SessionNotice
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.SessionTransition
import dev.ayuislands.studio.model.StudioSessionState

internal object SessionEffects {
    fun apply(
        state: StudioSessionState,
        result: SessionEffect,
        inputs: SessionInputs,
    ): SessionTransition {
        val phase =
            state.phase as? SessionPhase.Processing
                ?: return SessionTransitions.invariant(state, "Surface completion outside an operation")
        return when (result) {
            is SessionEffect.PreflightCompleted -> preflight(state, phase, result.result, inputs)
            SessionEffect.Prepared ->
                advanceFrom(state, phase, OperationStage.PREPARING, OperationStage.APPLYING)
            SessionEffect.SurfacesSettled ->
                advanceFrom(state, phase, OperationStage.APPLYING, OperationStage.RESOLVING_COMMIT)
            SessionEffect.RollbackSettled ->
                when (phase.stage) {
                    OperationStage.ROLLING_BACK, OperationStage.RECOVERY_REQUIRED -> settled(state, phase, inputs)
                    else -> unexpected(state)
                }
            SessionEffect.CleanupSettled ->
                when (phase.stage) {
                    OperationStage.ACCEPTED_CLEANUP, OperationStage.RECOVERY_REQUIRED -> settled(state, phase, inputs)
                    else -> unexpected(state)
                }
            is SessionEffect.Failed -> failed(state, phase, result.code, inputs)
            is SessionEffect.ResourceFinished -> unexpected(state)
        }
    }

    private fun advanceFrom(
        state: StudioSessionState,
        phase: SessionPhase.Processing,
        expected: OperationStage,
        next: OperationStage,
    ): SessionTransition = if (phase.stage == expected) advance(state, phase, next) else unexpected(state)

    private fun preflight(
        state: StudioSessionState,
        phase: SessionPhase.Processing,
        result: AcceptancePlanResult,
        inputs: SessionInputs,
    ): SessionTransition {
        if (phase.stage != OperationStage.PREPARING) return unexpected(state)
        if (phase.close != CloseRequest.NONE) return SessionLifecycle.retire(state, phase.close, inputs)
        val next =
            when (result) {
                is AcceptancePlanResult.ReviewRequired ->
                    state.copy(phase = SessionPhase.Reviewing(phase.operation.id, result.items), notice = null)
                is AcceptancePlanResult.Blocked ->
                    state.copy(phase = SessionPhase.Editing, notice = SessionNotice.Blocked(result.reasons))
                is AcceptancePlanResult.Ready ->
                    when (result.plan) {
                        AcceptancePlan.Unchanged ->
                            state.copy(
                                phase = SessionPhase.Editing,
                                notice = SessionNotice.NoChanges,
                            )
                        is AcceptancePlan.Commit -> state
                    }
            }
        return SessionTransitions.handled(next)
    }

    private fun failed(
        state: StudioSessionState,
        phase: SessionPhase.Processing,
        code: String,
        inputs: SessionInputs,
    ): SessionTransition {
        val failed = state.copy(notice = SessionNotice.Failure(code))
        return when (phase.stage) {
            OperationStage.PREPARING -> settled(failed, phase, inputs)
            OperationStage.APPLYING -> advance(failed, phase, OperationStage.ROLLING_BACK)
            OperationStage.ROLLING_BACK -> advance(failed, phase, OperationStage.RECOVERY_REQUIRED)
            OperationStage.ACCEPTED_CLEANUP, OperationStage.RECOVERY_REQUIRED -> SessionTransitions.handled(failed)
            OperationStage.RESOLVING_COMMIT -> unexpected(state)
        }
    }

    fun advance(
        state: StudioSessionState,
        phase: SessionPhase.Processing,
        stage: OperationStage,
    ): SessionTransition = SessionTransitions.handled(state.copy(phase = phase.copy(stage = stage)))

    private fun unexpected(state: StudioSessionState): SessionTransition =
        SessionTransitions.invariant(state, "Surface completion is invalid in this phase")

    private fun settled(
        state: StudioSessionState,
        phase: SessionPhase.Processing,
        inputs: SessionInputs,
    ): SessionTransition {
        if (phase.close != CloseRequest.NONE) return SessionLifecycle.retire(state, phase.close, inputs)
        val editing = state.copy(phase = SessionPhase.Editing)
        val record = requireNotNull(state.record)
        if (phase.stage == OperationStage.ACCEPTED_CLEANUP && state.journal is SessionJournalStatus.Pending) {
            return SessionTransitions.handled(
                SessionTransitions.trackJournal(editing),
                SessionCommand.SaveDraft(record),
            )
        }
        return SessionTransitions.handled(editing)
    }
}
