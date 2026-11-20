package dev.ayuislands.glow

import com.intellij.openapi.diagnostic.logger
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Window
import java.util.WeakHashMap
import java.util.concurrent.CancellationException
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JTextField
import javax.swing.SwingUtilities

/**
 * Manages focus-ring glow borders on text input components.
 *
 * Uses a [WeakHashMap] to track already-processed windows so that
 * repeated calls (e.g., on settings change) skip windows whose
 * components already have listeners installed, improving performance
 * on projects with many open windows.
 */
class FocusRingManager {
    private val log = logger<FocusRingManager>()
    private val focusListeners = mutableMapOf<JComponent, GlowFocusListener>()
    private val processedWindows = WeakHashMap<Window, Unit>()
    private var callbackHold: CallbackHold? = null

    /** Keep focus callbacks off the captured borders until the enclosing change has settled. */
    internal fun holdCallbacks(): CallbackHold {
        check(SwingUtilities.isEventDispatchThread()) { "Glow focus holds must run on EDT" }
        check(callbackHold == null) { "Glow focus callbacks are already held" }
        return CallbackHold().also { callbackHold = it }
    }

    internal inner class CallbackHold {
        private val captures = focusListeners.values.map(GlowFocusListener::captureCallbacks)

        fun release() {
            check(SwingUtilities.isEventDispatchThread()) { "Glow focus holds must run on EDT" }
            if (callbackHold !== this) return
            val failures = mutableListOf<RuntimeException>()
            try {
                for (capture in captures) {
                    try {
                        capture.reconcile()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: RuntimeException) {
                        failures.add(failure)
                    }
                }
            } finally {
                if (callbackHold === this) callbackHold = null
            }
            failures.firstOrNull()?.let { first ->
                failures.drop(1).filterNot { it === first }.forEach(first::addSuppressed)
                throw first
            }
        }
    }

    fun isTextInputComponent(component: Component): Boolean =
        component is JTextField ||
            component is JComboBox<*> ||
            (component is JComponent && component.javaClass.simpleName.contains("SearchTextField"))

    /**
     * Install a focus-ring glow on all text inputs across all open windows.
     *
     * Windows already in [processedWindows] are skipped for performance.
     */
    fun initializeFocusRingGlow(
        accent: Color,
        style: GlowStyle,
        intensity: Int,
    ) {
        if (callbackHold != null) return
        for (window in Window.getWindows()) {
            if (processedWindows.containsKey(window)) continue
            installFocusListenersRecursively(window, accent, style, intensity)
            processedWindows[window] = Unit
        }
        log.info("Focus-ring glow initialized")
    }

    /**
     * Re-install focus-ring listeners after a settings change.
     *
     * Removes all existing listeners, clears the processed-windows cache,
     * and re-walks all windows with the new parameters. Self-contained —
     * callers don't need to clear the cache separately.
     */
    fun updateFocusRingGlow(
        accent: Color,
        style: GlowStyle,
        intensity: Int,
        enabled: Boolean,
    ) {
        if (callbackHold != null) return
        removeFocusListeners()
        processedWindows.clear()
        if (enabled) {
            for (window in Window.getWindows()) {
                installFocusListenersRecursively(window, accent, style, intensity)
                processedWindows[window] = Unit
            }
        }
    }

    fun removeFocusListeners() {
        for (listener in focusListeners.values.toList()) {
            listener.detach()
        }
        focusListeners.clear()
    }

    fun dispose() {
        removeFocusListeners()
        processedWindows.clear()
        callbackHold = null
    }

    private fun installFocusListenersRecursively(
        component: Component,
        accent: Color,
        style: GlowStyle,
        intensity: Int,
    ) {
        if (isTextInputComponent(component) && component is JComponent && !focusListeners.containsKey(component)) {
            val listener =
                GlowFocusListener(component, accent, style, intensity, callbacksAllowed = { callbackHold == null }) {
                    focusListeners.remove(component)
                }
            focusListeners[component] = listener
            listener.attach()
        }
        if (component is Container) {
            for (child in component.components) {
                installFocusListenersRecursively(child, accent, style, intensity)
            }
        }
    }
}
