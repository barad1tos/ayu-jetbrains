package dev.ayuislands.studio.runtime

import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.rotation.ClockSample
import dev.ayuislands.rotation.FullIntervalReason
import dev.ayuislands.rotation.RotationAvailability
import dev.ayuislands.rotation.RotationIntent
import dev.ayuislands.rotation.RotationResume
import dev.ayuislands.rotation.RotationRuntimeCheckpoint
import dev.ayuislands.rotation.RotationRuntimeCommand
import dev.ayuislands.rotation.RotationRuntimeSnapshot
import dev.ayuislands.rotation.RotationSchedule
import dev.ayuislands.rotation.RotationTimerGeneration
import dev.ayuislands.rotation.RuntimeFault
import dev.ayuislands.rotation.RuntimeOwner
import dev.ayuislands.studio.catalogTestDocument
import dev.ayuislands.studio.model.AcceptanceLease
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.session.ApplyExecution
import dev.ayuislands.studio.session.ApplyFailure
import dev.ayuislands.studio.session.ApplySettlement
import dev.ayuislands.studio.session.SettlementProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.Delayed
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class StudioRuntimeCoordinatorTest {
    @Test
    fun `duplicate observations retain one appointment and a wake is consumed once`() =
        runBlocking {
            val fixture = Fixture(this)
            fixture.coordinator.refreshRotation()
            fixture.coordinator.refreshRotation()
            val wake = fixture.scheduler.wakes.single()
            assertEquals(6 * HOUR, wake.delayMillis)

            fixture.moveTime(wallHours = 48, elapsedHours = 6)
            wake.run()
            wake.run()
            drainCommands()

            assertEquals(listOf(RotationRuntimeCommand.StepDue(fixture.accepted)), fixture.delivered)
            assertEquals(4, fixture.reads)
            assertEquals(1, fixture.scheduler.wakes.size)
        }

    @Test
    fun `a callback queued during Apply reads the newly accepted Off setting`() =
        runBlocking {
            val fixture = Fixture(this)
            fixture.coordinator.refreshRotation()
            val wake = fixture.scheduler.wakes.single()
            fixture.moveTime(6, 6)

            fixture.applyQueue.withLock {
                wake.run()
                yield()
                assertEquals(1, fixture.reads)
                fixture.accepted = fixture.accepted.copy(intent = fixture.accepted.intent.copy(enabled = false))
            }
            yield()
            wake.run()
            yield()

            assertEquals(2, fixture.reads)
            assertEquals(emptyList(), fixture.delivered)
            assertEquals(1, fixture.scheduler.wakes.size)
        }

    @Test
    fun `focus change cancels the previous wake and retains the remaining live wait`() =
        runBlocking {
            val fixture = Fixture(this)
            fixture.coordinator.refreshRotation()
            val old = fixture.scheduler.wakes.single()
            fixture.moveTime(wallHours = 48, elapsedHours = 2)
            val owner = RuntimeOwner(StudioTarget.Project(StudioProjectKey("/other")), 3)
            fixture.accepted =
                fixture.accepted.copy(
                    checkpoint = fixture.accepted.checkpoint.copy(owner = owner),
                    generation = fixture.accepted.generation.copy(target = 9),
                )
            fixture.coordinator.refreshRotation()
            val successor = fixture.scheduler.wakes.last()
            assertTrue(old.isCancelled)
            assertEquals(4 * HOUR, successor.delayMillis)

            fixture.moveTime(48, 6)
            old.run()
            successor.run()
            drainCommands()
            assertEquals(4, fixture.reads)
            assertEquals(listOf(RotationRuntimeCommand.StepDue(fixture.accepted)), fixture.delivered)
        }

    @Test
    fun `arming subtracts time already spent since the appointment was planned`() =
        runBlocking {
            val fixture = Fixture(this)
            fixture.clockSamples.add(ClockSample(0, 0))
            fixture.clockSamples.add(ClockSample(48 * HOUR, TimeUnit.HOURS.toNanos(2)))
            fixture.coordinator.refreshRotation()

            assertEquals(
                4 * HOUR,
                fixture.scheduler.wakes
                    .single()
                    .delayMillis,
            )
            assertEquals(emptyList(), fixture.delivered)
        }

    @Test
    fun `long appointments arm a second guard before issuing work`() =
        runBlocking {
            val fixture = Fixture(this)
            fixture.accepted =
                fixture.accepted.copy(
                    checkpoint =
                        fixture.accepted.checkpoint.copy(
                            schedule = RotationSchedule(0, 48 * HOUR, 48 * HOUR, 1),
                        ),
                )
            fixture.coordinator.refreshRotation()
            val guard = fixture.scheduler.wakes.single()
            assertEquals(24 * HOUR, guard.delayMillis)

            fixture.moveTime(24, 24)
            guard.run()
            yield()
            val finalWake = fixture.scheduler.wakes.last()
            assertEquals(2, fixture.scheduler.wakes.size)
            assertEquals(24 * HOUR, finalWake.delayMillis)
            assertEquals(emptyList(), fixture.delivered)

            fixture.moveTime(48, 48)
            guard.run()
            finalWake.run()
            drainCommands()
            assertEquals(listOf(RotationRuntimeCommand.StepDue(fixture.accepted)), fixture.delivered)
        }

    @Test
    fun `checkpoint proposals are handed off and only a later accepted appointment arms a wake`() =
        runBlocking {
            val fixture = Fixture(this)
            val settlement = CompletableDeferred<RotationCommandOutcome>()
            fixture.execute = { settlement.await() }
            fixture.accepted =
                fixture.accepted.copy(
                    checkpoint =
                        fixture.accepted.checkpoint.copy(
                            resume = RotationResume.FullInterval(FullIntervalReason.FOLLOW_SYSTEM),
                        ),
                )
            fixture.coordinator.refreshRotation()
            fixture.coordinator.refreshRotation()
            drainCommands()
            assertEquals(0, fixture.scheduler.wakes.size)
            val requested = assertIs<RotationRuntimeCommand.PersistCheckpoint>(fixture.delivered.single())
            assertEquals(fixture.accepted, requested.source)

            fixture.accepted = fixture.accepted.copy(checkpoint = requested.proposed.checkpoint)
            fixture.coordinator.refreshRotation()
            assertEquals(emptyList(), fixture.scheduler.wakes)
            settlement.complete(fixture.acceptedResult())
            drainCommands()
            assertEquals(
                6 * HOUR,
                fixture.scheduler.wakes
                    .single()
                    .delayMillis,
            )
        }

    @Test
    fun `an unresolved transaction keeps the timer stopped until authority is ready`() =
        runBlocking {
            val fixture = Fixture(this)
            fixture.accepted =
                fixture.accepted.copy(checkpoint = fixture.accepted.checkpoint.copy(resume = RotationResume.NoHistory))
            fixture.execute = {
                fixture.available = false
                RotationCommandOutcome.Executed(
                    ApplyExecution(ApplySettlement.RecoveryRequired(SettlementProblem.Unconfirmed), null),
                )
            }
            fixture.coordinator.refreshRotation()
            drainCommands()
            assertIs<RotationRuntimeCommand.StepDue>(fixture.delivered.first())
            assertEquals(
                RotationRuntimeCommand.ReportFault(RuntimeFault.RecoveryPending),
                fixture.delivered.last(),
            )
            assertEquals(emptyList(), fixture.scheduler.wakes)

            fixture.accepted = acceptedSnapshot()
            fixture.coordinator.refreshRotation()
            assertEquals(emptyList(), fixture.scheduler.wakes)
            fixture.available = true
            fixture.coordinator.refreshRotation()
            assertEquals(
                6 * HOUR,
                fixture.scheduler.wakes
                    .single()
                    .delayMillis,
            )
        }

    @Test
    fun `a stale command waits for the next refresh when accepted appearance has not changed`() =
        runBlocking {
            val fixture = Fixture(this)
            fixture.accepted =
                fixture.accepted.copy(checkpoint = fixture.accepted.checkpoint.copy(resume = RotationResume.NoHistory))
            fixture.execute = {
                RotationCommandOutcome.Executed(ApplyExecution(ApplySettlement.NotAccepted, ApplyFailure.LeaseLost))
            }
            fixture.coordinator.refreshRotation()
            drainCommands()
            assertIs<RotationRuntimeCommand.StepDue>(fixture.delivered.single())
            assertEquals(emptyList(), fixture.scheduler.wakes)

            fixture.execute = {
                fixture.accepted = acceptedSnapshot()
                fixture.acceptedResult()
            }
            fixture.coordinator.refreshRotation()
            drainCommands()
            assertEquals(2, fixture.delivered.filterIsInstance<RotationRuntimeCommand.StepDue>().size)
            assertEquals(
                6 * HOUR,
                fixture.scheduler.wakes
                    .single()
                    .delayMillis,
            )
        }

    @Test
    fun `confirmed recovery can resume from the same accepted checkpoint`() =
        runBlocking {
            val fixture = Fixture(this)
            fixture.accepted =
                fixture.accepted.copy(checkpoint = fixture.accepted.checkpoint.copy(resume = RotationResume.NoHistory))
            fixture.execute = {
                RotationCommandOutcome.Executed(
                    ApplyExecution(ApplySettlement.RecoveryRequired(SettlementProblem.Unconfirmed), null),
                )
            }
            fixture.coordinator.refreshRotation()
            drainCommands()
            assertEquals(RotationRuntimeCommand.ReportFault(RuntimeFault.RecoveryPending), fixture.delivered.last())
            assertEquals(emptyList(), fixture.scheduler.wakes)

            fixture.execute = {
                fixture.accepted = acceptedSnapshot()
                fixture.acceptedResult()
            }
            fixture.coordinator.refreshRotation()
            drainCommands()
            assertEquals(2, fixture.delivered.filterIsInstance<RotationRuntimeCommand.StepDue>().size)
            assertEquals(
                6 * HOUR,
                fixture.scheduler.wakes
                    .single()
                    .delayMillis,
            )
        }

    @Test
    fun `a refresh during unresolved rotation is delivered once after the command returns`() =
        runBlocking {
            val fixture = Fixture(this)
            val original =
                fixture.accepted.copy(
                    checkpoint = fixture.accepted.checkpoint.copy(resume = RotationResume.NoHistory),
                )
            val pending = CompletableDeferred<RotationCommandOutcome>()
            fixture.accepted = original
            fixture.execute = { pending.await() }
            fixture.coordinator.refreshRotation()
            drainCommands()
            assertIs<RotationRuntimeCommand.StepDue>(fixture.delivered.single())

            repeat(2) { fixture.coordinator.refreshRotation() }
            fixture.execute = {
                fixture.accepted = acceptedSnapshot()
                fixture.acceptedResult()
            }
            pending.complete(
                RotationCommandOutcome.Executed(
                    ApplyExecution(ApplySettlement.RecoveryRequired(SettlementProblem.Unconfirmed), null),
                ),
            )
            drainCommands()
            drainCommands()
            assertEquals(
                listOf(
                    original,
                    original,
                ),
                fixture.delivered.filterIsInstance<RotationRuntimeCommand.StepDue>().map {
                    it.source
                },
            )
            assertEquals(
                6 * HOUR,
                fixture.scheduler.wakes
                    .single()
                    .delayMillis,
            )
        }

    @Test
    fun `a failed post-command read can recover from the same accepted checkpoint`() =
        assertReadRecovery(IllegalStateException("Authority read interrupted"))

    @Test
    fun `a cancelled post-command read can recover from the same accepted checkpoint`() =
        assertReadRecovery(CancellationException("Authority read cancelled"))

    private fun assertReadRecovery(failure: RuntimeException) =
        runBlocking {
            val fixture = Fixture(this)
            fixture.accepted =
                fixture.accepted.copy(checkpoint = fixture.accepted.checkpoint.copy(resume = RotationResume.NoHistory))
            fixture.execute = {
                fixture.readFailure = failure
                RotationCommandOutcome.Rejected(RotationStepAdmission.Stale)
            }
            fixture.coordinator.refreshRotation()
            drainCommands()
            assertEquals(RotationRuntimeCommand.ReportFault(RuntimeFault.RecoveryPending), fixture.delivered.last())
            assertEquals(emptyList(), fixture.scheduler.wakes)

            fixture.execute = {
                fixture.accepted = acceptedSnapshot()
                fixture.acceptedResult()
            }
            fixture.coordinator.refreshRotation()
            drainCommands()
            assertEquals(2, fixture.delivered.filterIsInstance<RotationRuntimeCommand.StepDue>().size)
            assertEquals(
                6 * HOUR,
                fixture.scheduler.wakes
                    .single()
                    .delayMillis,
            )
        }

    @Test
    fun `an executor error after acceptance reconciles the confirmed checkpoint`() =
        runBlocking {
            val fixture = Fixture(this)
            fixture.accepted =
                fixture.accepted.copy(checkpoint = fixture.accepted.checkpoint.copy(resume = RotationResume.NoHistory))
            fixture.execute = {
                fixture.accepted = acceptedSnapshot()
                error("Interrupted result delivery")
            }

            fixture.coordinator.refreshRotation()
            drainCommands()
            assertIs<RotationRuntimeCommand.StepDue>(fixture.delivered.single())
            assertEquals(
                6 * HOUR,
                fixture.scheduler.wakes
                    .single()
                    .delayMillis,
            )
        }

    @Test
    fun `disposal cancels a command that currently owns the Apply queue`() =
        runBlocking {
            val fixture = Fixture(this)
            val entered = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            fixture.accepted =
                fixture.accepted.copy(checkpoint = fixture.accepted.checkpoint.copy(resume = RotationResume.NoHistory))
            fixture.execute = {
                fixture.applyQueue.withLock {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        released.complete(Unit)
                    }
                }
            }
            fixture.coordinator.refreshRotation()
            entered.await()
            withTimeout(2.seconds) { fixture.coordinator.disposeRotation() }
            assertTrue(released.isCompleted)
            assertEquals(emptyList(), fixture.scheduler.wakes)
        }

    @Test
    fun `disposal cancels the command and ignores its late successful acknowledgement`() =
        runBlocking<Unit> {
            val fixture = Fixture(this)
            val started = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            fixture.accepted =
                fixture.accepted.copy(checkpoint = fixture.accepted.checkpoint.copy(resume = RotationResume.NoHistory))
            fixture.execute = {
                withContext(NonCancellable) {
                    started.complete(Unit)
                    finished.await()
                    fixture.accepted = acceptedSnapshot()
                    fixture.acceptedResult()
                }
            }
            fixture.coordinator.refreshRotation()
            started.await()
            fixture.coordinator.disposeRotation()
            finished.complete(Unit)
            drainCommands()
            assertEquals(1, fixture.reads)
            assertEquals(emptyList(), fixture.scheduler.wakes)
            assertIs<RotationRuntimeCommand.StepDue>(fixture.delivered.single())
        }

    @Test
    fun `disposal cancels the owned wake and suppresses later observations`() =
        runBlocking {
            val fixture = Fixture(this)
            fixture.coordinator.refreshRotation()
            val wake = fixture.scheduler.wakes.single()
            fixture.coordinator.disposeRotation()
            fixture.coordinator.disposeRotation()
            fixture.coordinator.refreshRotation()

            fixture.moveTime(6, 6)
            wake.run()
            yield()
            assertTrue(wake.isCancelled)
            assertEquals(1, fixture.reads)
            assertEquals(1, fixture.scheduler.wakes.size)
            assertEquals(emptyList(), fixture.delivered)
        }

    @Test
    fun `scheduler rejection faults the appointment without emitting a step or repeating the failure`() =
        runBlocking {
            val fixture = Fixture(this)
            fixture.scheduler.reject = true
            fixture.coordinator.refreshRotation()
            fixture.coordinator.refreshRotation()

            assertEquals(1, fixture.scheduler.attempts)
            assertEquals(
                listOf(RotationRuntimeCommand.ReportFault(RuntimeFault.SchedulerUnavailable)),
                fixture.delivered,
            )
        }

    private class Fixture(
        scope: CoroutineScope,
    ) {
        val applyQueue = Mutex()
        val scheduler = RecordedScheduler()
        val clockSamples = ArrayDeque<ClockSample>()
        var reads = 0
        var readFailure: RuntimeException? = null
        var accepted = acceptedSnapshot()
        var available = true
        var execute: suspend (RotationRuntimeCommand.Transaction) -> RotationCommandOutcome = {
            RotationCommandOutcome.Rejected(RotationStepAdmission.Stale)
        }
        private val commands = mutableListOf<RotationRuntimeCommand>()
        private var currentClock = ClockSample(0, 0)

        val delivered: List<RotationRuntimeCommand> get() = commands

        val coordinator =
            StudioRuntimeCoordinator(
                queue = applyQueue,
                scope = scope,
                observeRotation = {
                    reads++
                    readFailure?.let { failure ->
                        readFailure = null
                        throw failure
                    }
                    if (available) RotationObservation(accepted, AcceptanceLease(1, 1, 1)) else null
                },
                clock = { clockSamples.removeFirstOrNull() ?: currentClock },
                scheduler = scheduler,
                execute = {
                    commands.add(it)
                    execute(it)
                },
                reportFault = { commands.add(RotationRuntimeCommand.ReportFault(it)) },
            )

        fun acceptedResult(): RotationCommandOutcome.Executed {
            val document = catalogTestDocument()
            val acceptedDocument = document.copy(runtime = document.runtime.copy(rotation = accepted.checkpoint))
            return RotationCommandOutcome.Executed(ApplyExecution(ApplySettlement.Accepted(acceptedDocument), null))
        }

        fun moveTime(
            wallHours: Long,
            elapsedHours: Long,
        ) {
            currentClock = ClockSample(wallHours * HOUR, TimeUnit.HOURS.toNanos(elapsedHours))
        }
    }

    private suspend fun drainCommands() {
        yield()
        yield()
    }

    private class RecordedScheduler : ScheduledThreadPoolExecutor(1) {
        val wakes = mutableListOf<RecordedWake>()
        var attempts = 0
        var reject = false

        override fun schedule(
            command: Runnable,
            delay: Long,
            unit: TimeUnit,
        ): ScheduledFuture<*> {
            attempts++
            if (reject) throw RejectedExecutionException("scheduler stopped")
            return RecordedWake(unit.toMillis(delay), command).also(wakes::add)
        }
    }

    private class RecordedWake(
        val delayMillis: Long,
        private val callback: Runnable,
    ) : FutureTask<Unit>({}, Unit),
        ScheduledFuture<Unit> {
        // Deliver even a cancelled callback to exercise events already queued by the executor.
        override fun run() = callback.run()

        override fun getDelay(unit: TimeUnit): Long = unit.convert(delayMillis, TimeUnit.MILLISECONDS)

        override fun compareTo(other: Delayed): Int = delayMillis.compareTo(other.getDelay(TimeUnit.MILLISECONDS))
    }

    private companion object {
        const val HOUR = 3_600_000L

        fun acceptedSnapshot(): RotationRuntimeSnapshot =
            RotationRuntimeSnapshot(
                checkpoint =
                    RotationRuntimeCheckpoint(
                        RuntimeOwner(StudioTarget.Global, 12),
                        sequenceCursorRaw = 5,
                        successfulStepSerial = 7,
                        schedule = RotationSchedule(0, 6 * HOUR, 6 * HOUR, 1),
                    ),
                intent = RotationIntent(enabled = true, modeRaw = null, intervalHoursRaw = 6),
                availability = RotationAvailability(LicenseEntitlement.LICENSED, false, AyuVariant.MIRAGE, true, false),
                generation = RotationTimerGeneration(service = 4, target = 8),
            )
    }
}
