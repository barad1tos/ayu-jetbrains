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
        private val captures = focusListeners.values.associateWith(GlowFocusListener::captureCallbacks)
        private val shared = mutableMapOf<GlowFocusListener, SharedGlowFocus>()
        private var released = false

        val isActive: Boolean get() = callbackHold === this && !released

        fun listeners(): List<GlowFocusListener> = focusListeners.values.toList()

        fun isRegistered(listener: GlowFocusListener): Boolean = focusListeners[listener.component] === listener

        fun canCapture(listener: GlowFocusListener): Boolean = isActive && isRegistered(listener) && listener !in shared

        fun share(
            listener: GlowFocusListener,
            focus: SharedGlowFocus,
        ) {
            shared[listener] = focus
        }

        fun captureAppearance(
            transform: (GlowFocusAppearance) -> GlowFocusAppearance,
        ): List<GlowFocusListener.Checkpoint> =
            captureGlowFocus(listOf(this)) { _, appearance -> transform(appearance) }

        fun release() {
            check(SwingUtilities.isEventDispatchThread()) { "Glow focus holds must run on EDT" }
            if (!isActive) return
            released = true
            try {
                reconcileCaptured()
            } catch (cancelled: CancellationException) {
                abandonShared()
                throw cancelled
            } finally {
                finishRelease()
            }
        }

        private fun reconcileCaptured() {
            val failures = mutableListOf<RuntimeException>()
            for ((listener, capture) in captures) {
                if (listener !in shared) reconcileStep(failures) { capture.reconcile() }
            }
            // A local border listener can synchronously dispatch focus to a shared field.
            for ((listener, focus) in shared) {
                reconcileStep(failures) { focus.release(listener) }
            }
            failures.firstOrNull()?.let { first ->
                failures.drop(1).filterNot { it === first }.forEach(first::addSuppressed)
                throw first
            }
        }

        private inline fun reconcileStep(
            failures: MutableList<RuntimeException>,
            action: () -> Unit,
        ) {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: RuntimeException) {
                failures.add(failure)
            }
        }

        fun abandonShared() {
            shared.values.forEach(SharedGlowFocus::abandon)
        }

        fun finishRelease() {
            if (released && callbackHold === this && shared.values.all { it.isSettled }) callbackHold = null
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
        callbackHold?.release()
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
