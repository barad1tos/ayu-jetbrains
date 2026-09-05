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
        if (isTextInputComponent(component) && component is JComponent && !focusListeners.containsKey(component)) {
            val listener = GlowFocusListener(component, accent, style, intensity) { focusListeners.remove(component) }
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
