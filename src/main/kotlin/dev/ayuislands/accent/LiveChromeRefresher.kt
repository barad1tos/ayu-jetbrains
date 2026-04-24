package dev.ayuislands.accent

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.WindowManager
import org.jetbrains.annotations.TestOnly
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Window
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * Set of Container class names that have already produced a WARN for a
     * `.components` read failure. Subsequent failures on the same class drop to
     * DEBUG to avoid log spam, but the first occurrence per container class is
     * loud because it almost always indicates a broken custom `Container`
     * override in a third-party plugin — reportable, not just defensive. See
     * Phase 40 review-loop Round 1 MEDIUM-1 and Round 2 LOW R2-1.
     *
     * Capped at [BROKEN_CONTAINER_LOG_CAP] entries so a pathological IDE
     * session (1000+ transient Container subclasses all throwing) cannot let
     * the set grow unbounded; on overflow the latch resets and the next
     * encounter of any class re-logs at WARN.
     */
    private val brokenContainerLogged: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Upper bound on [brokenContainerLogged]. A typical IDE session sees
     * ~5-10 distinct Container subclasses; 64 leaves plenty of headroom before
     * the cap triggers.
     */
    private const val BROKEN_CONTAINER_LOG_CAP = 64

    /**
     * Test-only reset for [brokenContainerLogged]. `ChromeBaseColors` clears
     * its sibling latch via `refresh()` (LAF-driven), but there is no
     * equivalent trigger for a per-session container latch in production, so
     * tests use this hook to isolate order-dependent assertions.
     */
    @TestOnly
    internal fun resetBrokenContainerLoggedForTests() {
        brokenContainerLogged.clear()
    }

    // --- Typed entry points (Phase 40.3c Refactor 2) ---
    //
    // Sealed [ChromeTarget] collapses the prior 6-entry API (refresh/clear × 3
    // peer strategies) into two methods. Dispatch is exhaustive — adding a new
    // ChromeTarget variant is a compile-time pattern-match failure, not a silent
    // new overload. Internal helpers (refreshOnTree*, clearOnTree*) stay
    // `internal` for the tree-walk tests that build synthetic Container trees.

    /** Refreshes the live peer described by [target] with [color]. */
    fun refresh(
        target: ChromeTarget,
        color: Color,
    ) {
        when (target) {
            ChromeTarget.StatusBar ->
                forEachUsableStatusBarComponent { component ->
                    component.background = color
                    component.repaint()
                }
            is ChromeTarget.ByClassName ->
                forEachShowingWindow { refreshOnTree(it, target.fqn, color) }
            is ChromeTarget.ByClassNameInside ->
                forEachShowingWindow {
                    refreshOnTreeInsideAncestor(it, target.target, target.ancestor, color)
                }
        }
    }

    /** D-14 symmetry mirror of [refresh] — hands [target]'s peer back to LAF default. */
    fun clear(target: ChromeTarget) {
        when (target) {
            ChromeTarget.StatusBar ->
                forEachUsableStatusBarComponent { component ->
                    component.background = null
                    component.repaint()
                }
            is ChromeTarget.ByClassName ->
                forEachShowingWindow { clearOnTree(it, target.fqn) }
            is ChromeTarget.ByClassNameInside ->
                forEachShowingWindow {
                    clearOnTreeInsideAncestor(it, target.target, target.ancestor)
                }
        }
    }

    private inline fun forEachUsableStatusBarComponent(action: (JComponent) -> Unit) {
        val projectManager = safeService("ProjectManager") { ProjectManager.getInstance() } ?: return
        val windowManager = safeService("WindowManager") { WindowManager.getInstance() } ?: return
        for (project in projectManager.openProjects) {
            if (!project.isUsable()) continue
            val statusBar = windowManager.getStatusBar(project) ?: continue
            val component = statusBar.component as? JComponent ?: continue
            action(component)
        }
    }

    /**
     * Pattern B: narrow the service-lookup catch to [RuntimeException] so
     * [OutOfMemoryError] / [NoClassDefFoundError] during a shutdown race
     * still propagate — the prior `runCatching { ... }.onFailure { log.debug(...) }`
     * swallowed the full [Throwable] surface on the hot live-refresh path.
     * The `?: return` on the callsite is expected during shutdown (both
     * services can legitimately report null).
     */
    private inline fun <T : Any> safeService(
        name: String,
        crossinline lookup: () -> T?,
    ): T? =
        try {
            lookup()
        } catch (exception: RuntimeException) {
            log.debug("$name unavailable during live refresh", exception)
            null
        }

    /**
     * Iterates top-level windows, skipping ones that are not [Window.isShowing] (disposed,
     * hidden, pooled popups) and isolating per-window failures so one flaky peer doesn't
     * abort chrome apply for the rest of the desktop. See Phase 40 review Round 3 C-1, C-2.
     *
     * Uses [Window.getWindows] (broader than `WindowManager.allProjectFrames`) to catch
     * pooled dialogs and detached popups that still cache stale chrome colors; the
     * [Window.isShowing] filter keeps the walk safe by skipping disposed/hidden peers.
     *
     * Catches [RuntimeException] rather than using `runCatching` (which would also
     * swallow [Error] — [OutOfMemoryError], [StackOverflowError]) so catastrophic JVM
     * failures still propagate and don't get quietly logged at DEBUG. The Swing /
     * AWT peer surface only throws [RuntimeException] subtypes (NPE on disposed peer,
     * ClassCast on reflective match, IllegalState on detached frame), so narrowing
     * the catch here also satisfies detekt's TooGenericExceptionCaught.
     *
     * The per-window catch around `action(window)` is defense-in-depth for callers
     * that might pass an `action` lambda not wrapped in [walk]. Every current caller
     * ([refreshOnTree], [clearOnTree], and the two `*InsideAncestor` variants)
     * delegates to [walk], whose own per-component catch swallows broken peers
     * before they can reach this layer — so the per-window catch is structurally
     * unreachable today. Keep it: this is a `private inline fun` whose signature
     * accepts an arbitrary `(Window) -> Unit`, and a future non-walk caller would
     * otherwise regress the isolation guarantee.
     */
    private inline fun forEachShowingWindow(action: (Window) -> Unit) {
        val windows =
            try {
                Window.getWindows()
            } catch (exception: RuntimeException) {
                log.warn(
                    "Live refresh could not enumerate AWT windows; skipping pass",
                    exception,
                )
                return
            }
        for (window in windows) {
            if (!window.isShowing) continue
            try {
                action(window)
            } catch (exception: RuntimeException) {
                log.debug("Live refresh skipped for ${window.javaClass.simpleName}", exception)
            }
        }
    }

    /**
     * Visible for tests — traverses [root] (and every descendant Container) and mutates
     * any [JComponent] whose runtime class name equals [classNameFqn].
     */
    internal fun refreshOnTree(
        root: Component,
        classNameFqn: ClassFqn,
        color: Color,
    ) {
        walk(root) { component ->
            if (component is JComponent && component.javaClass.name == classNameFqn.value) {
                component.background = color
                component.repaint()
            }
        }
    }

    /** Visible for tests — mirror of [refreshOnTree] for the revert path. */
    internal fun clearOnTree(
        root: Component,
        classNameFqn: ClassFqn,
    ) {
        walk(root) { component ->
            if (component is JComponent && component.javaClass.name == classNameFqn.value) {
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
        targetFqn: ClassFqn,
        ancestorFqn: ClassFqn,
        color: Color,
    ) {
        walk(root) { component ->
            if (component is JComponent &&
                component.javaClass.name == targetFqn.value &&
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
        targetFqn: ClassFqn,
        ancestorFqn: ClassFqn,
    ) {
        walk(root) { component ->
            if (component is JComponent &&
                component.javaClass.name == targetFqn.value &&
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
        ancestorFqn: ClassFqn,
    ): Boolean {
        var current: Container? = component.parent
        while (current != null) {
            if (current.javaClass.name == ancestorFqn.value) return true
            current = current.parent
        }
        return false
    }

    /**
     * Iteratively walks [component] and its descendants, invoking [visit] per node.
     * Phase 40.2 M-4 — converted from the prior recursive walk to an
     * [ArrayDeque]-based BFS so a pathological deeply nested container tree
     * cannot blow the JVM thread stack. The recursive version was capped only by
     * whatever headroom the EDT happened to have and a malicious plugin could
     * emit a container chain deep enough to trigger [StackOverflowError] mid-apply.
     * Iterative traversal bounds growth to heap (orders of magnitude more slack)
     * and keeps the per-visit try/catch + broken-container logging semantics
     * exactly as before. See Phase 40 review Round 3 C-1.
     *
     * Each visit is isolated so a single flaky peer (mid-dispose, ClassCast on
     * reflective match, NPE inside repaint) doesn't abort the rest of the tree.
     * Catches [RuntimeException] rather than [Throwable] so [Error]s
     * ([OutOfMemoryError], [StackOverflowError]) still propagate instead of being
     * silently swallowed at DEBUG, and narrower than [Exception] so detekt's
     * TooGenericExceptionCaught stays happy — the Swing peer surface only throws
     * [RuntimeException] subtypes anyway.
     */
    private fun walk(
        component: Component,
        visit: (Component) -> Unit,
    ) {
        val queue: ArrayDeque<Component> = ArrayDeque()
        queue.addLast(component)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            try {
                visit(current)
            } catch (exception: RuntimeException) {
                log.debug("Live refresh visit failed on ${current.javaClass.name}", exception)
            }
            if (current !is Container) continue
            val children =
                try {
                    current.components
                } catch (exception: RuntimeException) {
                    logBrokenContainer(current, exception)
                    continue
                }
            for (child in children) {
                queue.addLast(child)
            }
        }
    }

    /**
     * WARN on first encounter per container class, DEBUG thereafter. A throwing
     * `Container.components` read is a reportable defect (likely a broken
     * custom `Container` override in a third-party plugin) — the first
     * occurrence must be loud; subsequent hits in the same session drop to
     * DEBUG so the log doesn't flood. See Phase 40 review-loop Round 1 MEDIUM-1.
     */
    private fun logBrokenContainer(
        component: Container,
        exception: RuntimeException,
    ) {
        val className = component.javaClass.name
        if (brokenContainerLogged.add(className)) {
            log.warn(
                "Live refresh could not read children of $className — " +
                    "subtree will be skipped every apply; further hits log at DEBUG",
                exception,
            )
        } else {
            log.debug("Live refresh could not read children of $className", exception)
        }
        // Round 2 LOW R2-1: cap the set so a pathological session can't leak.
        // On overflow we clear and the next encounter re-warns — acceptable
        // trade-off vs unbounded memory growth.
        if (brokenContainerLogged.size > BROKEN_CONTAINER_LOG_CAP) {
            brokenContainerLogged.clear()
        }
    }

    private fun Project.isUsable(): Boolean = !isDefault && !isDisposed
}
