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
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
            yield()

            assertEquals(listOf(RotationRuntimeCommand.StepDue(fixture.accepted)), fixture.delivered)
            assertEquals(3, fixture.reads)
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
            yield()
            assertEquals(3, fixture.reads)
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
            yield()
            assertEquals(listOf(RotationRuntimeCommand.StepDue(fixture.accepted)), fixture.delivered)
        }

    @Test
    fun `checkpoint proposals are handed off and only a later accepted appointment arms a wake`() =
        runBlocking {
            val fixture = Fixture(this)
            fixture.accepted =
                fixture.accepted.copy(
                    checkpoint =
                        fixture.accepted.checkpoint.copy(
                            resume = RotationResume.FullInterval(FullIntervalReason.FOLLOW_SYSTEM),
                        ),
                )
            fixture.coordinator.refreshRotation()
            fixture.coordinator.refreshRotation()
            assertEquals(0, fixture.scheduler.wakes.size)
            val requested = assertIs<RotationRuntimeCommand.PersistCheckpoint>(fixture.delivered.single())
            assertEquals(fixture.accepted, requested.source)

            fixture.accepted = fixture.accepted.copy(checkpoint = requested.proposed.checkpoint)
            fixture.coordinator.refreshRotation()
            assertEquals(
                6 * HOUR,
                fixture.scheduler.wakes
                    .single()
                    .delayMillis,
            )
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
        var accepted = acceptedSnapshot()
        private val commands = mutableListOf<RotationRuntimeCommand>()
        private var currentClock = ClockSample(0, 0)

        val delivered: List<RotationRuntimeCommand> get() = commands

        val coordinator =
            StudioRuntimeCoordinator(
                queue = applyQueue,
                scope = scope,
                observeRotation = { accepted.also { reads++ } },
                clock = { clockSamples.removeFirstOrNull() ?: currentClock },
                scheduler = scheduler,
                publishRotation = { commands.add(it) },
            )

        fun moveTime(
            wallHours: Long,
            elapsedHours: Long,
        ) {
            currentClock = ClockSample(wallHours * HOUR, TimeUnit.HOURS.toNanos(elapsedHours))
        }
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
