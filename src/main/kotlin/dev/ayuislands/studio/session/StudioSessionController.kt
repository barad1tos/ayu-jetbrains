package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.CloseRequest
import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.OperationStage
import dev.ayuislands.studio.model.SessionCommand
import dev.ayuislands.studio.model.SessionEvent
import dev.ayuislands.studio.model.SessionInputs
import dev.ayuislands.studio.model.SessionJournalEvent
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.StudioSessionState
import dev.ayuislands.studio.model.ViewAttachment

/** Application-owned session; the attached view can be dropped without losing work or ongoing I/O. */
internal class StudioSessionController(
    initial: StudioSessionState,
    private val inputs: (StudioSessionState) -> SessionInputs,
    scheduler: SessionScheduler,
    writer: SessionWriter,
    private val commands: (SessionCommand) -> Unit,
) {
    var state: StudioSessionState = initial
        private set

    private val journal = StudioSessionJournal(scheduler, writer, ::dispatch)
    private val pending = ArrayDeque<SessionEvent>()
    private var isDispatching = false
    private var listener: ((StudioSessionState) -> Unit)? = null

    fun dispatch(event: SessionEvent) {
        pending.addLast(event)
        if (isDispatching) return
        isDispatching = true
        try {
            while (pending.isNotEmpty()) process(pending.removeFirst())
        } finally {
            isDispatching = false
        }
    }

    fun attach(listener: (StudioSessionState) -> Unit) {
        this.listener = listener
        dispatch(SessionEvent.AttachUi)
    }

    fun detach() {
        listener = null
        dispatch(SessionEvent.HostClose(CloseRequest.NONE))
    }

    fun flush(
        record: StudioSessionRecord,
        completion: (DraftWriteResult) -> Unit,
    ) {
        if (record != state.record || state.phase == SessionPhase.Retired) {
            completion(DraftWriteResult.Failed("stale-session"))
            return
        }
        val stage = (state.phase as? SessionPhase.Processing)?.stage
        if (stage == OperationStage.ACCEPTED_CLEANUP || stage == OperationStage.RECOVERY_REQUIRED) {
            completion(DraftWriteResult.Failed("settle-operation-first"))
            return
        }
        journal.flush(record, completion)
    }

    private fun process(event: SessionEvent) {
        val previous = state
        val transition = StudioSessionReducer.reduce(state, event, inputs(state))
        state = transition.state
        transition.commands.forEach(::execute)
        reportRetiredFailure(event)
        if (state.view.attachment == ViewAttachment.ATTACHED && (state != previous || event == SessionEvent.AttachUi)) {
            listener?.invoke(state)
        }
        if (state.view.attachment == ViewAttachment.DETACHED) listener = null
    }

    private fun execute(command: SessionCommand) {
        when (command) {
            is SessionCommand.SaveDraft -> journal.schedule(command.record)
            is SessionCommand.Retire -> journal.retire(command.record)
            is SessionCommand.Quarantine -> journal.retire(command.record)
            else -> commands(command)
        }
    }

    private fun reportRetiredFailure(event: SessionEvent) {
        if (state.phase != SessionPhase.Retired || event !is SessionEvent.JournalCompleted) return
        val failure = event.result as? SessionJournalEvent.Failed ?: return
        val record = state.record ?: return
        if (event.stamp.operation == SessionTransitions.journalStamp(record)) {
            commands(SessionCommand.DraftWriteFailed(record, failure.code))
        }
    }
}
