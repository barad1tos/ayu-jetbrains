package dev.ayuislands.accent

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkTypeId
import com.intellij.openapi.roots.ProjectRootManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Exercises the real heuristic of [ProjectLanguageDetector] — [AccentResolverTest]
 * mocks the object out, so without this suite the SDK/module detection paths and
 * cache behavior have zero real coverage.
 *
 * The first test (`dominant returns null without throwing ...`) started as the RED
 * case that exposed a ConcurrentHashMap null-value NPE in the cache delegate; the
 * rest lock in the detection rules.
 */
class ProjectLanguageDetectorTest {
    @BeforeTest
    fun setUp() {
        mockkStatic(ProjectRootManager::class)
        mockkStatic(ModuleManager::class)
        ProjectLanguageDetector.clear()
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
        ProjectLanguageDetector.clear()
    }

    @Test
    fun `dominant returns null without throwing when no SDK and no matching modules`() {
        val project = stubProject("/tmp/undetectable-${System.nanoTime()}")
        wireProjectRootManager(project, sdkName = null)
        wireModuleManager(project, moduleNames = emptyList())

        assertNull(ProjectLanguageDetector.dominant(project))
    }

    // ---- SDK-based detection ----

    @Test
    fun `dominant detects kotlin from SDK type containing kotlin`() {
        assertSdkDetects("KotlinSdkType", "kotlin")
    }

    @Test
    fun `dominant detects python from SDK type containing python`() {
        assertSdkDetects("Python SDK", "python")
    }

    @Test
    fun `dominant detects javascript from SDK type containing node`() {
        assertSdkDetects("Node.js", "javascript")
    }

    @Test
    fun `dominant detects typescript from SDK type`() {
        assertSdkDetects("typescript-sdk", "typescript")
    }

    @Test
    fun `dominant detects rust from SDK type`() {
        assertSdkDetects("RustSdkType", "rust")
    }

    @Test
    fun `dominant detects go from SDK type containing go but not google`() {
        assertSdkDetects("GoSdkType", "go")
    }

    @Test
    fun `dominant does NOT detect go when SDK type contains google`() {
        // Google Cloud SDK et al. must not match Go's "go" substring rule.
        val project = stubProject("/tmp/google-proj-${System.nanoTime()}")
        wireProjectRootManager(project, sdkName = "GoogleCloudSDK")
        wireModuleManager(project, moduleNames = emptyList())
        assertNull(ProjectLanguageDetector.dominant(project))
    }

    @Test
    fun `dominant detects ruby from SDK type`() {
        assertSdkDetects("Ruby SDK", "ruby")
    }

    @Test
    fun `dominant detects php from SDK type`() {
        assertSdkDetects("PHP SDK", "php")
    }

    @Test
    fun `dominant detects dart from SDK type`() {
        assertSdkDetects("Dart SDK", "dart")
    }

    @Test
    fun `dominant detects scala from SDK type`() {
        assertSdkDetects("Scala SDK", "scala")
    }

    @Test
    fun `dominant detects java from SDK name containing JavaSDK`() {
        assertSdkDetects("JavaSDK", "java")
    }

    @Test
    fun `dominant detects java from SDK name containing JDK`() {
        assertSdkDetects("OpenJDK", "java")
    }

    // ---- Module-name fallback ----

    @Test
    fun `dominant falls back to module name when SDK is absent - kotlin`() {
        assertModuleDetects(moduleNames = listOf("my-kotlin-lib"), expected = "kotlin")
    }

    @Test
    fun `dominant falls back to module name - flutter maps to dart`() {
        assertModuleDetects(moduleNames = listOf("flutter_app"), expected = "dart")
    }

    @Test
    fun `dominant falls back to module name - android maps to kotlin`() {
        assertModuleDetects(moduleNames = listOf("android-core"), expected = "kotlin")
    }

    @Test
    fun `dominant falls back to module name - python`() {
        assertModuleDetects(moduleNames = listOf("backend-python-service"), expected = "python")
    }

    @Test
    fun `dominant returns null when module names contain no language keywords`() {
        val project = stubProject("/tmp/unmatched-${System.nanoTime()}")
        wireProjectRootManager(project, sdkName = null)
        wireModuleManager(project, moduleNames = listOf("utils", "lib", "main"))
        assertNull(ProjectLanguageDetector.dominant(project))
    }

    // ---- Cache behavior ----

    @Test
    fun `dominant caches successful detection - detectInternal called once`() {
        val project = stubProject("/tmp/cache-hit-${System.nanoTime()}")
        wireProjectRootManager(project, sdkName = "KotlinSdkType")
        wireModuleManager(project, moduleNames = emptyList())

        assertEquals("kotlin", ProjectLanguageDetector.dominant(project))
        assertEquals("kotlin", ProjectLanguageDetector.dominant(project))

        // Static getInstance should have been called only on the first detection.
        verify(exactly = 1) { ProjectRootManager.getInstance(project) }
    }

    @Test
    fun `dominant caches negative detection - detectInternal called once for null`() {
        val project = stubProject("/tmp/cache-miss-${System.nanoTime()}")
        wireProjectRootManager(project, sdkName = null)
        wireModuleManager(project, moduleNames = emptyList())

        assertNull(ProjectLanguageDetector.dominant(project))
        assertNull(ProjectLanguageDetector.dominant(project))

        // Critical: a null detection must still be cached so we don't keep hammering
        // ProjectRootManager/ModuleManager. Verifies the sentinel approach.
        verify(exactly = 1) { ProjectRootManager.getInstance(project) }
    }

    @Test
    fun `invalidate clears cache entry - detectInternal called twice after invalidate`() {
        val project = stubProject("/tmp/invalidate-${System.nanoTime()}")
        wireProjectRootManager(project, sdkName = "Python SDK")
        wireModuleManager(project, moduleNames = emptyList())

        assertEquals("python", ProjectLanguageDetector.dominant(project))
        ProjectLanguageDetector.invalidate(project)
        assertEquals("python", ProjectLanguageDetector.dominant(project))

        verify(exactly = 2) { ProjectRootManager.getInstance(project) }
    }

    @Test
    fun `transient SDK lookup failure does not poison the cache`() {
        // Regression guard: an earlier implementation silently swallowed the exception and stored
        // a NULL_SENTINEL, so the next call served the poisoned entry and the user's language
        // override was permanently broken until the next invalidate/restart.
        val project = stubProject("/tmp/transient-sdk-${System.nanoTime()}")
        every { ProjectRootManager.getInstance(project) } throws IllegalStateException("SDK list mutated")

        // First call: underlying API threw → detector returns null WITHOUT caching.
        assertNull(ProjectLanguageDetector.dominant(project))

        // Second call: same project, now the API is healthy — detector must retry, not serve
        // a cached sentinel.
        wireProjectRootManager(project, sdkName = "KotlinSdkType")
        wireModuleManager(project, moduleNames = emptyList())
        assertEquals("kotlin", ProjectLanguageDetector.dominant(project))
    }

    @Test
    fun `transient module lookup failure does not poison the cache`() {
        val project = stubProject("/tmp/transient-modules-${System.nanoTime()}")
        wireProjectRootManager(project, sdkName = null)
        every { ModuleManager.getInstance(project) } throws IllegalStateException("ModuleManager disposed")

        assertNull(ProjectLanguageDetector.dominant(project))

        // Healthy retry: no cached poison.
        wireModuleManager(project, moduleNames = listOf("my-kotlin-lib"))
        assertEquals("kotlin", ProjectLanguageDetector.dominant(project))
    }

    @Test
    fun `clear empties all entries`() {
        val a = stubProject("/tmp/a-${System.nanoTime()}")
        val b = stubProject("/tmp/b-${System.nanoTime()}")
        wireProjectRootManager(a, sdkName = "KotlinSdkType")
        wireProjectRootManager(b, sdkName = "Python SDK")
        wireModuleManager(a, moduleNames = emptyList())
        wireModuleManager(b, moduleNames = emptyList())

        ProjectLanguageDetector.dominant(a)
        ProjectLanguageDetector.dominant(b)
        ProjectLanguageDetector.clear()

        ProjectLanguageDetector.dominant(a)
        ProjectLanguageDetector.dominant(b)

        verify(exactly = 2) { ProjectRootManager.getInstance(a) }
        verify(exactly = 2) { ProjectRootManager.getInstance(b) }
    }

    // ---- Helpers ----

    private fun assertSdkDetects(
        sdkName: String,
        expected: String,
    ) {
        val project = stubProject("/tmp/sdk-$sdkName-${System.nanoTime()}")
        wireProjectRootManager(project, sdkName = sdkName)
        wireModuleManager(project, moduleNames = emptyList())
        assertEquals(expected, ProjectLanguageDetector.dominant(project))
    }

    private fun assertModuleDetects(
        moduleNames: List<String>,
        expected: String,
    ) {
        val project = stubProject("/tmp/module-${moduleNames.first()}-${System.nanoTime()}")
        wireProjectRootManager(project, sdkName = null)
        wireModuleManager(project, moduleNames = moduleNames)
        assertEquals(expected, ProjectLanguageDetector.dominant(project))
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
                    val module = mockk<Module>()
                    every { module.name } returns name
                    module
                }.toTypedArray()
        every { mm.modules } returns modules
        every { ModuleManager.getInstance(project) } returns mm
    }
}
