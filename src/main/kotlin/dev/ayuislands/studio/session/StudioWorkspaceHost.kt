package dev.ayuislands.studio.session

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.RegistryValue
import dev.ayuislands.commitpanel.CommitPanelAutoFitManager
import dev.ayuislands.editor.EditorScrollbarManager
import dev.ayuislands.gitpanel.GitPanelAutoFitManager
import dev.ayuislands.projectview.ProjectViewScrollbarManager
import dev.ayuislands.projectview.RootPathLease

/** Existing project owners and the pure application lease; capture never queues native application work. */
internal interface StudioWorkspaceHost {
    fun editor(project: Project): EditorScrollbarManager?

    fun projectView(project: Project): ProjectViewScrollbarManager?

    fun commit(project: Project): CommitPanelAutoFitManager?

    fun git(project: Project): GitPanelAutoFitManager?

    fun rootLease(): RootPathLease?

    fun rootRegistry(): RegistryValue

    fun properties(): PropertiesComponent?
}

internal class IdeWorkspaceHost : StudioWorkspaceHost {
    override fun editor(project: Project): EditorScrollbarManager? =
        project.getServiceIfCreated(EditorScrollbarManager::class.java)

    override fun projectView(project: Project): ProjectViewScrollbarManager? =
        project.getServiceIfCreated(ProjectViewScrollbarManager::class.java)

    override fun commit(project: Project): CommitPanelAutoFitManager? =
        project.getServiceIfCreated(CommitPanelAutoFitManager::class.java)

    override fun git(project: Project): GitPanelAutoFitManager? =
        project.getServiceIfCreated(GitPanelAutoFitManager::class.java)

    override fun rootLease(): RootPathLease = RootPathLease.getInstance()

    override fun rootRegistry(): RegistryValue = ProjectViewScrollbarManager.showUrlRegistryValue()

    override fun properties(): PropertiesComponent? =
        ApplicationManager.getApplication()?.getServiceIfCreated(PropertiesComponent::class.java)
}
