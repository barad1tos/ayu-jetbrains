package dev.ayuislands.studio.runtime

import com.intellij.openapi.diagnostic.Logger
import dev.ayuislands.rotation.ClockSample
import dev.ayuislands.rotation.RotationRuntimeCommand
import dev.ayuislands.rotation.RotationRuntimeEvent
import dev.ayuislands.rotation.RotationRuntimeSnapshot
import dev.ayuislands.rotation.RotationRuntimeState
import dev.ayuislands.rotation.RotationWaitTicket
import dev.ayuislands.rotation.RuntimeFault
import dev.ayuislands.rotation.transitionRotationRuntime
import dev.ayuislands.studio.session.ApplyFailure
import dev.ayuislands.studio.session.ApplySettlement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Drives rotation from the same serialized lane as accepted appearance changes. */
internal class StudioRuntimeCoordinator(
    private val queue: Mutex,
    private val scope: CoroutineScope,
    private val observeRotation: suspend () -> RotationObservation?,
    private val clock: () -> ClockSample,
    private val scheduler: ScheduledExecutorService,
    private val execute: suspend (RotationRuntimeCommand.Transaction) -> RotationCommandOutcome,
    private val reportFault: (RuntimeFault) -> Unit,
) {
    private var rotation: RotationRuntimeState = RotationRuntimeState.Loading
    private var scheduledWake: ScheduledWake? = null
    private var active: RunningTransaction? = null

    suspend fun refreshRotation() {
        queue.withLock {
            if (rotation != RotationRuntimeState.Disposed && active == null) reconcileRotation()
        }
    }

    suspend fun disposeRotation() {
        queue.withLock {
            val transaction = active
            active = null
            transaction?.job?.cancel()
            dispatchRotation(RotationRuntimeEvent.Dispose)
        }
    }

    private suspend fun onWake(ticket: RotationWaitTicket) {
        queue.withLock {
            if (scheduledWake?.ticket != ticket) return@withLock
            scheduledWake = null
            reconcileRotation()
            dispatchRotation(RotationRuntimeEvent.TimerDue(ticket))
        }
    }

    private suspend fun reconcileRotation(completedSource: RotationRuntimeSnapshot? = null) {
        val current = observeRotation()?.snapshot
        val event =
            when {
                current == null -> RotationRuntimeEvent.AuthorityUnavailable
                completedSource == null -> RotationRuntimeEvent.Reconcile(current)
                else -> RotationRuntimeEvent.CommandFinished(completedSource, current)
            }
        dispatchRotation(event)
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
            is RotationRuntimeCommand.Transaction -> startTransaction(command)
            is RotationRuntimeCommand.ReportFault -> reportFault(command.fault)
        }
    }

    private fun startTransaction(command: RotationRuntimeCommand.Transaction) {
        check(active == null) { "A Studio rotation command is already running" }
        val job =
            scope.launch {
                val fault =
                    try {
                        execute(command).fault()
                    } catch (cancelled: CancellationException) {
                        withContext(NonCancellable) { finishTransaction(command, RuntimeFault.CommandRejected) }
                        throw cancelled
                    } catch (failure: RuntimeException) {
                        LOG.warn("Studio rotation command failed", failure)
                        RuntimeFault.CommandRejected
                    }
                finishTransaction(command, fault)
            }
        active = RunningTransaction(command, job)
    }

    private suspend fun finishTransaction(
        command: RotationRuntimeCommand.Transaction,
        fault: RuntimeFault?,
    ) {
        queue.withLock {
            if (active?.command !== command) return@withLock
            active = null
            if (fault == null) {
                reconcileRotation(command.source)
            } else {
                dispatchRotation(RotationRuntimeEvent.CommandBlocked(command.source, fault))
            }
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

    private data class RunningTransaction(
        val command: RotationRuntimeCommand.Transaction,
        val job: Job,
    )

    private companion object {
        val LOG = Logger.getInstance(StudioRuntimeCoordinator::class.java)
    }
}

private fun RotationCommandOutcome.fault(): RuntimeFault? =
    when (this) {
        is RotationCommandOutcome.Executed ->
            when (execution.settlement) {
                is ApplySettlement.Accepted -> null
                is ApplySettlement.AcceptedCleanupPending,
                is ApplySettlement.RecoveryRequired,
                -> RuntimeFault.RecoveryPending
                ApplySettlement.NotAccepted ->
                    if (execution.failure == ApplyFailure.LeaseLost) null else RuntimeFault.CommandRejected
            }
        is RotationCommandOutcome.Rejected ->
            when (admission) {
                RotationStepAdmission.Stale -> null
                is RotationStepAdmission.StoreUnavailable -> RuntimeFault.RecoveryPending
                else -> RuntimeFault.CommandRejected
            }
    }
