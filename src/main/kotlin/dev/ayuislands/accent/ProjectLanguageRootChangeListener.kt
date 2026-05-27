package dev.ayuislands.accent

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener

/** Project service that invalidates language detection when roots change. */
@Service(Service.Level.PROJECT)
class ProjectLanguageRootChangeListener(
    private val project: Project,
) : Disposable {
    init {
        project.messageBus.connect(this).subscribe(
            ModuleRootListener.TOPIC,
            object : ModuleRootListener {
                override fun rootsChanged(event: ModuleRootEvent) {
                    ProjectLanguageDetector.invalidate(project)
                }
            },
        )
    }

    override fun dispose() = Unit

    companion object {
        fun getInstance(project: Project): ProjectLanguageRootChangeListener =
            project.getService(ProjectLanguageRootChangeListener::class.java)
    }
}
