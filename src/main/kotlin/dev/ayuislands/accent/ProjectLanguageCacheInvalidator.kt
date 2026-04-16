package dev.ayuislands.accent

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener

/**
 * Application-level listener that drops a closed project's language-detection
 * cache entry so the entry can't leak into a future IDE session if the same
 * canonical path is re-used (project folder renamed, reopened, or cloned over).
 *
 * Runs at application scope via the `applicationListeners` entry in plugin.xml
 * — per-project message-bus connections dispose *before* `projectClosed` fires,
 * so a project-scoped subscription would miss the event for the project being
 * closed.
 */
class ProjectLanguageCacheInvalidator : ProjectManagerListener {
    override fun projectClosed(project: Project) {
        ProjectLanguageDetector.invalidate(project)
    }
}
