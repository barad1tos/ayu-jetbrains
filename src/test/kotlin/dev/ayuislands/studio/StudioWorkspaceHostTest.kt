package dev.ayuislands.studio

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import dev.ayuislands.commitpanel.CommitPanelAutoFitManager
import dev.ayuislands.editor.EditorScrollbarManager
import dev.ayuislands.gitpanel.GitPanelAutoFitManager
import dev.ayuislands.projectview.ProjectViewScrollbarManager
import dev.ayuislands.projectview.RootPathLease
import dev.ayuislands.studio.session.IdeWorkspaceHost
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class StudioWorkspaceHostTest {
    @AfterTest
    fun restoreApplication() = unmockkAll()

    @Test
    fun `capture obtains the pure application lease before the first project callback`() {
        mockkStatic(ApplicationManager::class)
        val lease = RootPathLease()
        val application =
            mockk<Application> {
                every { getServiceIfCreated(RootPathLease::class.java) } returns null
                every { getService(RootPathLease::class.java) } returns lease
            }
        every { ApplicationManager.getApplication() } returns application

        assertSame(lease, IdeWorkspaceHost().rootLease())

        verify(exactly = 1) { application.getService(RootPathLease::class.java) }
    }

    @Test
    fun `capture never initializes missing project services with queued native effects`() {
        val project =
            mockk<Project>(relaxed = true) {
                every { getServiceIfCreated(EditorScrollbarManager::class.java) } returns null
                every { getServiceIfCreated(ProjectViewScrollbarManager::class.java) } returns null
                every { getServiceIfCreated(CommitPanelAutoFitManager::class.java) } returns null
                every { getServiceIfCreated(GitPanelAutoFitManager::class.java) } returns null
            }
        val host = IdeWorkspaceHost()

        assertNull(host.editor(project))
        assertNull(host.projectView(project))
        assertNull(host.commit(project))
        assertNull(host.git(project))

        verify(exactly = 0) { project.getService(EditorScrollbarManager::class.java) }
        verify(exactly = 0) { project.getService(ProjectViewScrollbarManager::class.java) }
        verify(exactly = 0) { project.getService(CommitPanelAutoFitManager::class.java) }
        verify(exactly = 0) { project.getService(GitPanelAutoFitManager::class.java) }
    }
}
