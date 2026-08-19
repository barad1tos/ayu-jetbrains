package dev.ayuislands.ui

import com.intellij.openapi.project.Project

/** Publishes project-scoped refresh events so subscribed managers reapply their overrides. */
object ComponentTreeRefresher {
    /** Publishes after the platform or Ayu refreshes visible UI state. */
    fun notifyOnly(project: Project) {
        if (project.isDisposed) return
        project.messageBus
            .syncPublisher(ComponentTreeRefreshedTopic.TOPIC)
            .afterRefresh(project)
    }
}
