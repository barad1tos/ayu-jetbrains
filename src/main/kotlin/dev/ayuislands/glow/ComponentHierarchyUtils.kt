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
     * Tries IslandHolder first, then InternalDecoratorImpl. On the 2026.1+
     * islands UI the visible rounded island is `XNextIslandHolder`, which wraps
     * the decorator (divider North, decorator Center) — hosting the overlay on
     * the decorator there paints glow strips inside the island instead of
     * hugging its edges. Older UIs have no holder and keep the decorator host.
     * If neither is found, logs a warning and returns [component] as a safe
     * fallback.
     */
    fun findGlowHost(
        component: JComponent,
        maxDepth: Int = 8,
    ): JComponent {
        val island = findAncestorByClassName(component, "IslandHolder", maxDepth)
        if (island != null) return island as JComponent

        val decorator = findAncestorByClassName(component, "InternalDecoratorImpl", maxDepth)
        if (decorator != null) return decorator as JComponent

        log.warn(
            "No IslandHolder or InternalDecoratorImpl found for ${component.javaClass.name}, " +
                "using component directly",
        )
        return component
    }

    /**
     * One-line ancestry description for attach-time diagnostics:
     * `Class[x,y WxH] < Parent[..] < ...` from [component] upward, so idea.log
     * shows which containers host resolution actually saw on the running
     * platform version.
     */
    fun describeAncestry(
        component: Component,
        maxDepth: Int = 10,
    ): String {
        val chain = StringBuilder()
        var current: Component? = component
        var depth = 0
        while (current != null && depth < maxDepth) {
            if (depth > 0) chain.append(" < ")
            chain
                .append(current.javaClass.simpleName.ifEmpty { "?" })
                .append('[')
                .append(current.x)
                .append(',')
                .append(current.y)
                .append(' ')
                .append(current.width)
                .append('x')
                .append(current.height)
                .append(']')
            current = current.parent
            depth++
        }
        return chain.toString()
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
