package dev.ayuislands.glow

import dev.ayuislands.accent.AccentHex
import javax.swing.SwingUtilities

/** One manager's callback admission; retained native checkpoints remain with their surface owners. */
internal class GlowUpdateGate(
    private val isDisposed: () -> Boolean,
    private val readInput: () -> GlowRuntimeInput?,
    private val holdFocus: () -> FocusRingManager.CallbackHold,
) {
    @Volatile
    private var generation = Any()

    @Volatile
    private var activeHold: Hold? = null

    val isOpen: Boolean
        get() = !isDisposed() && activeHold == null && readInput() != null

    val current: GlowRuntimeInput
        get() {
            val held = activeHold
            return checkNotNull(
                if (held ==
                    null
                ) {
                    readInput()
                } else {
                    held.selected
                },
            ) { "Accepted Glow input is unavailable" }
        }

    fun isHeldBy(hold: Hold): Boolean = !isDisposed() && activeHold === hold

    fun hold(): Hold {
        check(SwingUtilities.isEventDispatchThread()) { "Glow update holds must run on EDT" }
        check(!isDisposed() && activeHold == null) { "Glow manager is unavailable or already held" }
        val held = Hold(readInput(), holdFocus())
        generation = Any()
        activeHold = held
        return held
    }

    inner class Hold(
        val before: GlowRuntimeInput?,
        val focus: FocusRingManager.CallbackHold,
    ) {
        var selected: GlowRuntimeInput? = before
            private set
        var pendingAccent: AccentHex? = null
            private set
        var needsRefresh = false
            private set
        private var isReleased = false

        val isActive: Boolean
            get() = isHeldBy(this)

        /** Select after the outer write acknowledgement; native effects remain with their checkpoints. */
        fun select(input: GlowRuntimeInput?) {
            check(SwingUtilities.isEventDispatchThread()) { "Glow update holds must run on EDT" }
            check(!isReleased && activeHold === this) { "Glow update hold is no longer active" }
            check(!isDisposed()) { "Glow manager is unavailable" }
            selected = input
        }

        fun defer(accent: AccentHex?) {
            needsRefresh = true
            if (accent != null) pendingAccent = accent
        }

        /** The outer runtime owner reconciles pending work; never replay legacy setters here. */
        fun release() {
            check(SwingUtilities.isEventDispatchThread()) { "Glow update holds must run on EDT" }
            if (isReleased) return
            check(activeHold === this) { "Glow update hold was replaced" }
            if (!isDisposed()) focus.release()
            generation = Any()
            activeHold = null
            isReleased = true
        }
    }

    fun admit(accent: AccentHex? = null): Boolean {
        if (isDisposed()) return false
        val held = activeHold ?: return readInput() != null
        held.defer(accent)
        return false
    }

    fun queue(
        accent: AccentHex? = null,
        action: () -> Unit,
    ) {
        val queuedGeneration = generation
        SwingUtilities.invokeLater {
            if (queuedGeneration !== generation) {
                admit()
            } else if (admit(accent)) {
                action()
            }
        }
    }
}
