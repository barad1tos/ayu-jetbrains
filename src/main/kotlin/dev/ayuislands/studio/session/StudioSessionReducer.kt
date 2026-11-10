package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.SessionEvent
import dev.ayuislands.studio.model.SessionInputs
import dev.ayuislands.studio.model.SessionTransition
import dev.ayuislands.studio.model.StudioSessionState

internal object StudioSessionReducer {
    fun reduce(
        state: StudioSessionState,
        event: SessionEvent,
        inputs: SessionInputs,
    ): SessionTransition =
        when (event) {
            is SessionEvent.Loaded -> SessionLifecycle.loaded(state, event.record, inputs)
            is SessionEvent.Intent -> SessionEdits.apply(state, event.intent, inputs)
            is SessionEvent.AcceptRequested -> SessionLifecycle.accept(state, event.operationId, inputs)
            is SessionEvent.ReviewResolved -> SessionLifecycle.review(state, event.operationId, event.choices, inputs)
            is SessionEvent.HostClose -> SessionLifecycle.close(state, event.request, inputs)
            is SessionEvent.Observation -> SessionObservations.apply(state, event.observation, inputs)
            is SessionEvent.EffectCompleted -> SessionCallbacks.effect(state, event, inputs)
            is SessionEvent.JournalCompleted -> SessionCallbacks.journal(state, event, inputs)
            is SessionEvent.RetryRecovery -> SessionLifecycle.retry(state, event.operation)
            SessionEvent.AttachUi -> SessionLifecycle.attach(state)
        }
}
