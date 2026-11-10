package dev.ayuislands.studio.session

import com.intellij.openapi.application.EDT
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.ApplyFailure
import dev.ayuislands.studio.model.FontJob
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.SessionCommand
import dev.ayuislands.studio.model.SessionEvent
import dev.ayuislands.studio.model.SessionIdentity
import dev.ayuislands.studio.model.SessionInputs
import dev.ayuislands.studio.model.SessionObservation
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.StudioApplyUpdate
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.StudioSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** Application lifetime command execution. The Settings host awaits acceptance in its own modal coroutine. */
internal class StudioSessionBinding(
    initial: StudioSessionState,
    private val inputs: (StudioSessionState) -> SessionInputs,
    private val scheduler: SessionScheduler,
    private val writer: SessionWriter,
    coordinator: (suspend (StudioApplyUpdate) -> Unit) -> StudioApplyCoordinator,
    private val loadRecord: suspend () -> StudioSessionRecord,
    private val resource: (FontJob) -> Unit,
    private val ids: () -> String,
) {
    var controller = createController(initial)
        private set
    private var progress = StudioSessionProgress(controller, ::accepted)
    private val apply = coordinator(::publish)
    private var pending: SessionCommand? = null
    private var running: Job? = null
    private val observations = mutableListOf<SessionObservation>()
    var lastOutcome: AcceptanceResult? = null
        private set
    val document: StudioDocument get() = inputs(controller.state).latest
    val observation get() = inputs(controller.state).context

    fun nextId(): String = ids()

    suspend fun load() {
        val state = withContext(Dispatchers.EDT) { controller.state }
        if (state.phase == SessionPhase.Loading) {
            val record = loadRecord()
            withContext(Dispatchers.EDT) {
                controller.dispatch(SessionEvent.Loaded(record))
                replayObservations()
            }
        } else if (state.phase == SessionPhase.Retired) {
            withContext(Dispatchers.EDT) { reopen() }
        }
    }

    /** Executes only requests emitted by the reducer; it never rebuilds one from displayed controls. */
    suspend fun execute(): AcceptanceResult? {
        val operationJob = currentCoroutineContext()[Job]
        val command =
            withContext(Dispatchers.EDT) {
                pending.also {
                    pending = null
                    if (it != null) running = operationJob
                }
            } ?: return null
        return try {
            when (command) {
                is SessionCommand.Submit -> consume(command.request.transactionId) { apply.submit(command.request) }
                is SessionCommand.Retry -> consume(command.operation.id) { apply.retryRecovery(command.operation.id) }
                is SessionCommand.SettleThenAccept -> settle(command)
                else -> error("Command does not represent acceptance")
            }
        } finally {
            withContext(NonCancellable + Dispatchers.EDT) {
                running = null
                replayObservations()
            }
        }
    }

    private suspend fun consume(
        operationId: String,
        action: suspend () -> AcceptanceResult,
    ): AcceptanceResult {
        val result =
            try {
                action()
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    recordOutcome(
                        operationId,
                        apply.outcome(operationId) ?: AcceptanceResult.NotAccepted(ApplyFailure("cancelled")),
                    )
                }
                throw cancelled
            }
        withContext(NonCancellable) { recordOutcome(operationId, result) }
        return result
    }

    /** Retains the host result before releasing settled coordinator bookkeeping. */
    private suspend fun recordOutcome(
        operationId: String,
        result: AcceptanceResult,
    ) {
        withContext(Dispatchers.EDT) { lastOutcome = result }
        apply.acknowledge(operationId)
    }

    private suspend fun settle(command: SessionCommand.SettleThenAccept): AcceptanceResult {
        val result = consume(command.operation.id) { apply.retryRecovery(command.operation.id) }
        return if (result is AcceptanceResult.Accepted || result == AcceptanceResult.NoChanges) {
            consume(command.request.transactionId) { apply.submit(command.request) }
        } else {
            result
        }
    }

    private suspend fun publish(update: StudioApplyUpdate) = withContext(Dispatchers.EDT) { progress.receive(update) }

    private fun accepted(committed: StudioDocument) {
        check(document == committed) { "Accepted document must be published before its session update" }
    }

    private fun createController(state: StudioSessionState) =
        StudioSessionController(state, inputs, scheduler, writer, ::command)

    private fun command(command: SessionCommand) {
        when (command) {
            is SessionCommand.Submit, is SessionCommand.Retry, is SessionCommand.SettleThenAccept -> {
                check(pending == null) { "An acceptance command is already waiting" }
                pending = command
            }

            is SessionCommand.Abort -> {
                running?.cancel()
            }

            is SessionCommand.StartFontJob -> {
                resource(command.job)
            }

            is SessionCommand.QueueObservation -> {
                observations.add(command.observation)
            }

            is SessionCommand.DraftWriteFailed -> {
                lastOutcome = AcceptanceResult.NotAccepted(ApplyFailure(command.code))
            }

            is SessionCommand.NewSession -> {
                reopen()
            }

            is SessionCommand.SaveDraft, is SessionCommand.Retire, is SessionCommand.Quarantine -> {
                error("Draft journals belong to the session controller")
            }
        }
    }

    private fun reopen() {
        val previous = controller.state
        check(previous.phase == SessionPhase.Retired)
        val clean =
            SessionTransitions.cleanRecord(previous, document).copy(
                id = nextId(),
                generation = 0,
                availability = SessionAvailability.LIVE,
            )
        val initial =
            StudioSessionState(
                SessionIdentity(clean.id, clean.target, previous.identity.targetGeneration),
                clean,
                SessionPhase.Editing,
            )
        controller = createController(initial)
        progress = StudioSessionProgress(controller, ::accepted)
        lastOutcome = null
        replayObservations()
    }

    private fun replayObservations() {
        if (controller.state.phase == SessionPhase.Loading || controller.state.phase is SessionPhase.Processing) return
        val ready = observations.toList()
        observations.clear()
        ready.forEach { controller.dispatch(SessionEvent.Observation(it)) }
    }
}
