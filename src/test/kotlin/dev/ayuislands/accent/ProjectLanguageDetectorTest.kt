package dev.ayuislands.accent

import com.intellij.openapi.module.Module
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
        // ProjectLanguageScanner is the new primary path; default it to emptyMap()
        // so existing SDK/module-fallback tests (which pre-date the scanner) exercise
        // the legacy path — their fixtures don't set up a ProjectFileIndex and the
        // fallback is what they're really asserting against.
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
    fun `dominant returns null without throwing when no SDK and no matching modules`() {
        val project = stubProject("/tmp/undetectable-${System.nanoTime()}")
        wireProjectRootManager(project, sdkName = null)
        wireModuleManager(project, moduleNames = emptyList())

        assertNull(ProjectLanguageDetector.dominant(project))
    }

    // SDK-based detection

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

    // Module-name fallback

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

    // Cache behavior

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
    fun `dominant propagates CancellationException from SDK lookup instead of swallowing`() {
        // ProjectLanguageDetector.dominant is reachable from AyuIslandsStartupActivity.execute's
        // coroutine body via AccentResolver.findOverride. Plain runCatching would absorb the
        // CancellationException into Result.failure, log a "SDK lookup failed" WARN, return
        // null, and the cancelled coroutine would keep walking the resolver chain. The helper
        // runCatchingPreservingCancellation must rethrow so structured concurrency holds.
        val project = stubProject("/tmp/cancel-sdk-${System.nanoTime()}")
        every { ProjectRootManager.getInstance(project) } throws
            kotlin.coroutines.cancellation.CancellationException("startup cancelled during SDK lookup")

        val thrown =
            kotlin.test.assertFailsWith<kotlin.coroutines.cancellation.CancellationException> {
                ProjectLanguageDetector.dominant(project)
            }
        assertEquals("startup cancelled during SDK lookup", thrown.message)
    }

    @Test
    fun `dominant propagates CancellationException from module lookup instead of swallowing`() {
        // Parallel contract on the module-list branch — SDK returns no match, module lookup
        // throws CancellationException. Without the rethrow, the detector would return null
        // and the cancelled coroutine would continue.
        val project = stubProject("/tmp/cancel-modules-${System.nanoTime()}")
        wireProjectRootManager(project, sdkName = null)
        every { ModuleManager.getInstance(project) } throws
            kotlin.coroutines.cancellation.CancellationException("startup cancelled during module lookup")

        val thrown =
            kotlin.test.assertFailsWith<kotlin.coroutines.cancellation.CancellationException> {
                ProjectLanguageDetector.dominant(project)
            }
        assertEquals("startup cancelled during module lookup", thrown.message)
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

    // ── Content-scan primary path (new in v2.6.x) ────────────────────────────────────

    @Test
    fun `content scan dominance wins over SDK fallback`() {
        // Scanner says Kotlin is 80% of the weight — even though the SDK is Python,
        // the scan is authoritative. Guards against a regression where someone wires
        // legacy detection before the scan result.
        val project = stubProject("/tmp/scan-wins-${System.nanoTime()}")
        every { ProjectLanguageScanner.scan(project) } returns
            mapOf("kotlin" to 800L, "java" to 200L)
        wireProjectRootManager(project, sdkName = "Python SDK")
        wireModuleManager(project, moduleNames = emptyList())

        assertEquals("kotlin", ProjectLanguageDetector.dominant(project))
        // Legacy detection must NOT have been consulted — scan was decisive.
        verify(exactly = 0) { ProjectRootManager.getInstance(project) }
    }

    @Test
    fun `content scan polyglot uses SDK tiebreak when hint has code-weight foothold`() {
        // 50/50 Kotlin/Java: scan alone produces no winner. The SDK tiebreak kicks
        // in — "KotlinSdkType" resolves to "kotlin", which has a 50% share of the
        // code weights (well above the 20% TIE_BREAK_MIN_SHARE floor), so the
        // detector returns "kotlin". Without this tiebreak, users upgrading from
        // v2.5 would silently lose their Kotlin override on JVM-mixed codebases.
        val project = stubProject("/tmp/scan-polyglot-sdk-tie-${System.nanoTime()}")
        every { ProjectLanguageScanner.scan(project) } returns
            mapOf("kotlin" to 500L, "java" to 500L)
        wireProjectRootManager(project, sdkName = "KotlinSdkType")
        wireModuleManager(project, moduleNames = emptyList())

        assertEquals("kotlin", ProjectLanguageDetector.dominant(project))
    }

    @Test
    fun `content scan polyglot rejects SDK tiebreak when hint lacks foothold`() {
        // Scanner proves Kotlin is only 10% — below the 20% tiebreak floor. The
        // SDK says Kotlin, but we refuse to override the scan's "no winner"
        // verdict because Kotlin isn't meaningfully present.
        val project = stubProject("/tmp/scan-polyglot-weak-hint-${System.nanoTime()}")
        every { ProjectLanguageScanner.scan(project) } returns
            mapOf("kotlin" to 100L, "java" to 900L)
        wireProjectRootManager(project, sdkName = "KotlinSdkType")
        wireModuleManager(project, moduleNames = emptyList())

        // Kotlin is 10% — below TIE_BREAK_MIN_SHARE (20%). But Java is 90% → scan
        // actually DOES have a winner via the primary threshold. So this test
        // asserts the primary path first, proving the SDK hint is irrelevant.
        assertEquals("java", ProjectLanguageDetector.dominant(project))
    }

    @Test
    fun `content scan polyglot ignores SDK hint when it is not in the scan weights`() {
        // Scanner found Kotlin + Python in a 50/50 split. SDK says "Rust", which
        // isn't represented in the scan at all — detector must NOT return "rust"
        // just because the SDK said so.
        val project = stubProject("/tmp/scan-polyglot-hint-absent-${System.nanoTime()}")
        every { ProjectLanguageScanner.scan(project) } returns
            mapOf("kotlin" to 500L, "python" to 500L)
        wireProjectRootManager(project, sdkName = "RustSdkType")
        wireModuleManager(project, moduleNames = emptyList())

        assertNull(ProjectLanguageDetector.dominant(project))
    }

    @Test
    fun `content scan polyglot with no SDK hint returns null`() {
        val project = stubProject("/tmp/scan-polyglot-no-hint-${System.nanoTime()}")
        every { ProjectLanguageScanner.scan(project) } returns
            mapOf("kotlin" to 500L, "java" to 500L)
        wireProjectRootManager(project, sdkName = null)
        wireModuleManager(project, moduleNames = emptyList())

        assertNull(ProjectLanguageDetector.dominant(project))
    }

    @Test
    fun `content scan null result is not cached and retries next call`() {
        // Scanner returns null when it can't give an authoritative answer (dumb mode,
        // disposal, ReadAction failure). Detector must NOT cache null because the
        // next call — potentially in smart mode — can succeed.
        val project = stubProject("/tmp/scan-transient-${System.nanoTime()}")
        every { ProjectLanguageScanner.scan(project) } returnsMany
            listOf(null, mapOf("kotlin" to 900L, "java" to 100L))
        wireProjectRootManager(project, sdkName = null)
        wireModuleManager(project, moduleNames = emptyList())

        // First call: scanner returns null → detector returns null WITHOUT caching.
        assertNull(ProjectLanguageDetector.dominant(project))
        // Second call: scanner now returns weights → kotlin wins, cached.
        assertEquals("kotlin", ProjectLanguageDetector.dominant(project))
        // Third call: cache hit, scanner NOT consulted again.
        assertEquals("kotlin", ProjectLanguageDetector.dominant(project))
        verify(exactly = 2) { ProjectLanguageScanner.scan(project) }
    }

    @Test
    fun `content scan empty map falls through to legacy SDK detection`() {
        // Scanner ran clean but found no source files (brand-new project / docs-only).
        // Legacy SDK/module path must still engage so the user gets their override
        // even before the first code file lands.
        val project = stubProject("/tmp/scan-empty-${System.nanoTime()}")
        every { ProjectLanguageScanner.scan(project) } returns emptyMap()
        wireProjectRootManager(project, sdkName = "RustSdkType")
        wireModuleManager(project, moduleNames = emptyList())

        assertEquals("rust", ProjectLanguageDetector.dominant(project))
    }

    @Test
    fun `content scan markup-only project surfaces markup as dominant`() {
        // K8s-manifests-only repo: pickDominantFromAllWeights falls back to the full
        // map when code weights are empty, so the user's "yaml → blue" mapping lands.
        val project = stubProject("/tmp/scan-yaml-${System.nanoTime()}")
        every { ProjectLanguageScanner.scan(project) } returns
            mapOf("yaml" to 1_000L, "json" to 200L)
        wireProjectRootManager(project, sdkName = null)
        wireModuleManager(project, moduleNames = emptyList())

        assertEquals("yaml", ProjectLanguageDetector.dominant(project))
    }

    @Test
    fun `content scan code plus xml resources picks code winner`() {
        // Android-style: Kotlin source + lots of XML resources. Two-tier filter
        // drops XML from the code base, Kotlin wins 100% of the code tier.
        val project = stubProject("/tmp/scan-android-${System.nanoTime()}")
        every { ProjectLanguageScanner.scan(project) } returns
            mapOf("kotlin" to 400L, "xml" to 800L, "json" to 100L)
        wireProjectRootManager(project, sdkName = null)
        wireModuleManager(project, moduleNames = emptyList())

        assertEquals("kotlin", ProjectLanguageDetector.dominant(project))
    }

    @Test
    fun `content scan result is cached after success`() {
        val project = stubProject("/tmp/scan-cache-${System.nanoTime()}")
        every { ProjectLanguageScanner.scan(project) } returns
            mapOf("python" to 900L, "yaml" to 100L)
        wireProjectRootManager(project, sdkName = null)
        wireModuleManager(project, moduleNames = emptyList())

        assertEquals("python", ProjectLanguageDetector.dominant(project))
        assertEquals("python", ProjectLanguageDetector.dominant(project))
        assertEquals("python", ProjectLanguageDetector.dominant(project))

        // Scanner called exactly once despite three dominant() calls.
        verify(exactly = 1) { ProjectLanguageScanner.scan(project) }
    }

    @Test
    fun `content scan polyglot null is cached definitively`() {
        // Contrast with scan-returning-null (transient). A scan that RAN but produced
        // no winner is a definitive answer — cache it so we don't re-scan a 10k-file
        // repo on every focus swap just to re-confirm "yep, still polyglot".
        val project = stubProject("/tmp/scan-polyglot-cache-${System.nanoTime()}")
        every { ProjectLanguageScanner.scan(project) } returns
            mapOf("kotlin" to 500L, "java" to 500L)
        wireProjectRootManager(project, sdkName = null)
        wireModuleManager(project, moduleNames = emptyList())

        assertNull(ProjectLanguageDetector.dominant(project))
        assertNull(ProjectLanguageDetector.dominant(project))

        verify(exactly = 1) { ProjectLanguageScanner.scan(project) }
    }

    @Test
    fun `invalidate after content scan triggers a fresh scan`() {
        // ModuleRootListener fires invalidate on content-root changes (gradle sync,
        // module add/remove). Next dominance lookup must hit the scanner again so a
        // newly-added Python microservice doesn't keep "kotlin" cached.
        val project = stubProject("/tmp/scan-invalidate-${System.nanoTime()}")
        every { ProjectLanguageScanner.scan(project) } returnsMany
            listOf(
                mapOf("kotlin" to 900L, "java" to 100L),
                mapOf("python" to 900L, "kotlin" to 100L),
            )
        wireProjectRootManager(project, sdkName = null)
        wireModuleManager(project, moduleNames = emptyList())

        assertEquals("kotlin", ProjectLanguageDetector.dominant(project))
        ProjectLanguageDetector.invalidate(project)
        assertEquals("python", ProjectLanguageDetector.dominant(project))

        verify(exactly = 2) { ProjectLanguageScanner.scan(project) }
    }

    // Test helpers

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
