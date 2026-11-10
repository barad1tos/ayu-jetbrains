package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.AcceptanceBlock
import dev.ayuislands.studio.model.AcceptanceChoices
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.AcceptancePlanResult
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.CloseRequest
import dev.ayuislands.studio.model.OperationStage
import dev.ayuislands.studio.model.OperationStamp
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.SessionCommand
import dev.ayuislands.studio.model.SessionInputs
import dev.ayuislands.studio.model.SessionNotice
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.SessionTransition
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.StudioSessionState
import dev.ayuislands.studio.model.ViewAttachment

internal object SessionLifecycle {
    fun loaded(
        state: StudioSessionState,
        record: StudioSessionRecord,
        inputs: SessionInputs,
    ): SessionTransition {
        if (state.phase != SessionPhase.Loading) return SessionTransitions.invariant(state, "Unexpected session load")
        if (record.id != state.identity.id ||
            record.target != state.identity.target ||
            record.availability != SessionAvailability.LIVE
        ) {
            return SessionTransitions.invariant(state, "Loaded session identity or availability does not match")
        }
        val rebased = record.copy(work = StudioCatalogPolicy.rebaseWork(inputs.latest, record.work))
        return SessionTransitions.handled(state.copy(record = rebased, phase = SessionPhase.Editing, notice = null))
    }

    fun accept(
        state: StudioSessionState,
        id: String,
        inputs: SessionInputs,
    ): SessionTransition =
        when (val phase = state.phase) {
            SessionPhase.Editing -> preflight(state, id, AcceptanceChoices(), inputs)
            is SessionPhase.Processing ->
                when (phase.stage) {
                    OperationStage.ACCEPTED_CLEANUP ->
                        SessionTransitions.handled(
                            state,
                            SessionCommand.SettleThenAccept(phase.operation, SessionTransitions.request(state, id)),
                        )
                    OperationStage.RECOVERY_REQUIRED ->
                        SessionTransitions.rejected(
                            state,
                            "Resolve the pending operation before applying",
                        )
                    OperationStage.PREPARING, OperationStage.APPLYING,
                    OperationStage.RESOLVING_COMMIT, OperationStage.ROLLING_BACK,
                    ->
                        SessionTransitions
                            .rejected(
                                state,
                                "An operation is already in progress",
                            )
                }
            is SessionPhase.Reviewing -> SessionTransitions.rejected(state, "Confirm the review before applying")
            SessionPhase.Loading, SessionPhase.Retired ->
                SessionTransitions.rejected(
                    state,
                    "The session is not editable",
                )
        }

    fun review(
        state: StudioSessionState,
        operationId: String,
        choices: AcceptanceChoices,
        inputs: SessionInputs,
    ): SessionTransition {
        val phase =
            state.phase as? SessionPhase.Reviewing
                ?: return SessionTransitions.rejected(state, "There is no active review")
        if (operationId.isBlank() || operationId == phase.requestId) {
            return SessionTransitions.invariant(state, "Review confirmation requires a fresh operation identity")
        }
        return preflight(state, operationId, choices, inputs)
    }

    private fun preflight(
        state: StudioSessionState,
        id: String,
        choices: AcceptanceChoices,
        inputs: SessionInputs,
    ): SessionTransition {
        if (id.isBlank()) return SessionTransitions.invariant(state, "Missing operation identity")
        val request = SessionTransitions.request(state, id, choices)
        return when (val result = StudioAcceptancePlanner.plan(request, inputs.latest, inputs.context)) {
            is AcceptancePlanResult.Blocked ->
                if (result.reasons.singleOrNull() == AcceptanceBlock.FONT_REFRESH) {
                    submit(state, request)
                } else {
                    SessionTransitions.handled(state.copy(notice = SessionNotice.Blocked(result.reasons)))
                }
            is AcceptancePlanResult.ReviewRequired ->
                SessionTransitions.handled(
                    state.copy(phase = SessionPhase.Reviewing(id, result.items), notice = null),
                )
            is AcceptancePlanResult.Ready ->
                when (result.plan) {
                    AcceptancePlan.Unchanged ->
                        SessionTransitions.handled(
                            state.copy(phase = SessionPhase.Editing, notice = SessionNotice.NoChanges),
                        )
                    is AcceptancePlan.Commit -> submit(state, request)
                }
        }
    }

    private fun submit(
        state: StudioSessionState,
        request: AcceptanceRequest,
    ): SessionTransition {
        val operation = OperationStamp(request.session.id, request.session.generation, request.transactionId)
        return SessionTransitions.handled(
            state.copy(
                phase = SessionPhase.Processing(OperationStage.PREPARING, operation),
                async =
                    state.async.copy(
                        journals = StudioList(listOf(SessionTransitions.journalStamp(request.session))),
                    ),
                notice = null,
            ),
            SessionCommand.Submit(request),
        )
    }

    fun close(
        state: StudioSessionState,
        close: CloseRequest,
        inputs: SessionInputs,
    ): SessionTransition {
        if (close ==
            CloseRequest.NONE
        ) {
            return SessionTransitions.handled(
                state.copy(view = state.view.copy(attachment = ViewAttachment.DETACHED)),
            )
        }
        return when (val phase = state.phase) {
            SessionPhase.Retired -> SessionTransitions.ignored(state)
            SessionPhase.Loading, SessionPhase.Editing, is SessionPhase.Reviewing -> retire(state, close, inputs)
            is SessionPhase.Processing ->
                when (phase.stage) {
                    OperationStage.PREPARING, OperationStage.APPLYING ->
                        SessionTransitions.handled(
                            state.copy(phase = phase.copy(close = close)),
                            SessionCommand.Abort(phase.operation),
                        )
                    OperationStage.RESOLVING_COMMIT, OperationStage.ROLLING_BACK ->
                        SessionTransitions.handled(
                            state.copy(phase = phase.copy(close = close)),
                        )
                    OperationStage.ACCEPTED_CLEANUP -> retire(state, close, inputs)
                    OperationStage.RECOVERY_REQUIRED -> retire(state, CloseRequest.AMBIGUOUS, inputs)
                }
        }
    }

    fun retire(
        state: StudioSessionState,
        close: CloseRequest,
        inputs: SessionInputs,
    ): SessionTransition {
        val record = state.record
        val retired =
            state.copy(
                phase = SessionPhase.Retired,
                view = state.view.copy(attachment = ViewAttachment.DETACHED),
            )
        if (record == null) return SessionTransitions.handled(retired)
        return when (close) {
            CloseRequest.CANCEL -> {
                val discarded =
                    SessionTransitions
                        .cleanRecord(
                            state,
                            inputs.latest,
                        ).copy(availability = SessionAvailability.RETIRED)
                SessionTransitions.handled(retired.copy(record = discarded), SessionCommand.Retire(discarded))
            }
            CloseRequest.AMBIGUOUS, CloseRequest.SHUTDOWN -> {
                val recovered =
                    record.copy(
                        generation = record.generation + 1,
                        availability = SessionAvailability.RECOVERED,
                    )
                SessionTransitions.handled(retired.copy(record = recovered), SessionCommand.Quarantine(recovered))
            }
            CloseRequest.NONE -> SessionTransitions.invariant(state, "Retirement requires a close request")
        }
    }

    fun retry(
        state: StudioSessionState,
        operation: OperationStamp,
    ): SessionTransition {
        if (state.phase ==
            SessionPhase.Retired
        ) {
            return SessionTransitions.handled(state, SessionCommand.Retry(operation))
        }
        val phase =
            state.phase as? SessionPhase.Processing
                ?: return SessionTransitions.rejected(state, "There is no pending recovery")
        if (phase.operation != operation) return SessionTransitions.rejected(state, "The recovery request is stale")
        return when (phase.stage) {
            OperationStage.RESOLVING_COMMIT, OperationStage.ROLLING_BACK,
            OperationStage.ACCEPTED_CLEANUP, OperationStage.RECOVERY_REQUIRED,
            ->
                SessionTransitions
                    .handled(
                        state,
                        SessionCommand.Retry(operation),
                    )
            OperationStage.PREPARING, OperationStage.APPLYING ->
                SessionTransitions.rejected(
                    state,
                    "The operation is still running",
                )
        }
    }

    fun attach(state: StudioSessionState): SessionTransition =
        if (state.phase == SessionPhase.Retired) {
            SessionTransitions.handled(state, SessionCommand.NewSession(state.identity.target))
        } else {
            SessionTransitions.handled(state.copy(view = state.view.copy(attachment = ViewAttachment.ATTACHED)))
        }
}
