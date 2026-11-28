package dev.ayuislands.rotation

import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioTarget
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RotationWaitPlannerTest {
    private val owner = RuntimeOwner(StudioTarget.Global, 12)
    private val generation = RotationTimerGeneration(service = 4, target = 8)
    private val armedAt = 123_000_000L

    @Test
    fun `only the accepted appointment and its owner can arm the wait`() {
        val saved = checkpoint(6)
        val pending = saved.copy(resume = RotationResume.FullInterval(FullIntervalReason.FOLLOW_SYSTEM))
        val proposed = assertIs<RotationTimingPlan.Persist>(planRotationTiming(pending, 1, HOUR))
        assertNull(armRotationWait(pending, proposed, generation, armedAt))

        val accepted = proposed.checkpoint
        val timing = planRotationTiming(accepted, 1, HOUR)
        assertNull(armRotationWait(saved, timing, generation, armedAt))
        val laterOwner = accepted.copy(owner = owner.copy(appearanceRevision = 13))
        assertNull(armRotationWait(laterOwner, timing, generation, armedAt))
        val external = accepted.copy(resume = RotationResume.ExternalCadence)
        assertNull(armRotationWait(external, timing, generation, armedAt))
        val recovering = accepted.copy(recoveryFault = RuntimeFault.DurableRecovery("pending-rotation"))
        assertNull(armRotationWait(recovering, timing, generation, armedAt))
        val ticket = assertNotNull(armRotationWait(accepted, timing, generation, armedAt))
        assertEquals(HOUR, ticket.delayMillis)
        assertEquals(rotationTimerKey(accepted, generation), ticket.key)
    }

    @Test
    fun `long waits use bounded guards and only the final wake makes a rotation due`() {
        val accepted = checkpoint(72)
        val first = arm(accepted)
        val key = first.key
        assertEquals(24 * HOUR, first.delayMillis)
        assertEquals(72 * HOUR, first.remainingMillis)

        val second = rearm(first, 24)
        assertEquals(48 * HOUR, second.remainingMillis)
        assertEquals(24 * HOUR, second.delayMillis)
        assertEquals(RotationWakePlan.Ignored, advanceRotationWait(second, first, key, atHours(24)))

        val third = rearm(second, 48)
        assertEquals(24 * HOUR, third.remainingMillis)
        assertEquals(RotationWakePlan.Due, advanceRotationWait(third, third, key, atHours(72)))
        assertEquals(RotationWakePlan.Ignored, advanceRotationWait(null, third, key, atHours(72)))
    }

    @Test
    fun `live waiting uses elapsed monotonic time after a wall clock jump`() {
        val accepted = checkpoint(6)
        val initial = arm(accepted)
        // A fresh restart at this wall time would see an overdue appointment.
        assertEquals(RotationTimingPlan.StepDue, planRotationTiming(accepted, 6, 48 * HOUR))

        val wait = rearm(initial, 2)
        assertEquals(4 * HOUR, wait.remainingMillis)
        assertEquals(4 * HOUR, wait.delayMillis)
        assertEquals(RotationWakePlan.Due, advanceRotationWait(wait, wait, wait.key, atHours(6)))
    }

    @Test
    fun `callbacks for a replaced appointment or a different target or service generation are stale`() {
        val accepted = checkpoint(6)
        val ticket = arm(accepted)
        val otherOwner = RuntimeOwner(StudioTarget.Project(StudioProjectKey("/other")), 12)
        val otherAppointment = requireNotNull(accepted.schedule).copy(scheduleSerial = 2)
        val replacements =
            listOf(
                rotationTimerKey(accepted, generation.copy(service = 5)),
                rotationTimerKey(accepted, generation.copy(target = 9)),
                rotationTimerKey(accepted.copy(owner = otherOwner), generation),
                rotationTimerKey(accepted.copy(schedule = otherAppointment), generation),
                rotationTimerKey(accepted.copy(schedule = null), generation),
            )
        for (current in replacements) {
            assertEquals(RotationWakePlan.Ignored, advanceRotationWait(ticket, ticket, current, atHours(6)))
        }
    }

    @Test
    fun `a late callback from the accepted short appointment emits one due result`() {
        val accepted = checkpoint(6)
        val ticket = arm(accepted)
        assertEquals(6 * HOUR, ticket.delayMillis)
        assertEquals(RotationWakePlan.Due, advanceRotationWait(ticket, ticket, ticket.key, atHours(7)))
        assertEquals(RotationWakePlan.Ignored, advanceRotationWait(null, ticket, ticket.key, atHours(7)))
    }

    private fun checkpoint(hours: Long): RotationRuntimeCheckpoint =
        RotationRuntimeCheckpoint(
            owner,
            sequenceCursorRaw = 5,
            successfulStepSerial = 7,
            schedule = RotationSchedule(0, hours * HOUR, hours * HOUR, 1),
        )

    private fun arm(checkpoint: RotationRuntimeCheckpoint): RotationWaitTicket =
        assertNotNull(armRotationWait(checkpoint, planRotationTiming(checkpoint, 6, 0), generation, armedAt))

    private fun rearm(
        ticket: RotationWaitTicket,
        elapsedHours: Long,
    ): RotationWaitTicket =
        assertIs<RotationWakePlan.Arm>(advanceRotationWait(ticket, ticket, ticket.key, atHours(elapsedHours))).ticket

    private fun atHours(hours: Long): Long = armedAt + TimeUnit.HOURS.toNanos(hours)

    private companion object {
        const val HOUR = 3_600_000L
    }
}
