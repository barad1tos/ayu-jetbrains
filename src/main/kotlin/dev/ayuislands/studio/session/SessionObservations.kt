package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.AcceptanceChoices
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.AcceptancePlanResult
import dev.ayuislands.studio.model.SessionCommand
import dev.ayuislands.studio.model.SessionInputs
import dev.ayuislands.studio.model.SessionJournalStatus
import dev.ayuislands.studio.model.SessionNotice
import dev.ayuislands.studio.model.SessionObservation
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.SessionTransition
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSessionState

internal object SessionObservations {
    fun apply(
        state: StudioSessionState,
        observation: SessionObservation,
        inputs: SessionInputs,
    ): SessionTransition =
        when (state.phase) {
            SessionPhase.Retired -> SessionTransitions.ignored(state)
            SessionPhase.Loading, is SessionPhase.Processing ->
                SessionTransitions.handled(
                    state,
                    SessionCommand.QueueObservation(observation),
                )
            SessionPhase.Editing, is SessionPhase.Reviewing ->
                when (observation) {
                    is SessionObservation.NativeSurface -> {
                        val previous =
                            state.view.native.filterNot {
                                it.owner == observation.change.owner &&
                                    it.identity == observation.change.identity
                            }
                        SessionTransitions.handled(
                            state.copy(
                                view =
                                    state.view.copy(
                                        native =
                                            StudioList(
                                                previous + observation.change,
                                            ),
                                    ),
                            ),
                        )
                    }
                    is SessionObservation.ResourceInventory -> SessionTransitions.handled(state)
                    is SessionObservation.IntentRevision -> rebase(state, inputs)
                }
        }

    private fun rebase(
        state: StudioSessionState,
        inputs: SessionInputs,
    ): SessionTransition {
        val record = requireNotNull(state.record)
        val latest = StudioTargets.current(inputs.latest, record.target)
        val source = StudioCatalogPolicy.rebaseWork(inputs.latest, record.work)
        val work =
            if (latest ==
                null
            ) {
                source
            } else {
                source.copy(drafts = StudioList(source.drafts.map { StudioDraftRebase.rebase(it, latest) }))
            }
        val proposed = record.copy(baseDocumentRevision = inputs.latest.header.revision, work = work)
        val hasChanges = proposed != record
        val rebased =
            if (hasChanges) {
                val next = proposed.copy(generation = record.generation + 1)
                SessionTransitions.trackJournal(
                    state.copy(record = next, journal = SessionJournalStatus.Pending(next.generation)),
                )
            } else {
                state
            }
        val phase = state.phase
        val transition =
            if (phase is SessionPhase.Reviewing) {
                refreshReview(rebased, phase, inputs)
            } else {
                SessionTransitions.handled(rebased)
            }
        return if (hasChanges) {
            transition.copy(
                commands = StudioList(transition.commands + SessionCommand.SaveDraft(requireNotNull(rebased.record))),
            )
        } else {
            transition
        }
    }

    private fun refreshReview(
        state: StudioSessionState,
        phase: SessionPhase.Reviewing,
        inputs: SessionInputs,
    ): SessionTransition {
        val request = SessionTransitions.request(state, phase.requestId, AcceptanceChoices())
        val next =
            when (val result = StudioAcceptancePlanner.plan(request, inputs.latest, inputs.context)) {
                is AcceptancePlanResult.ReviewRequired ->
                    state.copy(
                        phase = phase.copy(items = result.items),
                        notice = null,
                    )
                is AcceptancePlanResult.Blocked ->
                    state.copy(
                        phase = SessionPhase.Editing,
                        notice = SessionNotice.Blocked(result.reasons),
                    )
                is AcceptancePlanResult.Ready ->
                    when (result.plan) {
                        AcceptancePlan.Unchanged ->
                            state.copy(
                                phase = SessionPhase.Editing,
                                notice = SessionNotice.NoChanges,
                            )
                        is AcceptancePlan.Commit ->
                            state.copy(
                                phase = phase.copy(items = StudioList(emptyList())),
                                notice = null,
                            )
                    }
            }
        return SessionTransitions.handled(next)
    }
}
