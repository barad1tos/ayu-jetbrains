package dev.ayuislands.glow

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import javax.swing.Timer
import kotlin.math.sin

/** Timer-based animation controller with 3 animation types and performance monitoring. */
class GlowAnimator : Disposable {
    private val log = logger<GlowAnimator>()

    private var timer: Timer? = null
    private var frame: Long = 0
    private var currentAnimation: GlowAnimation = GlowAnimation.NONE
    private var onFrame: ((alpha: Float) -> Unit)? = null

    // Performance monitoring
    private var slowFrameCount = 0
    private var lastFrameTimeNanos: Long = 0
    private var startTimeNanos: Long = 0

    // Reactive animation state
    private var reactiveBoost: Float = 0.0f
    private val reactiveDecayRate: Float = REACTIVE_DECAY

    companion object {
        private const val FRAME_INTERVAL_MS = 16
        private const val SLOW_FRAME_THRESHOLD_MS = 32.0
        private const val GRACE_PERIOD_MS = 180_000L
        private const val MAX_SLOW_FRAMES = 30

        private const val PULSE_CYCLE_FRAMES = 120
        private const val PULSE_ATTACK_RATIO = 0.15
        private const val PULSE_RELEASE_DIVISOR = 0.85
        private const val PULSE_MIN_ALPHA = 0.3f
        private const val PULSE_RANGE = 0.7f

        private const val BREATHE_CYCLE_FRAMES = 240
        private const val BREATHE_MIN_ALPHA = 0.2f
        private const val BREATHE_RANGE = 0.8f

        private const val REACTIVE_BASE_ALPHA = 0.4f
        private const val REACTIVE_RANGE = 0.6f
        private const val REACTIVE_THRESHOLD = 0.01f
        private const val REACTIVE_DECAY = 0.92f

        private const val NANOS_PER_MS = 1_000_000.0
        private const val NANOS_PER_MS_LONG = 1_000_000L
    }

    fun start(
        animation: GlowAnimation,
        callback: (alpha: Float) -> Unit,
    ) {
        stop()
        if (animation == GlowAnimation.NONE) return

        currentAnimation = animation
        onFrame = callback
        frame = 0
        slowFrameCount = 0
        lastFrameTimeNanos = 0
        startTimeNanos = System.nanoTime()
        reactiveBoost = 0.0f

        timer =
            Timer(FRAME_INTERVAL_MS) {
                if (!shouldContinueAnimation(System.nanoTime())) return@Timer

                val alpha = calculateAlpha(animation)
                frame++
                callback(alpha)
            }.also {
                it.isRepeats = true
                it.start()
            }

        log.info("Glow animation started: ${animation.displayName}")
    }

    private fun shouldContinueAnimation(now: Long): Boolean {
        if (lastFrameTimeNanos > 0) {
            val elapsedMs = (now - lastFrameTimeNanos) / NANOS_PER_MS
            val elapsedSinceStart = (now - startTimeNanos) / NANOS_PER_MS_LONG
            if (elapsedSinceStart > GRACE_PERIOD_MS) {
                if (elapsedMs > SLOW_FRAME_THRESHOLD_MS) {
                    slowFrameCount++
                    if (slowFrameCount > MAX_SLOW_FRAMES) {
                        stop()
                        notifyPerformanceDegradation()
                        return false
                    }
                } else {
                    slowFrameCount = (slowFrameCount - 1).coerceAtLeast(0)
                }
            }
        }
        lastFrameTimeNanos = now
        return true
    }

    private fun calculateAlpha(animation: GlowAnimation): Float =
        when (animation) {
            GlowAnimation.PULSE -> {
                // Sharp brightening pulse every 2 seconds (120 frames at 60fps)
                val cycle = (frame % PULSE_CYCLE_FRAMES) / PULSE_CYCLE_FRAMES.toDouble()
                val pulse =
                    if (cycle < PULSE_ATTACK_RATIO) {
                        (cycle / PULSE_ATTACK_RATIO).toFloat()
                    } else {
                        ((1.0 - cycle) / PULSE_RELEASE_DIVISOR).toFloat().coerceAtLeast(0f)
                    }
                PULSE_MIN_ALPHA + pulse * PULSE_RANGE
            }
            GlowAnimation.BREATHE -> {
                // Slow sinusoidal breathing over 4 seconds (240 frames at 60fps)
                val cycle = (frame % BREATHE_CYCLE_FRAMES) / BREATHE_CYCLE_FRAMES.toDouble()
                val breath = (sin(cycle * Math.PI * 2) + 1.0) / 2.0
                BREATHE_MIN_ALPHA + breath.toFloat() * BREATHE_RANGE
            }
            GlowAnimation.REACTIVE -> {
                // Base alpha with boost from typing/actions (managed via boost() calls)
                reactiveBoost *= reactiveDecayRate
                if (reactiveBoost < REACTIVE_THRESHOLD) reactiveBoost = 0.0f
                (REACTIVE_BASE_ALPHA + reactiveBoost * REACTIVE_RANGE).coerceAtMost(1.0f)
            }
            GlowAnimation.NONE -> 1.0f
        }

    fun stop() {
        timer?.stop()
        timer = null
        onFrame = null
        currentAnimation = GlowAnimation.NONE
        reactiveBoost = 0.0f
    }

    private fun notifyPerformanceDegradation() {
        log.warn("Glow animation auto-disabled due to sustained poor performance")
        try {
            NotificationGroupManager
                .getInstance()
                .getNotificationGroup("Ayu Islands")
                .createNotification(
                    "Glow animation disabled",
                    "Animation was automatically disabled due to rendering performance issues. " +
                        "Static glow will continue to work normally.",
                    NotificationType.WARNING,
                ).notify(null)
        } catch (exception: RuntimeException) {
            log.warn("Failed to show performance degradation notification: ${exception.message}")
        }
    }

    override fun dispose() {
        stop()
    }
}
