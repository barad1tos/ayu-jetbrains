package dev.ayuislands.studio.session

import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.glow.GlowUpdateGate
import javax.swing.SwingUtilities

internal data class HeldGlowManager(
    val manager: GlowOverlayManager,
    val hold: GlowUpdateGate.Hold,
)

/** Retain this owner before acquisition; a failed acquisition still owns its captured holds. */
internal class StudioGlowHolds(
    managers: List<GlowOverlayManager>,
) {
    private val owners = java.util.List.copyOf(managers)
    private val captured = mutableListOf<HeldGlowManager>()
    private var phase = Phase.PREPARED

    init {
        require(owners.distinct().size == owners.size) { "Glow manager census contains duplicate owners" }
    }

    val entries: List<HeldGlowManager>
        get() {
            checkEdt()
            return java.util.List.copyOf(captured)
        }

    val isActive: Boolean
        get() {
            checkEdt()
            return phase == Phase.HELD && captured.all { it.hold.isActive }
        }

    fun acquire() {
        checkEdt()
        check(phase == Phase.PREPARED) { "Glow acquisition was already started" }
        phase = Phase.ACQUIRING
        for (manager in owners) captured.add(HeldGlowManager(manager, manager.holdUpdates()))
        phase = Phase.HELD
    }

    /** Independent managers can settle even if another manager needs a cleanup retry. */
    fun release() {
        checkEdt()
        if (phase == Phase.RELEASED) return
        phase = Phase.RELEASING
        val failures = mutableListOf<RuntimeException>()
        for (entry in captured.asReversed()) {
            try {
                entry.hold.release()
            } catch (exception: RuntimeException) {
                failures.add(exception)
            }
        }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).filterNot { it === first }.forEach(first::addSuppressed)
            throw first
        }
        phase = Phase.RELEASED
    }

    private fun checkEdt() {
        check(SwingUtilities.isEventDispatchThread()) { "Glow manager holds must run on EDT" }
    }

    private enum class Phase { PREPARED, ACQUIRING, HELD, RELEASING, RELEASED }
}
