package dev.ayuislands.glow

import javax.swing.SwingUtilities

/** Blocks legacy writes while the native owner settles; deferred events become a fresh-read signal. */
internal class GlowUpdateGate(
    private val isDisposed: () -> Boolean,
    private val holdFocus: () -> FocusRingManager.CallbackHold,
) {
    private val lock = Any()
    private val queued = mutableSetOf<Any>()
    private var currentHold: Hold? = null

    fun hold(): Hold {
        check(SwingUtilities.isEventDispatchThread()) { "Glow update holds must run on EDT" }
        return synchronized(lock) {
            check(!isDisposed() && currentHold == null) { "Glow manager is unavailable or already held" }
            Hold(holdFocus(), queued.isNotEmpty()).also {
                currentHold = it
                queued.clear()
            }
        }
    }

    inner class Hold(
        val focusCallbacks: FocusRingManager.CallbackHold,
        private var refreshRequested: Boolean,
    ) {
        val isActive: Boolean
            get() = synchronized(lock) { currentHold === this && !isDisposed() }

        fun invalidate() {
            refreshRequested = true
        }

        /** Return whether the owner must reconcile against the current environment after settlement. */
        fun release(): Boolean {
            check(SwingUtilities.isEventDispatchThread()) { "Glow update holds must run on EDT" }
            if (synchronized(lock) { currentHold === this }) {
                try {
                    if (!isDisposed()) focusCallbacks.release()
                } finally {
                    synchronized(lock) {
                        if (currentHold === this) currentHold = null
                    }
                }
            }
            return synchronized(lock) { !isDisposed() && refreshRequested }
        }
    }

    fun admit(invalidates: Boolean = true): Boolean = synchronized(lock) { admitLocked(invalidates) }

    private fun admitLocked(invalidates: Boolean): Boolean {
        if (isDisposed()) return false
        val hold = currentHold ?: return true
        if (invalidates) hold.invalidate()
        return false
    }

    fun dispatch(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            if (admit()) action()
        } else {
            queue(action)
        }
    }

    fun queue(action: () -> Unit) {
        val ticket = Any()
        synchronized(lock) {
            if (!admitLocked(invalidates = true)) return
            queued.add(ticket)
        }
        SwingUtilities.invokeLater {
            if (synchronized(lock) { queued.remove(ticket) && !isDisposed() }) action()
        }
    }
}
