package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.CallbackStamp
import dev.ayuislands.studio.model.SessionEffect
import dev.ayuislands.studio.model.SessionEvent
import dev.ayuislands.studio.model.SessionInputs
import dev.ayuislands.studio.model.SessionJournalEvent
import dev.ayuislands.studio.model.SessionJournalStatus
import dev.ayuislands.studio.model.SessionNotice
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.SessionTransition
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSessionState

internal object SessionCallbacks {
    fun guard(
        state: StudioSessionState,
        stamp: CallbackStamp,
    ): SessionTransition? {
        if (state.phase == SessionPhase.Retired ||
            stamp in state.async.completed
        ) {
            return SessionTransitions.ignored(state)
        }
        if (stamp.operation.session != state.identity.id) return SessionTransitions.ignored(state)
        val processing = state.phase as? SessionPhase.Processing
        if (processing?.operation == stamp.operation ||
            stamp.operation in state.async.journals ||
            state.async.jobs.any { it.operation == stamp.operation }
        ) {
            return null
        }
        if (stamp.operation.generation < (state.record?.generation ?: 0)) return SessionTransitions.ignored(state)
        return SessionTransitions.invariant(state, "Completion has no matching operation")
    }

    fun effect(
        state: StudioSessionState,
        event: SessionEvent.EffectCompleted,
        inputs: SessionInputs,
    ): SessionTransition {
        guard(state, event.stamp)?.let { return it }
        val transition =
            if (event.result is SessionEffect.ResourceFinished) {
                resource(
                    state,
                    event,
                )
            } else {
                SessionEffects.apply(state, event.result, inputs)
            }
        return SessionTransitions.completed(transition, event.stamp)
    }

    private fun resource(
        state: StudioSessionState,
        event: SessionEvent.EffectCompleted,
    ): SessionTransition {
        val job =
            state.async.jobs.singleOrNull { it.operation == event.stamp.operation }
                ?: return SessionTransitions.invariant(state, "Resource completion has no matching job")
        val status = (event.result as SessionEffect.ResourceFinished).status
        return SessionTransitions.handled(
            state.copy(
                async =
                    state.async.copy(
                        jobs =
                            StudioList(
                                state.async.jobs.filterNot {
                                    it ==
                                        job
                                },
                            ),
                    ),
                notice = SessionNotice.Resource(job.fontId, status),
            ),
        )
    }

    fun journal(
        state: StudioSessionState,
        event: SessionEvent.JournalCompleted,
        inputs: SessionInputs,
    ): SessionTransition {
        guard(state, event.stamp)?.let { return it }
        val transition =
            if (event.stamp.operation in state.async.journals &&
                (event.result is SessionJournalEvent.Saved || event.result is SessionJournalEvent.Failed)
            ) {
                saved(state, event)
            } else {
                SessionJournals.apply(state, event.result, inputs)
            }
        return SessionTransitions.completed(transition, event.stamp)
    }

    private fun saved(
        state: StudioSessionState,
        event: SessionEvent.JournalCompleted,
    ): SessionTransition {
        val operation = event.stamp.operation
        val retained =
            if (event.result is SessionJournalEvent.Failed &&
                state.record?.generation == operation.generation
            ) {
                state.async.journals
            } else {
                StudioList(state.async.journals.filterNot { it == operation })
            }
        val async = state.async.copy(journals = retained)
        if (state.record?.generation !=
            operation.generation
        ) {
            return SessionTransitions.handled(state.copy(async = async))
        }
        val journal =
            when (val result = event.result) {
                is SessionJournalEvent.Saved -> SessionJournalStatus.Saved(operation.generation, result.revision)
                is SessionJournalEvent.Failed -> SessionJournalStatus.Failed(operation.generation, result.code)
                else -> return SessionTransitions.invariant(state, "Unexpected draft journal result")
            }
        return SessionTransitions.handled(state.copy(async = async, journal = journal))
    }
}
