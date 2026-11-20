package dev.ayuislands.glow

import javax.swing.Timer

/** Retains a native timer without resetting the animation state it drives. */
internal class GlowPlaybackTimer private constructor(
    private val timer: Timer,
    private val resumeClock: () -> Unit,
    private val isCurrent: () -> Boolean,
) {
    fun pause(): Boolean {
        if (!isCurrent() || !timer.isRunning) return false
        timer.stop()
        return true
    }

    fun resume() {
        if (!isCurrent()) return
        resumeClock()
        timer.start()
    }

    companion object {
        fun capture(
            isOwned: () -> Boolean = { true },
            resumeClock: () -> Unit = {},
            currentTimer: () -> Timer?,
        ): GlowPlaybackTimer? {
            val timer = currentTimer() ?: return null
            return GlowPlaybackTimer(timer, resumeClock) { isOwned() && currentTimer() === timer }
        }
    }
}
