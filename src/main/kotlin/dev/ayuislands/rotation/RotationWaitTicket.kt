package dev.ayuislands.rotation

import java.util.concurrent.TimeUnit

internal data class RotationTimerGeneration(
    val service: Long,
    val target: Long,
)

internal data class RotationTimerKey(
    val owner: RuntimeOwner,
    val scheduleSerial: Long,
    val generation: RotationTimerGeneration,
)

/** One outstanding wake for a durable appointment. Timer time is monotonic and process-local. */
internal data class RotationWaitTicket(
    val key: RotationTimerKey,
    val armedAtNanos: Long,
    val remainingMillis: Long,
) {
    val delayMillis: Long get() = minOf(remainingMillis, MAX_WAKE_DELAY_MILLIS)

    fun elapsedMillis(nowNanos: Long): Long = TimeUnit.NANOSECONDS.toMillis(maxOf(0L, nowNanos - armedAtNanos))

    private companion object {
        val MAX_WAKE_DELAY_MILLIS: Long = TimeUnit.DAYS.toMillis(1)
    }
}

internal sealed interface RotationWakePlan {
    data object Ignored : RotationWakePlan

    data object Due : RotationWakePlan

    data class Arm(
        val ticket: RotationWaitTicket,
    ) : RotationWakePlan
}
