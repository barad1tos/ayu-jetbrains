package dev.ayuislands.glow

import java.awt.Container
import java.awt.Window
import javax.swing.JComponent
import javax.swing.SwingUtilities

/** Retains a newly prepared focus listener until its native membership is accepted or restored. */
internal class GlowFocusAttachment(
    val component: JComponent,
    val listener: GlowFocusListener,
    private val entries: MutableMap<JComponent, GlowFocusListener>,
    private val isAvailable: () -> Boolean,
) {
    val parent: Container? = component.parent
    val window: Window? = SwingUtilities.getWindowAncestor(component)
    val callbacks = listener.captureCallbacks { entries[component] === listener }
    val border = listener.prepareInitialBorder()
    var phase: GlowCheckpointPhase = GlowCheckpointPhase.PREPARED
        private set

    val hasPending: Boolean
        get() =
            phase == GlowCheckpointPhase.APPLYING ||
                phase == GlowCheckpointPhase.APPLIED ||
                phase == GlowCheckpointPhase.RESTORING

    fun apply() {
        checkEdt()
        check(phase == GlowCheckpointPhase.PREPARED) { "Glow focus attachment was already started" }
        checkHost()
        check(entries[component] == null && component.focusListeners.none { it is GlowFocusListener }) {
            "Glow focus target is already occupied"
        }
        check(component.hierarchyListeners.none { it is GlowFocusListener }) { "Glow focus target is already occupied" }
        border.checkPrepared()
        phase = GlowCheckpointPhase.APPLYING
        entries[component] = listener
        listener.attach()
        checkAttached()
        border.apply()
        checkAttached()
        phase = GlowCheckpointPhase.APPLIED
    }

    fun restore(isBlocked: Boolean = false) {
        checkEdt()
        check(phase != GlowCheckpointPhase.ACCEPTED) { "Accepted Glow focus attachments cannot be restored" }
        if (phase == GlowCheckpointPhase.RESTORED) return
        if (hasPending) {
            check(!isBlocked) { "A newer Glow receipt owns this focus attachment" }
            phase = GlowCheckpointPhase.RESTORING
            try {
                listener.detach()
            } finally {
                border.settle()
            }
            check(
                component.focusListeners.none { it === listener } &&
                    component.hierarchyListeners.none { it === listener },
            ) {
                "Glow focus cleanup did not settle"
            }
            if (entries[component] === listener) entries.remove(component)
        }
        border.settle()
        phase = GlowCheckpointPhase.RESTORED
    }

    fun accept() {
        checkEdt()
        if (phase == GlowCheckpointPhase.ACCEPTED) return
        check(phase == GlowCheckpointPhase.APPLIED) { "Glow focus attachment has not completed" }
        checkAttached()
        phase = GlowCheckpointPhase.ACCEPTED
    }

    private fun checkAttached() {
        checkHost()
        check(entries[component] === listener) { "Glow focus attachment was replaced" }
        check(
            component.focusListeners.any { it === listener } &&
                component.hierarchyListeners.any { it === listener },
        ) {
            "Glow focus registration did not settle"
        }
    }

    private fun checkHost() {
        check(isAvailable()) { "Glow focus owner is unavailable" }
        check(component.parent === parent && SwingUtilities.getWindowAncestor(component) === window) {
            "Glow focus component moved to another host"
        }
    }

    private fun checkEdt() {
        check(SwingUtilities.isEventDispatchThread()) { "Glow focus attachments must run on EDT" }
    }
}
