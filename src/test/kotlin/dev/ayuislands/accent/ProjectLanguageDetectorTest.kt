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
import kotlin.test.assertIs
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

        // Critical: a definitive no-match verdict must still be cached so we don't
        // keep hammering ProjectRootManager/ModuleManager on every lookup.
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
        // a definitive null cache entry, so the next call served the poisoned verdict and the user's language
        // override was permanently broken until the next invalidate/restart.
        val project = stubProject("/tmp/transient-sdk-${System.nanoTime()}")
        every { ProjectRootManager.getInstance(project) } throws IllegalStateException("SDK list mutated")

        // First call: underlying API threw → detector returns null WITHOUT caching.
        assertNull(ProjectLanguageDetector.dominant(project))

        // Second call: same project, now the API is healthy — detector must retry, not serve
        // a cached unavailable result.
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
    fun `verdict keeps NoWinner diagnostic weights while legacy proportions stay null`() {
        // Task 1 exposes the typed NoWinner verdict for future diagnostics, but
        // the pre-existing proportions() API must keep the current Settings UI
        // behavior until that diagnostics surface reads verdict() directly.
        val project = stubProject("/tmp/verdict-polyglot-${System.nanoTime()}")
        val weights = mapOf("typescript" to 500L, "javascript" to 500L)
        every { ProjectLanguageScanner.scan(project) } returns weights
        wireProjectRootManager(project, sdkName = null)
        wireModuleManager(project, moduleNames = emptyList())

        assertNull(ProjectLanguageDetector.dominant(project))

        val verdict = ProjectLanguageDetector.verdict(project)
        assertIs<ProjectLanguageVerdict.NoWinner>(verdict)
        assertEquals(weights, verdict.weights)
        assertNull(ProjectLanguageDetector.proportions(project))
    }

    @Test
    fun `verdict returns Detected with weights for scan winner`() {
        val project = stubProject("/tmp/verdict-detected-${System.nanoTime()}")
        val weights = mapOf("kotlin" to 800L, "java" to 200L)
        every { ProjectLanguageScanner.scan(project) } returns weights
        wireProjectRootManager(project, sdkName = null)
        wireModuleManager(project, moduleNames = emptyList())

        assertEquals("kotlin", ProjectLanguageDetector.dominant(project))

        val verdict = ProjectLanguageDetector.verdict(project)
        assertIs<ProjectLanguageVerdict.Detected>(verdict)
        assertEquals("kotlin", verdict.languageId)
        assertEquals(weights, verdict.weights)
    }

    @Test
    fun `verdict returns Cold without triggering scan`() {
        val project = stubProject("/tmp/verdict-cold-${System.nanoTime()}")
        wireProjectRootManager(project, sdkName = null)
        wireModuleManager(project, moduleNames = emptyList())

        assertEquals(ProjectLanguageVerdict.Cold, ProjectLanguageDetector.verdict(project))
        verify(exactly = 0) { ProjectLanguageScanner.scan(any()) }
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

    // ── proportions() verdict-cache semantics ────────────────────────────────────────

    @Test
    fun `proportions returns null for a cold cache without calling the scanner`() {
        // Cold cache: nothing warmed by dominant(). proportions() is a strictly
        // read-only projection; it MUST NOT call ProjectLanguageScanner.scan()
        // on a miss — the caller renders the polyglot copy on null.
        val project = stubProject("/tmp/proportions-cold-${System.nanoTime()}")
        wireProjectRootManager(project, sdkName = null)
        wireModuleManager(project, moduleNames = emptyList())

        assertNull(ProjectLanguageDetector.proportions(project))
        verify(exactly = 0) { ProjectLanguageScanner.scan(any()) }
    }

    @Test
    fun `proportions returns the warm weights map after dominant populates the cache`() {
        // dominant() runs a full scan and caches a Detected verdict carrying the
        // raw weights. A subsequent proportions() call serves that same map
        // without triggering a second scan.
        val project = stubProject("/tmp/proportions-warm-${System.nanoTime()}")
        val scanWeights = mapOf("kotlin" to 900L, "java" to 100L)
        every { ProjectLanguageScanner.scan(project) } returns scanWeights
        wireProjectRootManager(project, sdkName = null)
        wireModuleManager(project, moduleNames = emptyList())

        assertEquals("kotlin", ProjectLanguageDetector.dominant(project))
        assertEquals(scanWeights, ProjectLanguageDetector.proportions(project))
        // Scanner was called exactly once — by dominant(). proportions() did not scan.
        verify(exactly = 1) { ProjectLanguageScanner.scan(project) }
    }

    @Test
    fun `invalidate evicts the cached verdict and weights atomically`() {
        // Invalidate must be total — a drift where dominant() re-scans but
        // proportions() still serves stale weights would confuse users who
        // see the polyglot copy while their override applies correctly (or
        // vice-versa).
        val project = stubProject("/tmp/proportions-invalidate-${System.nanoTime()}")
        every { ProjectLanguageScanner.scan(project) } returns
            mapOf("python" to 800L, "yaml" to 200L)
        wireProjectRootManager(project, sdkName = null)
        wireModuleManager(project, moduleNames = emptyList())

        // Warm the cached verdict, including its weights payload.
        assertEquals("python", ProjectLanguageDetector.dominant(project))
        assertEquals(
            mapOf("python" to 800L, "yaml" to 200L),
            ProjectLanguageDetector.proportions(project),
        )

        ProjectLanguageDetector.invalidate(project)

        // After invalidate: proportions returns null (verdict evicted) AND a
        // fresh dominant() call re-kicks the scanner.
        assertNull(ProjectLanguageDetector.proportions(project))
        assertEquals("python", ProjectLanguageDetector.dominant(project))
        verify(exactly = 2) { ProjectLanguageScanner.scan(project) }
    }

    @Test
    fun `proportions returns null when the project has no canonical path`() {
        // Disposal race / default-project: AccentResolver.projectKey returns
        // null. proportions() must bail out with null — the polyglot copy is
        // the correct fallback.
        val project = mockk<Project>()
        every { project.basePath } returns null
        every { project.isDefault } returns false
        every { project.isDisposed } returns false

        assertNull(ProjectLanguageDetector.proportions(project))
    }

    @Test
    fun `proportions returns null when the scan returned null transient failure`() {
        // Scanner returns null when it can't give an authoritative answer
        // (dumb mode, disposal race). detectAndCache treats this as
        // cacheable=false → no verdict entry is written. proportions() must
        // therefore keep returning null on subsequent calls.
        val project = stubProject("/tmp/proportions-transient-${System.nanoTime()}")
        every { ProjectLanguageScanner.scan(project) } returns null
        wireProjectRootManager(project, sdkName = null)
        wireModuleManager(project, moduleNames = emptyList())

        // dominant() reports null AND does NOT cache (transient failure).
        assertNull(ProjectLanguageDetector.dominant(project))
        assertNull(ProjectLanguageDetector.proportions(project))
    }

    @Test
    fun `clear empties cached verdicts and their weights`() {
        // The @TestOnly clear() seam must reset every cached verdict — test
        // isolation depends on it. A regression that kept warmed verdicts
        // around would leak weights across tests.
        val project = stubProject("/tmp/proportions-clear-${System.nanoTime()}")
        every { ProjectLanguageScanner.scan(project) } returns
            mapOf("rust" to 1_000L)
        wireProjectRootManager(project, sdkName = null)
        wireModuleManager(project, moduleNames = emptyList())

        ProjectLanguageDetector.dominant(project)
        assertEquals(mapOf("rust" to 1_000L), ProjectLanguageDetector.proportions(project))

        ProjectLanguageDetector.clear()

        assertNull(ProjectLanguageDetector.proportions(project))
    }

    @Test
    fun `legacy SDK fallback leaves proportions absent`() {
        // Scanner returned emptyMap (brand-new project / docs-only after filter).
        // Legacy SDK resolves to "rust" — a Detected verdict is cached, but its
        // weights remain absent so proportions() returns null and the
        // caller renders polyglot copy (there are no meaningful proportions to
        // display when the scan itself is empty).
        val project = stubProject("/tmp/proportions-legacy-sdk-${System.nanoTime()}")
        every { ProjectLanguageScanner.scan(project) } returns emptyMap()
        wireProjectRootManager(project, sdkName = "RustSdkType")
        wireModuleManager(project, moduleNames = emptyList())

        assertEquals("rust", ProjectLanguageDetector.dominant(project))
        assertNull(ProjectLanguageDetector.proportions(project))
    }

    @Test
    fun `proportions for one project does not leak to another project`() {
        // Multi-entity isolation (CLAUDE.md testing philosophy): cached verdicts
        // are keyed by AccentResolver.projectKey (canonical path), so project A
        // and project B each get their own warmed entry. A bug that used a
        // shared key would cause cross-contamination and user-visible confusion.
        val a = stubProject("/tmp/proportions-a-${System.nanoTime()}")
        val b = stubProject("/tmp/proportions-b-${System.nanoTime()}")
        val aWeights = mapOf("kotlin" to 900L, "java" to 100L)
        val bWeights = mapOf("python" to 800L, "yaml" to 200L)
        every { ProjectLanguageScanner.scan(a) } returns aWeights
        every { ProjectLanguageScanner.scan(b) } returns bWeights
        wireProjectRootManager(a, sdkName = null)
        wireProjectRootManager(b, sdkName = null)
        wireModuleManager(a, moduleNames = emptyList())
        wireModuleManager(b, moduleNames = emptyList())

        ProjectLanguageDetector.dominant(a)
        ProjectLanguageDetector.dominant(b)

        assertEquals(aWeights, ProjectLanguageDetector.proportions(a))
        assertEquals(bWeights, ProjectLanguageDetector.proportions(b))
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

    // ── proportions() after explicit verdict eviction ─────────────────────────

    @Test
    fun `proportions returns null when the warmed verdict cache entry was evicted`() {
        // With the single verdict cache, proportions() must treat an evicted
        // warmed entry as cold state immediately rather than surfacing the
        // previously detected weights after test-only cache eviction.
        val project = stubProject("/tmp/prop-guard-${System.nanoTime()}")
        every { ProjectLanguageScanner.scan(project) } returns mapOf("kotlin" to 500L, "java" to 500L)
        wireProjectRootManager(project, sdkName = "KotlinSDK")
        wireModuleManager(project, moduleNames = emptyList())

        // Warm one coherent verdict entry with weights.
        ProjectLanguageDetector.dominant(project)
        assertEquals(
            mapOf("kotlin" to 500L, "java" to 500L),
            ProjectLanguageDetector.proportions(project),
            "baseline: warmed verdict must serve the weights",
        )

        // Evict the warmed verdict entry. proportions() must treat this as cold.
        ProjectLanguageDetector.evictVerdictCacheForTest(project)
        assertNull(
            ProjectLanguageDetector.proportions(project),
            "proportions must not serve weights after the warmed verdict entry was evicted",
        )
    }

    // ── refreshAccentOnEdt (extracted from cacheable scan refresh) ────────────

    @Test
    fun `refreshAccentOnEdt reapplies resolver fallback when language override no longer matches`() {
        // A cacheable scan can remove the active language override as well as
        // add one. The resolver owns that fallback decision; this helper must
        // apply whatever it resolves so chrome/glow do not stay on the old
        // language accent until the next focus swap.
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        mockkObject(AccentResolver)
        every { AccentResolver.resolve(any(), any<AyuVariant>()) } returns "#FFCC66"
        mockkObject(AccentApplicator)
        every { AccentApplicator.apply(any()) } answers { Unit }
        val swapService = mockk<dev.ayuislands.settings.mappings.ProjectAccentSwapService>(relaxed = true)
        mockkObject(dev.ayuislands.settings.mappings.ProjectAccentSwapService.Companion)
        every {
            dev.ayuislands.settings.mappings.ProjectAccentSwapService
                .getInstance()
        } returns swapService

        val project = stubProject("/tmp/refresh-fallback-${System.nanoTime()}")
        every { AccentApplicator.resolveFocusedProject() } returns project
        ProjectLanguageDetector.refreshAccentOnEdt(project)

        verify(exactly = 1) { AccentApplicator.applyFromHexString("#FFCC66") }
        verify(exactly = 1) { swapService.notifyExternalApply("#FFCC66") }
    }

    @Test
    fun `refreshAccentOnEdt runs the full apply chain when override is present`() {
        // Happy-path lock: given a resolved language override on a live
        // project, the helper must call the full resolver → applicator →
        // swap-cache chain. Dropping any of the three downstream calls would
        // leave users stuck on the previous accent until the next focus swap.
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        mockkObject(AccentResolver)
        every { AccentResolver.resolve(any(), any<AyuVariant>()) } returns "#FFCC66"
        mockkObject(AccentApplicator)
        every { AccentApplicator.apply(any()) } answers { Unit }
        val swapService = mockk<dev.ayuislands.settings.mappings.ProjectAccentSwapService>(relaxed = true)
        mockkObject(dev.ayuislands.settings.mappings.ProjectAccentSwapService.Companion)
        every {
            dev.ayuislands.settings.mappings.ProjectAccentSwapService
                .getInstance()
        } returns swapService

        val project = stubProject("/tmp/refresh-hit-${System.nanoTime()}")
        every { AccentApplicator.resolveFocusedProject() } returns project
        ProjectLanguageDetector.refreshAccentOnEdt(project)

        verify(exactly = 1) { AccentApplicator.applyFromHexString("#FFCC66") }
        verify(exactly = 1) { swapService.notifyExternalApply("#FFCC66") }
    }

    @Test
    fun `refreshAccentOnEdt swallows a throwing apply chain without rethrowing`() {
        // Contract lock on the runCatchingPreservingCancellation wrapper:
        // AccentApplicator.apply can throw on a LafManager race / UIManager
        // shutdown; if that propagates out of the invokeLater callback it
        // becomes an uncaught EDT exception. This test asserts the helper
        // returns normally (WARN-logged internally) instead of rethrowing.
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        mockkObject(AccentResolver)
        every { AccentResolver.resolve(any(), any<AyuVariant>()) } returns "#FFCC66"
        mockkObject(AccentApplicator)
        every { AccentApplicator.apply(any()) } throws RuntimeException("LafManager boom")

        val project = stubProject("/tmp/refresh-throw-${System.nanoTime()}")
        every { AccentApplicator.resolveFocusedProject() } returns project
        // Must not throw — load-bearing assertion is simply that the call
        // completes. A regression dropping the runCatching would propagate
        // the RuntimeException and fail this test.
        ProjectLanguageDetector.refreshAccentOnEdt(project)
    }

    @Test
    fun `refreshAccentOnEdt swallows a throwing resolver without rethrowing`() {
        // The resolver now owns both hit and fallback decisions. A corrupt
        // override, plugin-unload race, or resolver regression must not escape
        // the invokeLater callback as an uncaught EDT exception.
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        mockkObject(AccentResolver)
        every { AccentResolver.resolve(any(), any<AyuVariant>()) } throws RuntimeException("resolver boom")

        mockkObject(AccentApplicator)
        every { AccentApplicator.apply(any()) } answers { Unit }

        val project = stubProject("/tmp/refresh-settings-boom-${System.nanoTime()}")
        every { AccentApplicator.resolveFocusedProject() } returns project

        // Must not throw — the runCatching contains the resolver.
        ProjectLanguageDetector.refreshAccentOnEdt(project)

        // And because the resolver failed, the apply chain must not be reached.
        verify(exactly = 0) { AccentApplicator.apply(any()) }
    }

    @Test
    fun `refreshAccentOnEdt skips apply chain when variant detection returns null`() {
        // Null variant = no Ayu theme active. The early `?: return@…` keeps
        // the applicator from touching UIManager when there's no Ayu theme to
        // drive. Locking the guard so a future author doesn't delete it as
        // "dead code" — it's the fallback path for users on a non-Ayu theme.
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns null

        mockkObject(AccentApplicator)
        every { AccentApplicator.apply(any()) } answers { Unit }

        val project = stubProject("/tmp/refresh-null-variant-${System.nanoTime()}")
        every { AccentApplicator.resolveFocusedProject() } returns project
        ProjectLanguageDetector.refreshAccentOnEdt(project)

        verify(exactly = 0) { AccentApplicator.apply(any()) }
    }

    @Test
    fun `refreshAccentOnEdt skips apply chain when focused project changed after scan`() {
        // The apply path writes app-global UIManager state. A background scan
        // from an old project must not repaint over the project that gained
        // focus while the scan was running.
        val scannedProject = stubProject("/tmp/refresh-old-focus-${System.nanoTime()}")
        val focusedProject = stubProject("/tmp/refresh-new-focus-${System.nanoTime()}")
        mockkObject(AccentApplicator)
        every { AccentApplicator.resolveFocusedProject() } returns focusedProject
        every { AccentApplicator.apply(any()) } answers { Unit }

        ProjectLanguageDetector.refreshAccentOnEdt(scannedProject)

        verify(exactly = 0) { AccentApplicator.apply(any()) }
    }

    @Test
    fun `refreshAccentOnEdt skips apply chain when project is disposed`() {
        // Round-2 disposal guard: scheduling delay between background scan
        // and EDT dispatch can close the project. The early return here
        // keeps the applicator from writing UIManager entries for a dead
        // project's swap-cache.
        mockkObject(AccentApplicator)
        every { AccentApplicator.apply(any()) } answers { Unit }

        val project = stubProject("/tmp/refresh-disposed-${System.nanoTime()}")
        every { project.isDisposed } returns true

        ProjectLanguageDetector.refreshAccentOnEdt(project)

        verify(exactly = 0) { AccentApplicator.apply(any()) }
    }

    // ── rescan (Phase 29) ─────────────────────────────────────────────────────

    @Test
    fun `rescan clears the cached verdict so proportions returns null immediately`() {
        // Baseline: warm a verdict via dominant(). Then mock the scheduler
        // so the post-invalidate scan never actually runs; this proves
        // rescan()'s invalidate happens synchronously before the async
        // scheduler is consulted.
        val project = stubProject("/tmp/rescan-clears-${System.nanoTime()}")
        every { ProjectLanguageScanner.scan(project) } returns mapOf("kotlin" to 900L, "java" to 100L)
        wireProjectRootManager(project, sdkName = null)
        wireModuleManager(project, moduleNames = emptyList())
        ProjectLanguageDetector.dominant(project)
        assertEquals(
            mapOf("kotlin" to 900L, "java" to 100L),
            ProjectLanguageDetector.proportions(project),
        )

        stubDumbServiceSmart(project)
        mockkObject(ProjectLanguageScanAsync)
        every { ProjectLanguageScanAsync.schedule(any(), any()) } returns true

        ProjectLanguageDetector.rescan(project)

        assertNull(
            ProjectLanguageDetector.proportions(project),
            "rescan MUST evict the cached verdict synchronously — a stale breakdown " +
                "served while the scan is still running would misrepresent the in-progress state",
        )
    }

    @Test
    fun `rescan forwards to scheduler keyed by the canonical project path`() {
        // The dedup gate in ProjectLanguageScanAsync keys on the canonical
        // project path — rescan MUST use the same key so rapid-fire Rescan
        // clicks coalesce into a single scan run.
        val project = stubProject("/tmp/rescan-schedules-${System.nanoTime()}")
        every { ProjectLanguageScanner.scan(project) } returns emptyMap()
        wireProjectRootManager(project, sdkName = null)
        wireModuleManager(project, moduleNames = emptyList())

        stubDumbServiceSmart(project)
        mockkObject(ProjectLanguageScanAsync)
        val capturedKey = io.mockk.slot<String>()
        every { ProjectLanguageScanAsync.schedule(capture(capturedKey), any()) } returns true

        ProjectLanguageDetector.rescan(project)

        assertEquals(
            AccentResolver.projectKey(project),
            capturedKey.captured,
            "rescan forwards the scheduler key so the dedup gate can coalesce concurrent requests",
        )
    }

    @Test
    fun `rescan on a project with no canonical path is a no-op`() {
        // AccentResolver.projectKey returns null for a disposal race / default
        // project. rescan must bail out without touching caches or scheduling.
        val project = mockk<Project>()
        every { project.basePath } returns null
        every { project.isDefault } returns false
        every { project.isDisposed } returns false

        stubDumbServiceSmart(project)
        mockkObject(ProjectLanguageScanAsync)

        ProjectLanguageDetector.rescan(project)

        verify(exactly = 0) { ProjectLanguageScanAsync.schedule(any(), any()) }
    }

    @Test
    fun `rescan runs detectInternal and publishes scanCompleted with the winning id`() {
        // End-to-end happy path: rescan → scheduler runs task inline →
        // detectAndCache writes a cacheable verdict → publishScanCompleted fires on
        // EDT with the detected id. Verified by capturing the MessageBus
        // subscriber and asserting scanCompleted was called with "python".
        val project = stubProject("/tmp/rescan-publish-hit-${System.nanoTime()}")
        every { ProjectLanguageScanner.scan(project) } returns mapOf("python" to 900L, "yaml" to 100L)
        wireProjectRootManager(project, sdkName = null)
        wireModuleManager(project, moduleNames = emptyList())

        stubDumbServiceSmart(project)
        val listener = mockk<ProjectLanguageDetectionListener>(relaxed = true)
        wireMessageBus(project, listener)
        runInvokeLaterInline()
        runSchedulerInline()

        ProjectLanguageDetector.rescan(project)

        verify(exactly = 1) { listener.scanCompleted(ScanOutcome.Detected("python")) }
    }

    @Test
    fun `rescan publishes Unavailable when scanner hits a non-cacheable transient failure`() {
        // Scanner returns null when it can't give an authoritative answer
        // (ReadAction throw, disposal race, DumbService edge). The
        // DetectionResult carries cacheable=false, so detectAndCache does
        // NOT write a verdict entry. scheduleBackgroundDetection then maps
        // the returned ProjectLanguageVerdict.Unavailable to
        // ScanOutcome.Unavailable.
        // Locks the discriminator that distinguishes Unavailable from
        // Polyglot — a regression collapsing both into a single outcome
        // would surface here.
        val project = stubProject("/tmp/rescan-publish-unavailable-${System.nanoTime()}")
        every { ProjectLanguageScanner.scan(project) } returns null
        wireProjectRootManager(project, sdkName = null)
        wireModuleManager(project, moduleNames = emptyList())

        stubDumbServiceSmart(project)
        val listener = mockk<ProjectLanguageDetectionListener>(relaxed = true)
        wireMessageBus(project, listener)
        runInvokeLaterInline()
        runSchedulerInline()
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        mockkObject(AccentResolver)
        every { AccentResolver.resolve(project, AyuVariant.MIRAGE) } returns "#FFCC66"
        mockkObject(AccentApplicator)
        every { AccentApplicator.resolveFocusedProject() } returns project
        every { AccentApplicator.apply(any()) } answers { Unit }

        ProjectLanguageDetector.rescan(project)

        verify(exactly = 1) { listener.scanCompleted(ScanOutcome.Unavailable) }
        verify(exactly = 0) { AccentApplicator.resolveFocusedProject() }
        verify(exactly = 0) { AccentApplicator.apply(any()) }
    }

    @Test
    fun `rescan publishes Polyglot on no-winner verdict`() {
        // 50/50 split with no SDK hint → scan returns no winner → detector
        // caches a NoWinner verdict → publishScanCompleted fires with Polyglot so
        // subscribers (Settings row, Rescan balloon) can render the polyglot
        // copy without relying on detector state inspection.
        val project = stubProject("/tmp/rescan-publish-polyglot-${System.nanoTime()}")
        every { ProjectLanguageScanner.scan(project) } returns mapOf("kotlin" to 500L, "java" to 500L)
        wireProjectRootManager(project, sdkName = null)
        wireModuleManager(project, moduleNames = emptyList())

        stubDumbServiceSmart(project)
        val listener = mockk<ProjectLanguageDetectionListener>(relaxed = true)
        wireMessageBus(project, listener)
        runInvokeLaterInline()
        runSchedulerInline()

        ProjectLanguageDetector.rescan(project)

        verify(exactly = 1) { listener.scanCompleted(ScanOutcome.Polyglot) }
    }

    @Test
    fun `rescan refreshes fallback accent on polyglot no-winner verdict`() {
        // Polyglot is a definitive cacheable verdict, not a transient failure.
        // If the previous cache selected a language override, the UI must
        // re-apply the resolver fallback immediately instead of waiting for
        // focus swap to recover chrome and glow.
        val project = stubProject("/tmp/rescan-polyglot-refresh-${System.nanoTime()}")
        every { ProjectLanguageScanner.scan(project) } returns mapOf("kotlin" to 500L, "java" to 500L)
        wireProjectRootManager(project, sdkName = null)
        wireModuleManager(project, moduleNames = emptyList())

        stubDumbServiceSmart(project)
        val listener = mockk<ProjectLanguageDetectionListener>(relaxed = true)
        wireMessageBus(project, listener)
        runInvokeLaterInline()
        runSchedulerInline()

        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        mockkObject(AccentResolver)
        every { AccentResolver.resolve(project, AyuVariant.MIRAGE) } returns "#FFCC66"
        mockkObject(AccentApplicator)
        every { AccentApplicator.resolveFocusedProject() } returns project
        every { AccentApplicator.apply(any()) } answers { Unit }
        val swapService = mockk<dev.ayuislands.settings.mappings.ProjectAccentSwapService>(relaxed = true)
        mockkObject(dev.ayuislands.settings.mappings.ProjectAccentSwapService.Companion)
        every {
            dev.ayuislands.settings.mappings.ProjectAccentSwapService
                .getInstance()
        } returns swapService

        ProjectLanguageDetector.rescan(project)

        verify(exactly = 1) { listener.scanCompleted(ScanOutcome.Polyglot) }
        verify(exactly = 1) { AccentApplicator.applyFromHexString("#FFCC66") }
        verify(exactly = 1) { swapService.notifyExternalApply("#FFCC66") }
    }

    @Test
    fun `rescan skips publish entirely when project disposes before scan completes`() {
        // Dispose-between-schedule-and-completion race: the scheduler's
        // executor thread checks isDisposed before calling detectAndCache AND
        // before publishScanCompleted invokeLater. A bug that dropped the
        // second guard would publish on EDT after project disposal → stale
        // MessageBus. Simulate by flipping isDisposed true between schedule
        // and task execution.
        val project = stubProject("/tmp/rescan-disposed-${System.nanoTime()}")
        every { ProjectLanguageScanner.scan(project) } returns mapOf("kotlin" to 1_000L)
        wireProjectRootManager(project, sdkName = null)
        wireModuleManager(project, moduleNames = emptyList())

        stubDumbServiceSmart(project)
        val listener = mockk<ProjectLanguageDetectionListener>(relaxed = true)
        wireMessageBus(project, listener)
        runInvokeLaterInline()

        // Custom scheduler: flips disposed right before running the task.
        mockkObject(ProjectLanguageScanAsync)
        every { ProjectLanguageScanAsync.schedule(any(), any()) } answers {
            every { project.isDisposed } returns true
            val task = secondArg<() -> Unit>()
            task()
            true
        }

        ProjectLanguageDetector.rescan(project)

        verify(exactly = 0) { listener.scanCompleted(any()) }
    }

    @Test
    fun `rescan on null project key publishes Unavailable outcome`() {
        // User-facing symptom guard: `AccentResolver.projectKey` can
        // return null for a disposal race or canonicalization failure on
        // a live project. Before the fix, `rescan` returned silently,
        // orphaning the one-shot subscriber installed by
        // `RescanLanguageAction.subscribeOnceForBalloon` — user clicks
        // Rescan, gets no balloon, subscription leaks until project
        // close. The fix publishes [ScanOutcome.Unavailable] (transient,
        // not a definitive polyglot verdict) so subscribers fire the
        // polyglot-copy balloon and disconnect while signalling to the
        // Settings row that the previous render should stay put.
        val project = mockk<Project>()
        every { project.basePath } returns null
        every { project.isDefault } returns false
        every { project.isDisposed } returns false

        val listener = mockk<ProjectLanguageDetectionListener>(relaxed = true)
        wireMessageBus(project, listener)
        runInvokeLaterInline()
        mockkObject(ProjectLanguageScanAsync)

        ProjectLanguageDetector.rescan(project)

        verify(exactly = 1) { listener.scanCompleted(ScanOutcome.Unavailable) }
        verify(exactly = 0) { ProjectLanguageScanAsync.schedule(any(), any()) }
    }

    @Test
    fun `publishScanCompleted swallows a throwing subscriber without rethrow`() {
        // Defensive lock on the runCatchingPreservingCancellation wrap
        // inside publishScanCompleted. A rogue subscriber whose
        // scanCompleted handler throws must not propagate into the EDT
        // invokeLater callback — that becomes an uncaught EDT exception.
        val project = stubProject("/tmp/rescan-publish-throw-${System.nanoTime()}")
        every { ProjectLanguageScanner.scan(project) } returns mapOf("kotlin" to 1_000L)
        wireProjectRootManager(project, sdkName = null)
        wireModuleManager(project, moduleNames = emptyList())

        stubDumbServiceSmart(project)
        val listener =
            ProjectLanguageDetectionListener {
                error("subscriber blew up on purpose")
            }
        val bus = mockk<com.intellij.util.messages.MessageBus>()
        every { project.messageBus } returns bus
        every { bus.syncPublisher(ProjectLanguageDetectionListener.TOPIC) } returns listener
        runInvokeLaterInline()
        runSchedulerInline()

        // Load-bearing claim: rescan completes cleanly despite the rogue subscriber.
        ProjectLanguageDetector.rescan(project)
    }

    // Helpers for rescan tests — shared across the five specs above.

    private fun stubDumbServiceSmart(project: Project) {
        // scheduleBackgroundDetection gates on DumbService.isDumb(project) —
        // which resolves to `project.getService(DumbService::class).isDumb`
        // (the companion @JvmStatic delegates to getInstance → getService).
        // mockkStatic on DumbService does NOT intercept the companion call,
        // so we wire the service lookup directly on the mock Project instead.
        val dumbService = mockk<com.intellij.openapi.project.DumbService>()
        every { dumbService.isDumb } returns false
        every { project.getService(com.intellij.openapi.project.DumbService::class.java) } returns dumbService
    }

    private fun wireMessageBus(
        project: Project,
        listener: ProjectLanguageDetectionListener,
    ) {
        val bus = mockk<com.intellij.util.messages.MessageBus>()
        every { project.messageBus } returns bus
        every { bus.syncPublisher(ProjectLanguageDetectionListener.TOPIC) } returns listener
    }

    private fun runInvokeLaterInline() {
        mockkStatic(javax.swing.SwingUtilities::class)
        every { javax.swing.SwingUtilities.invokeLater(any()) } answers {
            firstArg<Runnable>().run()
        }
    }

    private fun runSchedulerInline() {
        mockkObject(ProjectLanguageScanAsync)
        every { ProjectLanguageScanAsync.schedule(any(), any()) } answers {
            val task = secondArg<() -> Unit>()
            task()
            true
        }
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
