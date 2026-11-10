package dev.ayuislands.glow

import com.intellij.openapi.util.Disposer
import dev.ayuislands.glow.waveform.WaveformConfig
import javax.swing.SwingUtilities

/** Retains one captured shutdown attempt before appearance or membership changes. */
internal class GlowPlaybackPause(
    private val hold: GlowUpdateGate.Hold,
    val overlays: GlowOverlaySnapshot,
    val animation: GlowAnimation,
    val routeConfig: WaveformConfig?,
    private val stopRoute: () -> Unit,
    private val stopAnimator: () -> Unit,
    private val stopPane: (RouteOverlay) -> Unit,
) {
    val targets: List<RouteOverlay> = overlays.selected
    private var isRouteStopped = false
    private var isAnimatorStopped = false
    private val stoppedPanes = mutableSetOf<String>()

    val isComplete: Boolean
        get() = isRouteStopped && isAnimatorStopped && stoppedPanes.size == targets.size

    fun checkStopped(expectedHold: GlowUpdateGate.Hold) {
        check(SwingUtilities.isEventDispatchThread()) { "Glow playback restart must run on EDT" }
        check(hold === expectedHold && hold.isActive) { "Glow playback hold is unavailable" }
        check(isComplete) { "Glow playback shutdown is incomplete" }
    }

    fun checkRestart(expectedHold: GlowUpdateGate.Hold) {
        checkStopped(expectedHold)
        overlays.checkCurrent()
    }

    fun planRestart(expectedHold: GlowUpdateGate.Hold): GlowPlaybackPlan {
        checkRestart(expectedHold)
        return GlowPlaybackPlan(overlays, animation, routeConfig)
    }

    fun stop(isBlocked: Boolean = false) {
        check(SwingUtilities.isEventDispatchThread()) { "Glow playback must stop on EDT" }
        check(!isBlocked) { "A newer Glow receipt owns playback" }
        check(hold.isActive) { "Glow playback hold is unavailable" }
        val failures = mutableListOf<RuntimeException>()
        if (!isRouteStopped) isRouteStopped = attempt(failures, stopRoute)
        if (!isAnimatorStopped) isAnimatorStopped = attempt(failures, stopAnimator)
        for (target in targets) {
            if (target.id !in stoppedPanes && attempt(failures) { stopPane(target) }) stoppedPanes.add(target.id)
        }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).filterNot { it === first }.forEach(first::addSuppressed)
            throw first
        }
    }

    private fun attempt(
        failures: MutableList<RuntimeException>,
        action: () -> Unit,
    ): Boolean =
        try {
            check(hold.isActive) { "Glow playback hold is unavailable" }
            action()
            check(hold.isActive) { "Glow playback hold expired during shutdown" }
            true
        } catch (exception: RuntimeException) {
            failures.add(exception)
            false
        }
}

/** Native ownership checks stay beside the captured cleanup, not in the session coordinator. */
internal fun captureGlowPlayback(
    hold: GlowUpdateGate.Hold,
    entries: MutableMap<String, GlowOverlayManager.OverlayEntry>,
    route: RouteController,
    readAnimator: () -> GlowAnimator?,
    clearAnimator: () -> Unit,
): GlowPlaybackPause {
    check(SwingUtilities.isEventDispatchThread()) { "Glow playback capture must run on EDT" }
    check(hold.isActive) { "Glow playback requires an active hold" }
    val census = GlowOverlaySnapshot(entries, hold)
    val previousAnimator = readAnimator()
    return GlowPlaybackPause(
        hold,
        census,
        previousAnimator?.currentAnimation ?: GlowAnimation.NONE,
        route.currentConfig,
        stopRoute = {
            census.checkCurrent()
            route.dispose(requireCompletion = true)
            census.checkCurrent()
        },
        stopAnimator = {
            check(readAnimator() === previousAnimator) { "Glow animator was replaced" }
            previousAnimator?.stop()
            previousAnimator?.let(Disposer::dispose)
            check(readAnimator() === previousAnimator) { "Glow animator changed during shutdown" }
            clearAnimator()
        },
        stopPane = { target ->
            census.checkTarget(target)
            target.pane.stopAnimation()
            census.checkTarget(target)
        },
    )
}
