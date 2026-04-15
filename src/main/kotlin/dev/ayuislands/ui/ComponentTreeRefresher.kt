package dev.ayuislands.ui

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.IJSwingUtilities
import java.awt.Component

/**
 * Central pipeline for "the component tree was refreshed → subscribed managers, please
 * reapply your overrides".
 *
 * `IJSwingUtilities.updateComponentTreeUI` calls `updateUI()` on every descendant, which
 * resets component-level customizations our managers install (e.g.
 * `EditorScrollbarManager` zeroes `JScrollBar.preferredSize` to hide scrollbars —
 * `updateUI()` restores the default size). After the walk we publish a project-scoped
 * event on [ComponentTreeRefreshedTopic] so subscribers reapply.
 *
 * Callers that need stale-color refresh (e.g. startup activity, focus-swap) use
 * [walkAndNotify]. Callers triggered by the platform's own LAF refresh (e.g.
 * `LafManagerListener.lookAndFeelChanged`) use [notifyOnly] — the tree walk is redundant
 * there, only the subscribe-side reapply is needed.
 */
object ComponentTreeRefresher {
    private val LOG = logger<ComponentTreeRefresher>()

    /** Walks the component subtree at [root], then fires [notifyOnly]. */
    fun walkAndNotify(
        project: Project,
        root: Component,
    ) {
        if (project.isDisposed) return
        try {
            IJSwingUtilities.updateComponentTreeUI(root)
        } catch (exception: RuntimeException) {
            // Pass the exception as the second argument so the stacktrace is preserved in
            // idea.log — post-mortem on a failed Swing refresh is impossible without it.
            // Include the root component class so failures on a specific tree node are traceable.
            LOG.warn(
                "Component tree refresh failed for ${project.name} (root=${root.javaClass.simpleName})",
                exception,
            )
        }
        notifyOnly(project)
    }

    /**
     * Publishes a refresh event without walking the tree — use when the platform or
     * another caller has already refreshed the component tree (e.g. native LAF change)
     * and we just need subscribers to reapply their overrides.
     */
    fun notifyOnly(project: Project) {
        if (project.isDisposed) return
        project.messageBus
            .syncPublisher(ComponentTreeRefreshedTopic.TOPIC)
            .afterRefresh(project)
    }
}
