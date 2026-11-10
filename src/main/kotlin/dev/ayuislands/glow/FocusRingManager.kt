package dev.ayuislands.glow

import com.intellij.openapi.diagnostic.logger
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Window
import java.util.WeakHashMap
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

    internal fun holdCallbacks(): CallbackHold {
        check(SwingUtilities.isEventDispatchThread()) { "Glow focus holds must run on EDT" }
        check(callbackHold == null) { "Glow focus callbacks are already held" }
        return CallbackHold().also { callbackHold = it }
    }

    internal inner class CallbackHold {
        val components: List<JComponent> = java.util.List.copyOf(focusListeners.keys)
        val listeners: List<GlowFocusListener> = java.util.List.copyOf(focusListeners.values)
        private var sharedBorders: Map<JComponent, GlowFocusBorderPlan>? = null

        val isActive: Boolean
            get() = !isReleased && callbackHold === this

        fun selectBorderWriters(plans: Map<JComponent, GlowFocusBorderPlan>) {
            check(SwingUtilities.isEventDispatchThread()) { "Glow focus selection must run on EDT" }
            check(isActive && sharedBorders == null) { "Glow focus selection is no longer available" }
            sharedBorders = java.util.Collections.unmodifiableMap(java.util.IdentityHashMap(plans))
        }

        fun captureShared(
            peers: Map<GlowFocusListener, GlowFocusPeer>,
            plans: Map<JComponent, GlowFocusBorderPlan>,
            isAvailable: () -> Boolean,
        ): List<GlowFocusListener.Checkpoint> {
            check(SwingUtilities.isEventDispatchThread()) { "Glow focus capture must run on EDT" }
            check(isActive && isAvailable()) { "Glow focus hold is unavailable" }
            return listeners.mapNotNull { listener ->
                plans[listener.component]?.let { plan ->
                    listener.captureShared(peers.getValue(listener).planned, plan) {
                        isActive && isAvailable() && focusListeners[listener.component] === listener
                    }
                }
            }
        }

        fun capture(
            isAvailable: () -> Boolean,
            transform: (GlowFocusAppearance) -> GlowFocusAppearance,
        ): List<GlowFocusListener.Checkpoint> {
            check(SwingUtilities.isEventDispatchThread()) { "Glow focus capture must run on EDT" }
            val isCurrent = { !isReleased && callbackHold === this && isAvailable() }
            check(isCurrent()) { "Glow focus hold is unavailable" }
            return this@FocusRingManager.capture(isCurrent, transform)
        }

        fun prepare(
            planned: GlowFocusAppearance,
            excluded: Collection<JComponent>,
            isAvailable: () -> Boolean,
        ): List<GlowFocusAttachment> {
            check(SwingUtilities.isEventDispatchThread()) { "Glow focus preparation must run on EDT" }
            check(!isReleased && callbackHold === this && isAvailable()) { "Glow focus hold is unavailable" }
            if (!planned.enabled) return emptyList()
            val attachments = mutableListOf<GlowFocusAttachment>()
            for (window in Window.getWindows()) {
                visitInputs(window) { component ->
                    if (!focusListeners.containsKey(component) && excluded.none { it === component }) {
                        val listener = createListener(component, planned)
                        val attachment =
                            GlowFocusAttachment(component, listener, focusListeners) {
                                !isReleased && callbackHold === this && isAvailable()
                            }
                        captures.add(attachment.callbacks)
                        attachments.add(attachment)
                    }
                }
            }
            return java.util.List.copyOf(attachments)
        }

        private val captures =
            focusListeners
                .map { (component, listener) ->
                    listener.captureCallbacks { focusListeners[component] === listener }
                }.toMutableList()
        private var isReleased = false

        fun release() {
            check(SwingUtilities.isEventDispatchThread()) { "Glow focus holds must run on EDT" }
            if (isReleased) return
            check(callbackHold === this) { "Glow focus hold was replaced" }
            val failures = mutableListOf<RuntimeException>()
            for (capture in captures) {
                try {
                    val writer = sharedBorders?.get(capture.listener.component)?.writer
                    capture.reconcile(writer == null || writer === capture.listener)
                } catch (exception: RuntimeException) {
                    failures.add(exception)
                }
            }
            failures.firstOrNull()?.let { first ->
                failures.drop(1).filterNot { it === first }.forEach(first::addSuppressed)
                throw first
            }
            callbackHold = null
            isReleased = true
        }
    }

    internal fun capture(
        isAvailable: () -> Boolean = { true },
        transform: (GlowFocusAppearance) -> GlowFocusAppearance,
    ): List<GlowFocusListener.Checkpoint> =
        focusListeners.mapNotNull { (component, listener) ->
            listener.capture(transform) { focusListeners[component] === listener && isAvailable() }
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
    }

    private fun installFocusListenersRecursively(
        component: Component,
        accent: Color,
        style: GlowStyle,
        intensity: Int,
    ) {
        val appearance = GlowFocusAppearance(accent, style, intensity, enabled = true)
        visitInputs(component) { input ->
            if (!focusListeners.containsKey(input)) {
                val listener = createListener(input, appearance)
                focusListeners[input] = listener
                listener.attach()
            }
        }
    }

    private fun createListener(
        component: JComponent,
        appearance: GlowFocusAppearance,
    ): GlowFocusListener {
        lateinit var listener: GlowFocusListener
        listener =
            GlowFocusListener(
                component,
                appearance.color,
                appearance.style,
                appearance.intensity,
                callbacksAllowed = { callbackHold == null },
                ownsComponent = { focusListeners[component] === listener },
            ) { detached ->
                if (focusListeners[component] === detached) focusListeners.remove(component)
            }
        return listener
    }

    private fun visitInputs(
        component: Component,
        visit: (JComponent) -> Unit,
    ) {
        if (isTextInputComponent(component) && component is JComponent) visit(component)
        if (component is Container) {
            for (child in component.components) {
                visitInputs(child, visit)
            }
        }
    }
}
