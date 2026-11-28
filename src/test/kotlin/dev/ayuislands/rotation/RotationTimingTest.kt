package dev.ayuislands.rotation

import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.studio.model.StudioTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RotationTimingTest {
    @Test
    fun `keep the appointment made with the former profile's interval`() {
        val checkpoint = checkpoint()
        assertEquals(RotationTimingPlan.Wait(5 * HOUR), planRotationTiming(checkpoint, 1, 13 * HOUR))
        assertEquals(RotationTimingPlan.StepDue, planRotationTiming(checkpoint, 1, 18 * HOUR))
        assertEquals(RotationTimingPlan.StepDue, planRotationTiming(checkpoint, 1, 21 * HOUR))
    }

    @Test
    fun `rebase an appointment whose start is ahead of the clock using its saved interval`() {
        assertRepair(
            checkpoint(),
            now = 11 * HOUR,
            repaired = RotationSchedule(11 * HOUR, 17 * HOUR, 6 * HOUR, 5),
            repair = RotationAppointmentRepair.CLOCK_MOVED_BACKWARD,
        )
    }

    @Test
    fun `repair missing and inconsistent appointments from the current interval`() {
        val initial = checkpoint()
        for (schedule in listOf(null, initial.schedule?.copy(deadlineEpochMillis = 21 * HOUR))) {
            assertRepair(
                initial.copy(schedule = schedule),
                now = 13 * HOUR,
                repaired = RotationSchedule(13 * HOUR, 14 * HOUR, HOUR, if (schedule == null) 1 else 5),
                repair = if (schedule == null) RotationAppointmentRepair.MISSING else RotationAppointmentRepair.INVALID,
            )
        }
    }

    @Test
    fun `only explicit immediate and recognized legacy no history obligations step without an appointment`() {
        val resumes =
            listOf(
                RotationResume.NoHistory,
                RotationResume.ImmediateEnable,
                RotationResume.ImmediateConfiguration,
            )
        for (resume in resumes) {
            val checkpoint = checkpoint().copy(schedule = null, resume = resume)
            assertEquals(RotationTimingPlan.StepDue, planRotationTiming(checkpoint, 1, 13 * HOUR))
        }
    }

    @Test
    fun `follow resuming requires a full current interval despite an existing schedule`() {
        val checkpoint = checkpoint().copy(resume = RotationResume.FullInterval(FullIntervalReason.FOLLOW_SYSTEM))
        assertRepair(
            checkpoint,
            now = 20 * HOUR,
            repaired = RotationSchedule(20 * HOUR, 21 * HOUR, HOUR, 5),
        )
    }

    @Test
    fun `returning from another theme skips elapsed slots and keeps the old cadence`() {
        val checkpoint = checkpoint().copy(resume = RotationResume.ExternalCadence)
        assertRepair(
            checkpoint,
            now = 18 * HOUR,
            repaired = RotationSchedule(18 * HOUR, 24 * HOUR, 6 * HOUR, 5),
        )
        assertRepair(
            checkpoint,
            now = 38 * HOUR,
            repaired = RotationSchedule(36 * HOUR, 42 * HOUR, 6 * HOUR, 5),
        )
    }

    @Test
    fun `returning before the external deadline consumes the obligation with the same appointment`() {
        val checkpoint = checkpoint().copy(resume = RotationResume.ExternalCadence)
        val planned =
            assertIs<RotationTimingPlan.Persist>(
                planRotationTiming(checkpoint, 1, 13 * HOUR),
            )
        assertEquals(checkpoint.copy(resume = RotationResume.KeepDeadline), planned.checkpoint)
        assertEquals(RotationTimingPlan.Wait(5 * HOUR), planRotationTiming(planned.checkpoint, 1, 13 * HOUR))
    }

    @Test
    fun `missing external cadence starts a full interval after eligibility returns`() {
        val checkpoint = checkpoint().copy(schedule = null, resume = RotationResume.ExternalCadence)
        assertRepair(
            checkpoint,
            now = 20 * HOUR,
            repaired = RotationSchedule(20 * HOUR, 21 * HOUR, HOUR, 1),
            repair = RotationAppointmentRepair.MISSING,
        )
    }

    @Test
    fun `maximum interval remains representable and a clock without room for repair fails closed`() {
        val checkpoint = checkpoint().copy(schedule = null)
        val planned =
            assertIs<RotationTimingPlan.Persist>(
                planRotationTiming(checkpoint, Int.MAX_VALUE, 0L),
            )
        assertEquals(RotationSchedule(0, Int.MAX_VALUE * HOUR, Int.MAX_VALUE * HOUR, 1), planned.checkpoint.schedule)
        assertEquals(RotationTimingPlan.Unrepresentable, planRotationTiming(checkpoint, 1, Long.MAX_VALUE))
    }

    private fun assertRepair(
        checkpoint: RotationRuntimeCheckpoint,
        now: Long,
        repaired: RotationSchedule,
        repair: RotationAppointmentRepair? = null,
    ) {
        val planned =
            assertIs<RotationTimingPlan.Persist>(
                planRotationTiming(checkpoint, 1, now),
            )
        assertEquals(checkpoint.copy(schedule = repaired, resume = RotationResume.KeepDeadline), planned.checkpoint)
        assertEquals(repair, planned.repair)
    }

    private fun checkpoint(): RotationRuntimeCheckpoint {
        val owner = RuntimeOwner(StudioTarget.Global, 14)
        return RotationRuntimeCheckpoint(
            owner,
            sequenceCursorRaw = 9,
            successfulStepSerial = 12,
            lastSuccessfulStepEpochMillis = 12 * HOUR,
            output = RotationOutput(owner, AyuVariant.MIRAGE, "#FFCD66"),
            schedule = RotationSchedule(12 * HOUR, 18 * HOUR, 6 * HOUR, 4),
        )
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
