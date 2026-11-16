package dev.ayuislands.studio.model

import java.time.Instant

/** Captured process identity; an unavailable start time remains explicitly unknown. */
internal data class StudioProcess(
    val pid: Long,
    val startedAt: Instant?,
) {
    init {
        require(pid > 0) { "Process identity requires a positive PID" }
    }
}

internal enum class StudioProcessRelation { SAME, DIFFERENT, UNCONFIRMED }
