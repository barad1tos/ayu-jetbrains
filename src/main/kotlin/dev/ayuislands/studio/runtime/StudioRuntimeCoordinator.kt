package dev.ayuislands.studio.runtime

import dev.ayuislands.rotation.ClockSample
import dev.ayuislands.rotation.RotationRuntimeCommand
import dev.ayuislands.rotation.RotationRuntimeEvent
import dev.ayuislands.rotation.RotationRuntimeSnapshot
import dev.ayuislands.rotation.RotationRuntimeState
import dev.ayuislands.rotation.RotationWaitTicket
import dev.ayuislands.rotation.transitionRotationRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Drives rotation from the same serialized lane as accepted appearance changes. */
internal class StudioRuntimeCoordinator(
    private val queue: Mutex,
    private val scope: CoroutineScope,
    private val observeRotation: () -> RotationRuntimeSnapshot,
    private val clock: () -> ClockSample,
    private val scheduler: ScheduledExecutorService,
    private val publishRotation: (RotationRuntimeCommand) -> Unit,
) {
    private var rotation: RotationRuntimeState = RotationRuntimeState.Loading
    private var scheduledWake: ScheduledWake? = null

    suspend fun refreshRotation() {
        queue.withLock {
            if (rotation != RotationRuntimeState.Disposed) reconcileRotation()
        }
    }

    suspend fun disposeRotation() {
        queue.withLock { dispatchRotation(RotationRuntimeEvent.Dispose) }
    }

    private suspend fun onWake(ticket: RotationWaitTicket) {
        queue.withLock {
            if (scheduledWake?.ticket != ticket) return@withLock
            scheduledWake = null
            reconcileRotation()
            dispatchRotation(RotationRuntimeEvent.TimerDue(ticket))
        }
    }

    private fun reconcileRotation() {
        dispatchRotation(RotationRuntimeEvent.Reconcile(observeRotation()))
    }

    private fun dispatchRotation(event: RotationRuntimeEvent) {
        val transition = transitionRotationRuntime(rotation, event, clock())
        rotation = transition.state
        transition.commands.forEach(::executeRotation)
    }

    private fun executeRotation(command: RotationRuntimeCommand) {
        when (command) {
            is RotationRuntimeCommand.ArmSchedule -> armWake(command.ticket)
            is RotationRuntimeCommand.CancelSchedule -> cancelWake(command.ticket)
            is RotationRuntimeCommand.PersistCheckpoint,
            is RotationRuntimeCommand.StepDue,
            is RotationRuntimeCommand.ReportFault,
            -> publishRotation(command)
        }
    }

    private fun armWake(ticket: RotationWaitTicket) {
        check(scheduledWake == null) { "Cancel the outstanding rotation wake before arming another" }
        val delay = (ticket.delayMillis - ticket.elapsedMillis(clock().monotonicNanos)).coerceAtLeast(0L)
        val callback = Runnable { scope.launch { onWake(ticket) } }
        try {
            scheduledWake = ScheduledWake(ticket, scheduler.schedule(callback, delay, TimeUnit.MILLISECONDS))
        } catch (_: RejectedExecutionException) {
            dispatchRotation(RotationRuntimeEvent.ArmRejected(ticket))
        }
    }

    private fun cancelWake(ticket: RotationWaitTicket) {
        val current = scheduledWake ?: return
        if (current.ticket != ticket) return
        scheduledWake = null
        current.future.cancel(false)
    }

    private data class ScheduledWake(
        val ticket: RotationWaitTicket,
        val future: ScheduledFuture<*>,
    )
}
