package dev.ayuislands.accent

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkTypeId
import com.intellij.openapi.roots.ProjectRootManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers the application-level listener that drops a project's language-detection
 * cache entry on `projectClosed`. Without this, a project reopened at the same
 * canonical path after rename / clone-over would serve whatever answer the prior
 * project's content resolved to — potentially pointing at a completely different
 * codebase.
 */
class ProjectLanguageCacheInvalidatorTest {
    @BeforeTest
    fun setUp() {
        mockkStatic(ProjectRootManager::class)
        mockkStatic(ModuleManager::class)
        mockkObject(ProjectLanguageScanner)
        every { ProjectLanguageScanner.scan(any()) } returns emptyMap()
        ProjectLanguageDetector.clear()
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
        ProjectLanguageDetector.clear()
    }

    @Test
    fun `projectClosed invalidates the cache entry for that project`() {
        val project = stubProject("/tmp/invalidator-${System.nanoTime()}")
        wireProjectRootManager(project, sdkName = "KotlinSdkType")
        wireModuleManager(project, moduleNames = emptyList())

        // Prime the cache via the legacy path (scanner mocked to emptyMap).
        assertEquals("kotlin", ProjectLanguageDetector.dominant(project))

        // Fire the listener — simulates the app-level ProjectManagerListener
        // dispatch when the IDE closes the project.
        ProjectLanguageCacheInvalidator().projectClosed(project)

        // Next call must re-run detection instead of serving the stale cache.
        // Re-wire with a different SDK to prove the cache was cleared — if the
        // entry leaked, we'd still see "kotlin".
        wireProjectRootManager(project, sdkName = "Python SDK")
        assertEquals("python", ProjectLanguageDetector.dominant(project))
    }

    @Test
    fun `projectClosed on unrelated project leaves other entries intact`() {
        val projectA = stubProject("/tmp/keep-${System.nanoTime()}")
        val projectB = stubProject("/tmp/close-${System.nanoTime()}")
        wireProjectRootManager(projectA, sdkName = "KotlinSdkType")
        wireProjectRootManager(projectB, sdkName = "RustSdkType")
        wireModuleManager(projectA, moduleNames = emptyList())
        wireModuleManager(projectB, moduleNames = emptyList())

        ProjectLanguageDetector.dominant(projectA) // primes cache for A
        ProjectLanguageDetector.dominant(projectB) // primes cache for B

        ProjectLanguageCacheInvalidator().projectClosed(projectB)

        // A's cache entry must survive — only B was invalidated. Re-wire both to
        // prove which one went through detection again: if A's cache leaked,
        // swapping its SDK wouldn't change the answer.
        wireProjectRootManager(projectA, sdkName = "Python SDK")
        wireProjectRootManager(projectB, sdkName = "Python SDK")

        assertEquals("kotlin", ProjectLanguageDetector.dominant(projectA)) // cached
        assertEquals("python", ProjectLanguageDetector.dominant(projectB)) // re-detected
    }

    @Test
    fun `projectClosed on uncached project is a no-op`() {
        val project = stubProject("/tmp/never-cached-${System.nanoTime()}")
        // Never called dominant() → no cache entry → listener must not throw.
        ProjectLanguageCacheInvalidator().projectClosed(project)

        // Subsequent detection runs normally.
        wireProjectRootManager(project, sdkName = null)
        wireModuleManager(project, moduleNames = emptyList())
        assertNull(ProjectLanguageDetector.dominant(project))
    }

    private fun stubProject(basePath: String): Project {
        val project = mockk<Project>()
        every { project.basePath } returns basePath
        every { project.isDefault } returns false
        every { project.isDisposed } returns false
        return project
    }

    private fun wireProjectRootManager(
        project: Project,
        sdkName: String?,
    ) {
        val prm = mockk<ProjectRootManager>()
        val sdk =
            if (sdkName != null) {
                val sdkMock = mockk<Sdk>()
                val sdkType = mockk<SdkTypeId>()
                every { sdkType.name } returns sdkName
                every { sdkMock.sdkType } returns sdkType
                sdkMock
            } else {
                null
            }
        every { prm.projectSdk } returns sdk
        every { ProjectRootManager.getInstance(project) } returns prm
    }

    private fun wireModuleManager(
        project: Project,
        moduleNames: List<String>,
    ) {
        val mm = mockk<ModuleManager>()
        val modules =
            moduleNames
                .map { name ->
                    val module = mockk<com.intellij.openapi.module.Module>()
                    every { module.name } returns name
                    module
                }.toTypedArray()
        every { mm.modules } returns modules
        every { ModuleManager.getInstance(project) } returns mm
    }
}
