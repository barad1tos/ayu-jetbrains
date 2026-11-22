package dev.ayuislands.studio.session

import dev.ayuislands.accent.combineUiFailures
import dev.ayuislands.accent.runCatchingPreservingCancellation
import dev.ayuislands.glow.GlowOverlayManager
import javax.swing.SwingUtilities

/** Apply's lease owns both pane playback and focus callbacks for each affected manager. */
internal class StudioGlowHolds {
    private val held = linkedMapOf<GlowOverlayManager, GlowOverlayManager.UpdateHold>()
    private val pendingRefresh = linkedSetOf<GlowOverlayManager>()
    private var releasing = false

    fun hold(manager: GlowOverlayManager): GlowOverlayManager.UpdateHold {
        checkEdt()
        check(!releasing) { "Glow holds are already settling" }
        return held.getOrPut(manager, manager::holdUpdates)
    }

    /** End all holds before refreshing. Failed steps remain pending for the next settlement attempt. */
    fun release(refresh: (GlowOverlayManager) -> Unit) {
        checkEdt()
        releasing = true
        settleGlowActions(held.keys.toList().asReversed()) { manager ->
            if (held.getValue(manager).release()) pendingRefresh.add(manager)
            held.remove(manager)
        }
        settleGlowActions(pendingRefresh.toList()) { manager ->
            refresh(manager)
            pendingRefresh.remove(manager)
        }
    }

    private fun checkEdt() {
        check(SwingUtilities.isEventDispatchThread()) { "Glow manager holds must run on EDT" }
    }
}

internal fun <T> settleGlowActions(
    values: List<T>,
    action: (T) -> Unit,
) {
    var failure: Throwable? = null
    for (value in values) {
        val problem = runCatchingPreservingCancellation { action(value) }.exceptionOrNull()
        if (problem != null) failure = combineUiFailures(failure, problem)
    }
    failure?.let { throw it }
}
