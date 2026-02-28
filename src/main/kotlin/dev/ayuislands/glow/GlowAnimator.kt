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

    // Reactive animation state
    private var reactiveBoost: Float = 0.0f
    private val reactiveDecayRate: Float = 0.92f

    fun start(animation: GlowAnimation, callback: (alpha: Float) -> Unit) {
        stop()
        if (animation == GlowAnimation.NONE) return

        currentAnimation = animation
        onFrame = callback
        frame = 0
        slowFrameCount = 0
        lastFrameTimeNanos = 0
        reactiveBoost = 0.0f

        timer = Timer(16) {
            val now = System.nanoTime()
            if (lastFrameTimeNanos > 0) {
                val elapsedMs = (now - lastFrameTimeNanos) / 1_000_000.0
                if (elapsedMs > 32.0) {
                    slowFrameCount++
                    if (slowFrameCount > 30) {
                        stop()
                        notifyPerformanceDegradation()
                        return@Timer
                    }
                } else {
                    slowFrameCount = (slowFrameCount - 1).coerceAtLeast(0)
                }
            }
            lastFrameTimeNanos = now

            val alpha = calculateAlpha(animation)
            frame++
            callback(alpha)
        }.also {
            it.isRepeats = true
            it.start()
        }

        log.info("Glow animation started: ${animation.displayName}")
    }

    private fun calculateAlpha(animation: GlowAnimation): Float {
        return when (animation) {
            GlowAnimation.PULSE -> {
                // Sharp brightening pulse every 2 seconds (120 frames at 60fps)
                val cycle = (frame % 120) / 120.0
                val pulse = if (cycle < 0.15) {
                    (cycle / 0.15).toFloat()
                } else {
                    ((1.0 - cycle) / 0.85).toFloat().coerceAtLeast(0f)
                }
                0.3f + pulse * 0.7f
            }
            GlowAnimation.BREATHE -> {
                // Slow sinusoidal breathing over 4 seconds (240 frames at 60fps)
                val cycle = (frame % 240) / 240.0
                val breath = (sin(cycle * Math.PI * 2) + 1.0) / 2.0
                0.2f + breath.toFloat() * 0.8f
            }
            GlowAnimation.REACTIVE -> {
                // Base alpha with boost from typing/actions (managed via boost() calls)
                reactiveBoost *= reactiveDecayRate
                if (reactiveBoost < 0.01f) reactiveBoost = 0.0f
                (0.4f + reactiveBoost * 0.6f).coerceAtMost(1.0f)
            }
            GlowAnimation.NONE -> 1.0f
        }
    }

    /** Trigger reactive brightening. Called externally on typing/action events. */
    fun boost() {
        if (currentAnimation == GlowAnimation.REACTIVE) {
            reactiveBoost = 1.0f
        }
    }

    fun stop() {
        timer?.stop()
        timer = null
        onFrame = null
        currentAnimation = GlowAnimation.NONE
        reactiveBoost = 0.0f
    }

    val isRunning: Boolean
        get() = timer?.isRunning == true

    private fun notifyPerformanceDegradation() {
        log.warn("Glow animation auto-disabled due to sustained poor performance")
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Ayu Islands")
                .createNotification(
                    "Glow animation disabled",
                    "Animation was automatically disabled due to rendering performance issues. " +
                        "Static glow will continue to work normally.",
                    NotificationType.WARNING,
                )
                .notify(null)
        } catch (exception: Exception) {
            log.warn("Failed to show performance degradation notification: ${exception.message}")
        }
    }

    override fun dispose() {
        stop()
    }
}
