package dev.ayuislands.rotation

internal enum class RotationAppointmentRepair { MISSING, INVALID, CLOCK_MOVED_BACKWARD }

internal sealed interface RotationTimingPlan {
    data object StepDue : RotationTimingPlan

    data class Wait(
        val delayMillis: Long,
    ) : RotationTimingPlan

    data class Persist(
        val checkpoint: RotationRuntimeCheckpoint,
        val repair: RotationAppointmentRepair? = null,
    ) : RotationTimingPlan

    data object Unrepresentable : RotationTimingPlan
}

/** Resolve an eligible owner's accepted timing obligation. Persist a proposal before arming its timer. */
internal fun planRotationTiming(
    checkpoint: RotationRuntimeCheckpoint,
    intervalHours: Int,
    nowEpochMillis: Long,
): RotationTimingPlan =
    when (checkpoint.resume) {
        RotationResume.NoHistory, RotationResume.ImmediateEnable, RotationResume.ImmediateConfiguration ->
            RotationTimingPlan.StepDue

        is RotationResume.FullInterval ->
            replaceAppointment(checkpoint, rotationIntervalMillis(intervalHours), nowEpochMillis)

        RotationResume.KeepDeadline, RotationResume.ExternalCadence ->
            planRetainedAppointment(checkpoint, intervalHours, nowEpochMillis)
    }

private fun planRetainedAppointment(
    checkpoint: RotationRuntimeCheckpoint,
    intervalHours: Int,
    now: Long,
): RotationTimingPlan {
    val schedule = checkpoint.schedule
    if (schedule == null) {
        return replaceAppointment(
            checkpoint,
            rotationIntervalMillis(intervalHours),
            now,
            RotationAppointmentRepair.MISSING,
        )
    }
    if (!schedule.isValid()) {
        return replaceAppointment(
            checkpoint,
            rotationIntervalMillis(intervalHours),
            now,
            RotationAppointmentRepair.INVALID,
        )
    }
    if (now < schedule.scheduledAtEpochMillis) {
        return replaceAppointment(
            checkpoint,
            schedule.intervalMillisAtScheduling,
            now,
            RotationAppointmentRepair.CLOCK_MOVED_BACKWARD,
        )
    }
    if (checkpoint.resume == RotationResume.ExternalCadence) return finishExternalPause(checkpoint, schedule, now)
    return if (now >= schedule.deadlineEpochMillis) {
        RotationTimingPlan.StepDue
    } else {
        RotationTimingPlan.Wait(schedule.deadlineEpochMillis - now)
    }
}

private fun finishExternalPause(
    checkpoint: RotationRuntimeCheckpoint,
    schedule: RotationSchedule,
    now: Long,
): RotationTimingPlan {
    if (now < schedule.deadlineEpochMillis) {
        return RotationTimingPlan.Persist(checkpoint.copy(resume = RotationResume.KeepDeadline))
    }
    val interval = schedule.intervalMillisAtScheduling
    // Compare phases to avoid overflowing the age of an old deadline.
    val phase = Math.floorMod(now, interval) - Math.floorMod(schedule.deadlineEpochMillis, interval)
    val lastSkippedSlot = now - Math.floorMod(phase, interval)
    return replaceAppointment(checkpoint, interval, lastSkippedSlot)
}

private fun replaceAppointment(
    checkpoint: RotationRuntimeCheckpoint,
    interval: Long,
    scheduledAt: Long,
    repair: RotationAppointmentRepair? = null,
): RotationTimingPlan =
    when (val proposal = RotationStepPlanner.planSchedule(checkpoint, interval, scheduledAt)) {
        is RotationCompletionPlan.Ready -> RotationTimingPlan.Persist(proposal.checkpoint, repair)
        is RotationCompletionPlan.Blocked -> RotationTimingPlan.Unrepresentable
    }

private fun RotationSchedule.isValid(): Boolean {
    val interval = intervalMillisAtScheduling
    if (interval <= 0 || scheduleSerial <= 0 || scheduledAtEpochMillis > Long.MAX_VALUE - interval) return false
    return deadlineEpochMillis == scheduledAtEpochMillis + interval
}
