package dev.ayuislands.rotation

internal fun rotationTimerKey(
    accepted: RotationRuntimeCheckpoint,
    generation: RotationTimerGeneration,
): RotationTimerKey? {
    val schedule = accepted.schedule ?: return null
    if (accepted.resume != RotationResume.KeepDeadline || accepted.recoveryFault != null) return null
    return RotationTimerKey(accepted.owner, schedule.scheduleSerial, generation)
}

/** A repair proposal can only arm a timer after the accepted checkpoint yields its own wait. */
internal fun armRotationWait(
    accepted: RotationRuntimeCheckpoint,
    timing: RotationTimingPlan,
    generation: RotationTimerGeneration,
    monotonicNanos: Long,
): RotationWaitTicket? {
    if (timing !is RotationTimingPlan.Wait || timing.owner != accepted.owner || timing.schedule != accepted.schedule) {
        return null
    }
    val key = rotationTimerKey(accepted, generation) ?: return null
    return RotationWaitTicket(key, monotonicNanos, timing.delayMillis)
}

/** The caller replaces or removes the outstanding ticket before executing the resulting command. */
internal fun advanceRotationWait(
    pending: RotationWaitTicket?,
    callback: RotationWaitTicket,
    current: RotationTimerKey?,
    monotonicNanos: Long,
): RotationWakePlan {
    if (pending != callback || current != callback.key) return RotationWakePlan.Ignored
    val elapsedMillis = callback.elapsedMillis(monotonicNanos)
    if (elapsedMillis >= callback.remainingMillis) return RotationWakePlan.Due
    val next = callback.copy(armedAtNanos = monotonicNanos, remainingMillis = callback.remainingMillis - elapsedMillis)
    return RotationWakePlan.Arm(next)
}
