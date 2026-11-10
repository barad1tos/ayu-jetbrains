package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.CloseRequest
import dev.ayuislands.studio.model.OperationStage
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.SessionInputs
import dev.ayuislands.studio.model.SessionJournalEvent
import dev.ayuislands.studio.model.SessionJournalStatus
import dev.ayuislands.studio.model.SessionNotice
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.SessionTransition
import dev.ayuislands.studio.model.StudioSessionState

internal object SessionJournals {
    fun apply(
        state: StudioSessionState,
        result: SessionJournalEvent,
        inputs: SessionInputs,
    ): SessionTransition {
        if (state.phase == SessionPhase.Loading &&
            result == SessionJournalEvent.LoadProgress
        ) {
            return SessionTransitions.handled(state)
        }
        val phase = state.phase as? SessionPhase.Processing ?: return unexpected(state)
        if (result is SessionJournalEvent.NotCommitted) {
            return notCommitted(state, phase, result.code)
        }
        return when (phase.stage) {
            OperationStage.PREPARING -> prepared(state, phase, result)
            OperationStage.APPLYING ->
                if (result ==
                    SessionJournalEvent.ReceiptSaved
                ) {
                    SessionTransitions.handled(state)
                } else {
                    unexpected(state)
                }
            OperationStage.RESOLVING_COMMIT, OperationStage.RECOVERY_REQUIRED ->
                resolution(
                    state,
                    phase,
                    result,
                    inputs,
                )
            OperationStage.ROLLING_BACK ->
                if (result ==
                    SessionJournalEvent.RecoverySaved
                ) {
                    SessionTransitions.handled(state)
                } else {
                    unexpected(state)
                }
            OperationStage.ACCEPTED_CLEANUP ->
                if (result ==
                    SessionJournalEvent.CleanupSaved
                ) {
                    SessionTransitions.handled(state)
                } else {
                    unexpected(state)
                }
        }
    }

    private fun notCommitted(
        state: StudioSessionState,
        phase: SessionPhase.Processing,
        code: String,
    ): SessionTransition =
        if (phase.stage == OperationStage.ACCEPTED_CLEANUP) {
            unexpected(state)
        } else {
            SessionEffects.advance(state.copy(notice = SessionNotice.Failure(code)), phase, OperationStage.ROLLING_BACK)
        }

    private fun prepared(
        state: StudioSessionState,
        phase: SessionPhase.Processing,
        result: SessionJournalEvent,
    ): SessionTransition =
        when (result) {
            SessionJournalEvent.Prepared -> SessionTransitions.handled(state)
            SessionJournalEvent.OutcomeUnknown -> SessionEffects.advance(state, phase, OperationStage.RECOVERY_REQUIRED)
            else -> unexpected(state)
        }

    private fun resolution(
        state: StudioSessionState,
        phase: SessionPhase.Processing,
        result: SessionJournalEvent,
        inputs: SessionInputs,
    ): SessionTransition =
        when (result) {
            is SessionJournalEvent.Committed -> {
                val record = result.record
                if (record.id != state.identity.id || record.target != state.identity.target
                ) {
                    SessionTransitions.invariant(state, "Committed session does not match the operation")
                } else if (record.generation < phase.operation.generation ||
                    record.availability != SessionAvailability.LIVE
                ) {
                    SessionTransitions.invariant(state, "Committed session generation or availability is invalid")
                } else {
                    val accepted =
                        state.copy(
                            record = record,
                            journal = SessionJournalStatus.Saved(record.generation, record.baseDocumentRevision),
                            notice = null,
                            phase = phase.copy(stage = OperationStage.ACCEPTED_CLEANUP),
                        )
                    if (phase.close ==
                        CloseRequest.NONE
                    ) {
                        SessionTransitions.handled(accepted)
                    } else {
                        SessionLifecycle.retire(accepted, phase.close, inputs)
                    }
                }
            }
            SessionJournalEvent.OutcomeUnknown -> SessionEffects.advance(state, phase, OperationStage.RECOVERY_REQUIRED)
            is SessionJournalEvent.Failed ->
                SessionEffects.advance(
                    state.copy(notice = SessionNotice.Failure(result.code)),
                    phase,
                    OperationStage.RECOVERY_REQUIRED,
                )
            SessionJournalEvent.RecoverySaved ->
                if (phase.stage ==
                    OperationStage.RECOVERY_REQUIRED
                ) {
                    SessionTransitions.handled(state)
                } else {
                    unexpected(state)
                }
            else -> unexpected(state)
        }

    private fun unexpected(state: StudioSessionState): SessionTransition =
        SessionTransitions.invariant(state, "Journal completion is invalid in this phase")
}
