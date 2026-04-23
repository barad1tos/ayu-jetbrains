package dev.ayuislands.accent

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.WindowManager
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Window
import javax.swing.JComponent

/**
 * Level 2 Gap-4 helper — finds live Swing peers of chrome surfaces and mutates their
 * `background` directly + forces a `repaint`.
 *
 * Why is this needed on top of UIManager writes + the apply-path `notifyOnly` hook
 * from plan 40-13? Platform chrome peers (`IdeStatusBarImpl`, `MyNavBarWrapperPanel`,
 * `com.intellij.toolWindow.Stripe`, internal `MainToolbar`, `OnePixelDivider`) cache
 * colors at creation or on the prior LAF event — UIManager.put alone does NOT cause
 * already-rendered components to re-read the key. See VERIFICATION Gap 4.
 *
 * Per plan 40-14 (research §B), most target peer classes are internal / package-private
 * (`Stripe`, `MainToolbar`, `OnePixelDivider`, `MyNavBarWrapperPanel`), so we cannot
 * import them. All lookups walk `Window.getWindows()` and match by runtime class-name
 * string. `JComponent#setBackground` is safe on any live peer on the EDT.
 *
 * D-14 symmetry: every `refresh*` has a matching `clear*` that sets the background to
 * `null` (returns the component to LAF default). Callers wire both apply and revert.
 *
 * EDT: callers MUST already be on EDT — Swing background mutation + repaint are
 * EDT-only. `AccentApplicator` dispatches its apply/revert Runnable through
 * `invokeLaterSafe`, which satisfies this precondition.
 */
internal object LiveChromeRefresher {
    private val log = Logger.getInstance(LiveChromeRefresher::class.java)

    // --- StatusBar (resolvable via public WindowManager API) ---

    fun refreshStatusBar(color: Color) {
        forEachUsableStatusBarComponent { component ->
            component.background = color
            component.repaint()
        }
    }

    fun clearStatusBar() {
        forEachUsableStatusBarComponent { component ->
            component.background = null
            component.repaint()
        }
    }

    private inline fun forEachUsableStatusBarComponent(action: (JComponent) -> Unit) {
        val projectManager =
            runCatching { ProjectManager.getInstance() }
                .onFailure { log.debug("ProjectManager unavailable during live refresh", it) }
                .getOrNull() ?: return
        val windowManager =
            runCatching { WindowManager.getInstance() }
                .onFailure { log.debug("WindowManager unavailable during live refresh", it) }
                .getOrNull() ?: return
        for (project in projectManager.openProjects) {
            if (!project.isUsable()) continue
            val statusBar = windowManager.getStatusBar(project) ?: continue
            val component = statusBar.component as? JComponent ?: continue
            action(component)
        }
    }

    // --- Class-name-based frame walk (internal platform peer types) ---

    fun refreshByClassName(
        classNameFqn: String,
        color: Color,
    ) {
        forEachShowingWindow { refreshOnTree(it, classNameFqn, color) }
    }

    fun clearByClassName(classNameFqn: String) {
        forEachShowingWindow { clearOnTree(it, classNameFqn) }
    }

    /**
     * Ancestor-constrained variant of [refreshByClassName]. Mutates a matching [targetFqn]
     * only when the component sits inside a container whose runtime class name equals
     * [ancestorFqn]. Used for shared peer types (`OnePixelDivider`) whose instances live
     * all over the IDE — tinting every one of them would leak panel-border styling into
     * editor splitters, diff gutters, Settings dialog splitters, etc. See Phase 40
     * review Round 2 A-1.
     */
    fun refreshByClassNameInsideAncestorClass(
        targetFqn: String,
        ancestorFqn: String,
        color: Color,
    ) {
        forEachShowingWindow { refreshOnTreeInsideAncestor(it, targetFqn, ancestorFqn, color) }
    }

    /** Mirror of [refreshByClassNameInsideAncestorClass] for the revert path. */
    fun clearByClassNameInsideAncestorClass(
        targetFqn: String,
        ancestorFqn: String,
    ) {
        forEachShowingWindow { clearOnTreeInsideAncestor(it, targetFqn, ancestorFqn) }
    }

    /**
     * Iterates top-level windows, skipping ones that are not [Window.isShowing] (disposed,
     * hidden, pooled popups) and isolating per-window failures so one flaky peer doesn't
     * abort chrome apply for the rest of the desktop. See Phase 40 review Round 3 C-1, C-2.
     */
    private inline fun forEachShowingWindow(action: (Window) -> Unit) {
        for (window in Window.getWindows()) {
            if (!window.isShowing) continue
            runCatching { action(window) }.onFailure {
                log.debug("Live refresh skipped for ${window.javaClass.simpleName}", it)
            }
        }
    }

    /**
     * Visible for tests — traverses [root] (and every descendant Container) and mutates
     * any [JComponent] whose runtime class name equals [classNameFqn].
     */
    internal fun refreshOnTree(
        root: Component,
        classNameFqn: String,
        color: Color,
    ) {
        walk(root) { component ->
            if (component is JComponent && component.javaClass.name == classNameFqn) {
                component.background = color
                component.repaint()
            }
        }
    }

    /** Visible for tests — mirror of [refreshOnTree] for the revert path. */
    internal fun clearOnTree(
        root: Component,
        classNameFqn: String,
    ) {
        walk(root) { component ->
            if (component is JComponent && component.javaClass.name == classNameFqn) {
                component.background = null
                component.repaint()
            }
        }
    }

    /**
     * Visible for tests — ancestor-constrained variant of [refreshOnTree]. Only mutates a
     * matching [targetFqn] when a parent in its container chain has runtime class name
     * equal to [ancestorFqn].
     */
    internal fun refreshOnTreeInsideAncestor(
        root: Component,
        targetFqn: String,
        ancestorFqn: String,
        color: Color,
    ) {
        walk(root) { component ->
            if (component is JComponent &&
                component.javaClass.name == targetFqn &&
                hasAncestorWithClassName(component, ancestorFqn)
            ) {
                component.background = color
                component.repaint()
            }
        }
    }

    /** Visible for tests — mirror of [refreshOnTreeInsideAncestor] for the revert path. */
    internal fun clearOnTreeInsideAncestor(
        root: Component,
        targetFqn: String,
        ancestorFqn: String,
    ) {
        walk(root) { component ->
            if (component is JComponent &&
                component.javaClass.name == targetFqn &&
                hasAncestorWithClassName(component, ancestorFqn)
            ) {
                component.background = null
                component.repaint()
            }
        }
    }

    /** Walks the parent chain of [component] looking for a container whose runtime class name equals [ancestorFqn]. */
    private fun hasAncestorWithClassName(
        component: Component,
        ancestorFqn: String,
    ): Boolean {
        var current: Container? = component.parent
        while (current != null) {
            if (current.javaClass.name == ancestorFqn) return true
            current = current.parent
        }
        return false
    }

    /**
     * Recursively walks [component] and its descendants, invoking [visit] per node.
     * Each visit is isolated in `runCatching` so a single flaky peer (mid-dispose,
     * ClassCast on reflective match, NPE inside repaint) doesn't abort the rest of
     * the tree. See Phase 40 review Round 3 C-1.
     */
    private fun walk(
        component: Component,
        visit: (Component) -> Unit,
    ) {
        runCatching { visit(component) }.onFailure {
            log.debug("Live refresh visit failed on ${component.javaClass.name}", it)
        }
        if (component is Container) {
            val children = runCatching { component.components }.getOrNull() ?: return
            for (child in children) {
                walk(child, visit)
            }
        }
    }

    private fun Project.isUsable(): Boolean = !isDefault && !isDisposed
}
