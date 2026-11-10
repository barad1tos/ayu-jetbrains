package dev.ayuislands.studio.runtime

import dev.ayuislands.rotation.RotationTicket
import dev.ayuislands.rotation.RuntimeOwner
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.session.ScheduledCall
import dev.ayuislands.studio.session.SessionScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioRotationTimerTest {
    @Test
    fun `one shot preserves the supplied delay and delivers its ticket once`() {
        val fixture = timerFixture()
        val appointment = ticket()

        fixture.timer.arm(appointment)

        val scheduled = fixture.scheduler.calls.single()
        assertEquals(appointment.delayMillis, scheduled.delayMillis)
        assertTrue(fixture.delivered.isEmpty())
        scheduled.deliver()
        scheduled.deliver()
        assertSame(appointment, fixture.delivered.single())
    }

    @Test
    fun `replacement cancels the old handle and ignores its late callback and cancellation`() {
        val fixture = timerFixture()
        val previous = ticket()
        val replacement = previous.copy(scheduleSerial = 2, delayMillis = 40, remainingMillis = 40)
        fixture.timer.arm(previous)
        val oldCall = fixture.scheduler.calls.single()

        fixture.timer.arm(replacement)
        val currentCall = fixture.scheduler.calls.last()
        fixture.timer.cancel(previous)
        oldCall.deliver()
        oldCall.deliver()

        assertTrue(oldCall.isCancelled)
        assertFalse(currentCall.isCancelled)
        assertTrue(fixture.delivered.isEmpty())
        assertEquals(
            listOf(previous.delayMillis, replacement.delayMillis),
            fixture.scheduler.calls.map { it.delayMillis },
        )
        currentCall.deliver()
        currentCall.deliver()
        assertSame(replacement, fixture.delivered.single())
    }

    @Test
    fun `cancelling the current ticket prevents an already queued callback`() {
        val fixture = timerFixture()
        val appointment = ticket()
        fixture.timer.arm(appointment)
        val scheduled = fixture.scheduler.calls.single()

        fixture.timer.cancel(appointment)
        fixture.timer.cancel(appointment)
        scheduled.deliver()

        assertTrue(scheduled.isCancelled)
        assertTrue(fixture.delivered.isEmpty())
        assertEquals(1, fixture.scheduler.calls.size)
    }

    @Test
    fun `disposal cancels the owned call and prevents late delivery and further scheduling`() {
        val fixture = timerFixture()
        val appointment = ticket()
        fixture.timer.arm(appointment)
        val scheduled = fixture.scheduler.calls.single()

        fixture.timer.dispose()
        fixture.timer.dispose()
        scheduled.deliver()
        fixture.timer.arm(appointment.copy(scheduleSerial = 2))
        fixture.timer.cancel(appointment)
        scheduled.deliver()

        assertTrue(scheduled.isCancelled)
        assertTrue(fixture.delivered.isEmpty())
        assertEquals(1, fixture.scheduler.calls.size)
    }

    private fun timerFixture(): TimerFixture {
        val scheduler = RecordingScheduler()
        val delivered = mutableListOf<RotationTicket>()
        return TimerFixture(scheduler, delivered, StudioRotationTimer(scheduler, delivered::add))
    }

    private fun ticket(): RotationTicket =
        RotationTicket(
            owner = RuntimeOwner(StudioTarget.Global, 7),
            scheduleSerial = 1,
            delayMillis = 25,
            startedAtMonotonicNanos = 0,
        )

    private data class TimerFixture(
        val scheduler: RecordingScheduler,
        val delivered: MutableList<RotationTicket>,
        val timer: StudioRotationTimer,
    )

    private class RecordingScheduler : SessionScheduler {
        val calls = mutableListOf<ScheduledCallback>()

        override fun schedule(
            delayMillis: Long,
            action: () -> Unit,
        ): ScheduledCall {
            val scheduled = ScheduledCallback(delayMillis, action)
            calls.add(scheduled)
            return ScheduledCall { scheduled.isCancelled = true }
        }
    }

    private class ScheduledCallback(
        val delayMillis: Long,
        private val action: () -> Unit,
    ) {
        var isCancelled: Boolean = false

        fun deliver() = action()
    }
}
