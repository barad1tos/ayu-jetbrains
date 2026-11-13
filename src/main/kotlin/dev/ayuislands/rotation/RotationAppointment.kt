package dev.ayuislands.rotation

import java.util.concurrent.TimeUnit

internal fun rotationIntervalMillis(hours: Int): Long = TimeUnit.HOURS.toMillis(maxOf(1L, hours.toLong()))

/** Computes an appointment without reading the clock or changing the accepted checkpoint. */
internal fun planRotationAppointment(
    scheduledAt: Long,
    interval: Long,
    previousSerial: Long,
): RotationSchedule? {
    require(interval > 0) { "A rotation interval must be positive" }
    if (previousSerial == Long.MAX_VALUE || scheduledAt > Long.MAX_VALUE - interval) return null
    return RotationSchedule(scheduledAt, scheduledAt + interval, interval, maxOf(0L, previousSerial) + 1)
}
