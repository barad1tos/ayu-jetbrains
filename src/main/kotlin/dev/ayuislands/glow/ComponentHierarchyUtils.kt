package dev.ayuislands.glow

import com.intellij.openapi.diagnostic.logger
import java.awt.Component
import java.awt.Container
import javax.swing.JComponent

/**
 * Swing component hierarchy walking utilities with defensive fallbacks.
 *
 * All lookups use class name substring matching (not exact class references),
 * so glow rendering survives internal Swing/IDE class renames across versions.
 */
object ComponentHierarchyUtils {
    private val log = logger<ComponentHierarchyUtils>()

    /**
     * Walk up the parent chain from [component], returning the first ancestor
     * whose class name contains [className]. Stops after [maxDepth] levels.
     */
    fun findAncestorByClassName(
        component: Component,
        className: String,
        maxDepth: Int = 6,
    ): Component? {
        var current: Component? = component.parent
        var depth = 0
        while (current != null && depth < maxDepth) {
            if (current.javaClass.name.contains(className)) {
                return current
            }
            current = current.parent
            depth++
        }
        return null
    }

    /**
     * Search immediate children of [container] for the first component
     * whose class name contains [className].
     */
    fun findChildByClassName(
        container: Container,
        className: String,
    ): Component? = container.components.firstOrNull { it.javaClass.name.contains(className) }

    /**
     * Locate the glow host for a tool window component.
     *
     * Tries InternalDecoratorImpl first, then IslandHolder. If neither is found,
     * logs a warning and returns the original [component] as a safe fallback.
     */
    fun findGlowHost(
        component: JComponent,
        maxDepth: Int = 6,
    ): JComponent {
        val decorator = findAncestorByClassName(component, "InternalDecoratorImpl", maxDepth)
        if (decorator != null) return decorator as JComponent

        val island = findAncestorByClassName(component, "IslandHolder", maxDepth)
        if (island != null) return island as JComponent

        log.warn(
            "No InternalDecoratorImpl or IslandHolder found for ${component.javaClass.name}, " +
                "using component directly",
        )
        return component
    }

    /**
     * Walk up from [editorComponent] to find the EditorsSplitters container.
     *
     * Returns null if no ancestor matches (caller decides fallback behavior).
     */
    fun findEditorHost(editorComponent: JComponent): JComponent? {
        var current: Component? = editorComponent
        while (current != null) {
            if (current.javaClass.name.contains("EditorsSplitters")) {
                return current as JComponent
            }
            current = current.parent
        }
        return null
    }
}
