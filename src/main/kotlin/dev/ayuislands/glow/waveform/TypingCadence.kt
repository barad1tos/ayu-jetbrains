package dev.ayuislands.glow.waveform

/**
 * Tracks typing cadence as an exponential moving average of keystroke rate and
 * maps it to the ECG trace-scroll rate (resting 1x up to
 * [MAX_TRACE_RATE]). An isolated keystroke measures no interval, so it never
 * changes the rate; sustained typing is required to raise the pulse.
 */
internal data class TypingCadence(
    val lastKeystrokeMs: Long? = null,
    val strokesPerSecond: Float = 0f,
) {
    fun keystroke(nowMs: Long): TypingCadence {
        val previousMs = lastKeystrokeMs ?: return TypingCadence(lastKeystrokeMs = nowMs)
        val gapMs = nowMs - previousMs
        if (gapMs > CADENCE_RESET_MS) return TypingCadence(lastKeystrokeMs = nowMs)

        val instantRate = MS_PER_SECOND / gapMs.coerceAtLeast(MIN_INTERVAL_MS)
        return TypingCadence(
            lastKeystrokeMs = nowMs,
            strokesPerSecond = strokesPerSecond + EMA_GAIN * (instantRate - strokesPerSecond),
        )
    }

    fun currentRate(nowMs: Long): Float {
        val previousMs = lastKeystrokeMs ?: return 0f
        val idleMs = (nowMs - previousMs).coerceAtLeast(0L)
        return when {
            idleMs <= CADENCE_HOLD_MS -> strokesPerSecond
            idleMs >= CADENCE_HOLD_MS + CADENCE_DECAY_MS -> 0f
            else -> strokesPerSecond * (1f - (idleMs - CADENCE_HOLD_MS).toFloat() / CADENCE_DECAY_MS)
        }
    }

    fun targetRate(nowMs: Long): Float = 1f + (MAX_TRACE_RATE - 1f) * smoothStep(currentRate(nowMs) / FULL_RATE_STROKES)

    companion object {
        const val MAX_TRACE_RATE = 2.5f
        const val FULL_RATE_STROKES = 6f
        const val EMA_GAIN = 0.3f
        const val MS_PER_SECOND = 1_000f
        const val CADENCE_RESET_MS = 2_000L
        const val CADENCE_HOLD_MS = 400L
        const val CADENCE_DECAY_MS = 2_600L
        const val MIN_INTERVAL_MS = 30L
    }
}
