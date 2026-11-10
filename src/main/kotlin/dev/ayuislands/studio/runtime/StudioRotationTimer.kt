package dev.ayuislands.studio.runtime

import dev.ayuislands.rotation.RotationTicket
import dev.ayuislands.studio.session.ScheduledCall
import dev.ayuislands.studio.session.SessionScheduler

/** Owns one one-shot callback on the caller's serialized runtime lane. */
internal class StudioRotationTimer(
    private val scheduler: SessionScheduler,
    private val onDue: (RotationTicket) -> Unit,
) {
    private var current: Appointment? = null
    private var isDisposed = false

    fun arm(ticket: RotationTicket) {
        if (isDisposed) return
        current?.call?.cancel()
        val appointment = Appointment(ticket)
        current = appointment
        appointment.call =
            scheduler.schedule(ticket.delayMillis) {
                if (current === appointment && !isDisposed) {
                    current = null
                    onDue(ticket)
                }
            }
        if (current !== appointment) appointment.call?.cancel()
    }

    fun cancel(ticket: RotationTicket) {
        val appointment = current ?: return
        if (appointment.ticket != ticket) return
        current = null
        appointment.call?.cancel()
    }

    fun dispose() {
        isDisposed = true
        val appointment = current
        current = null
        appointment?.call?.cancel()
    }
}

private class Appointment(
    val ticket: RotationTicket,
    var call: ScheduledCall? = null,
)
