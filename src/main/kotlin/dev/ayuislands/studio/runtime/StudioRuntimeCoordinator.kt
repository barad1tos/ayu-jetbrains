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
import kotlinx.coroutines.CoroutineStart
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
import java.util.concurrent.atomic.AtomicReference

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
    private val commandSlot = AtomicReference<CommandSlot>(CommandSlot.Idle)
    private var refreshPending = false

    suspend fun refreshRotation() {
        queue.withLock {
            when (commandSlot.get()) {
                CommandSlot.Idle -> reconcileRotation()
                is CommandSlot.Running -> refreshPending = true
                CommandSlot.Disposed -> Unit
            }
        }
    }

    suspend fun disposeRotation() {
        val previous = commandSlot.getAndSet(CommandSlot.Disposed)
        (previous as? CommandSlot.Running)?.job?.cancel()
        queue.withLock {
            refreshPending = false
            dispatchRotation(RotationRuntimeEvent.Dispose)
        }
    }

    private suspend fun onWake(ticket: RotationWaitTicket) {
        queue.withLock {
            if (commandSlot.get() == CommandSlot.Disposed || scheduledWake?.ticket != ticket) return@withLock
            scheduledWake = null
            reconcileRotation()
            dispatchRotation(RotationRuntimeEvent.TimerDue(ticket))
        }
    }

    private suspend fun reconcileRotation() {
        val current = readAuthority()
        if (commandSlot.get() == CommandSlot.Disposed) return
        val event =
            if (current == null) {
                RotationRuntimeEvent.AuthorityUnavailable
            } else {
                RotationRuntimeEvent.Reconcile(current)
            }
        dispatchRotation(event)
    }

    private suspend fun readAuthority(): RotationRuntimeSnapshot? =
        try {
            observeRotation()?.snapshot
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            LOG.warn("Studio rotation authority read failed", failure)
            null
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
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    finishTransaction(command, executeCommand(command))
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) {
                        finishTransaction(command, RuntimeFault.RecoveryPending) { null }
                    }
                    throw cancelled
                }
            }
        check(commandSlot.get() !is CommandSlot.Running) { "A Studio rotation command is already running" }
        if (commandSlot.compareAndSet(CommandSlot.Idle, CommandSlot.Running(command, job))) {
            job.start()
        } else {
            job.cancel()
        }
    }

    private suspend fun executeCommand(command: RotationRuntimeCommand.Transaction): RuntimeFault? =
        try {
            execute(command).fault()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            LOG.warn("Studio rotation command failed", failure)
            RuntimeFault.RecoveryPending
        }

    private suspend fun finishTransaction(
        command: RotationRuntimeCommand.Transaction,
        fault: RuntimeFault?,
        readback: suspend () -> RotationRuntimeSnapshot? = ::readAuthority,
    ) {
        queue.withLock {
            val running = commandSlot.get() as? CommandSlot.Running ?: return@withLock
            if (running.command !== command) return@withLock
            val current = readback()
            if (!commandSlot.compareAndSet(running, CommandSlot.Idle)) return@withLock
            val requested = refreshPending
            refreshPending = false
            if (current != null && (fault == null || current != command.source)) {
                dispatchRotation(RotationRuntimeEvent.CommandFinished(command.source, current))
                return@withLock
            }
            val reason = fault ?: RuntimeFault.RecoveryPending
            dispatchRotation(RotationRuntimeEvent.CommandBlocked(command.source, reason))
            if (requested && current != null && reason == RuntimeFault.RecoveryPending) {
                dispatchRotation(RotationRuntimeEvent.Reconcile(current))
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

    private sealed interface CommandSlot {
        data object Idle : CommandSlot

        data object Disposed : CommandSlot

        data class Running(
            val command: RotationRuntimeCommand.Transaction,
            val job: Job,
        ) : CommandSlot
    }

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
