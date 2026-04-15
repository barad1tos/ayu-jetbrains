package dev.ayuislands.ui

import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

/**
 * Listener fired after a forced component-tree LAF refresh has walked a project's
 * frame. Subscribe in `init {}` on any project-scoped service that installs per-component
 * overrides (`putClientProperty`, `setPreferredSize`, `setBorder`, and similar) that
 * `JComponent.updateUI()` resets — e.g. scrollbar-hide tricks, caret-row overlays.
 *
 * Each subscriber receives events published on its own `project.messageBus` only; cross-
 * project contamination is blocked by [Topic.BroadcastDirection.NONE].
 */
fun interface ComponentTreeRefreshedListener {
    fun afterRefresh(project: Project)
}

object ComponentTreeRefreshedTopic {
    @JvmField
    val TOPIC: Topic<ComponentTreeRefreshedListener> =
        Topic.create(
            "Ayu Islands component tree refreshed",
            ComponentTreeRefreshedListener::class.java,
            Topic.BroadcastDirection.NONE,
        )
}
