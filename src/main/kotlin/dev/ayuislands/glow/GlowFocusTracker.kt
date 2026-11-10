package dev.ayuislands.glow

import java.awt.KeyboardFocusManager
import java.beans.PropertyChangeListener

/** Owns only its exact listener on the original native focus manager. */
internal class GlowFocusTracker(
    onChange: () -> Unit,
) {
    private val listener = PropertyChangeListener { onChange() }
    private var manager: KeyboardFocusManager? = null
    private var isRegistered = false
    private var isDisposed = false

    fun register() {
        check(!isDisposed) { "Glow focus tracking is disposed" }
        if (isRegistered) return
        val target = manager ?: KeyboardFocusManager.getCurrentKeyboardFocusManager().also { manager = it }
        if (target.getPropertyChangeListeners(PROPERTY).none { it === listener }) {
            target.addPropertyChangeListener(PROPERTY, listener)
        }
        isRegistered = true
    }

    fun dispose() {
        isDisposed = true
        val target = manager ?: return
        if (target.getPropertyChangeListeners(PROPERTY).any { it === listener }) {
            target.removePropertyChangeListener(PROPERTY, listener)
        }
        manager = null
        isRegistered = false
    }

    private companion object {
        const val PROPERTY = "permanentFocusOwner"
    }
}
