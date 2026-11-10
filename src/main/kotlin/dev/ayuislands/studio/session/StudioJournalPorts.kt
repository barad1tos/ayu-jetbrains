package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.StudioSessionRecord

internal fun interface ScheduledCall {
    fun cancel()
}

/** Callbacks run on the owning controller's serialized dispatcher. */
internal fun interface SessionScheduler {
    fun schedule(
        delayMillis: Long,
        action: () -> Unit,
    ): ScheduledCall
}

/** Writes through the application transaction queue and returns on the controller dispatcher. */
internal fun interface SessionWriter {
    fun write(
        record: StudioSessionRecord,
        completion: (DraftWriteResult) -> Unit,
    )
}
