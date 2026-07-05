package dev.ayuislands.accent

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Locks the exact [ProjectLanguageDetector] call pattern per lookup strategy.
 * These interactions are contractual: resolver and pending tests pin them with
 * `verify(exactly = 0)` / `verify(atLeast = 1)` assertions, so a drift here
 * (e.g. the diagnostics path starting to warm the cache from Settings) would
 * either schedule background scans from a read-only UI or leave the live
 * resolver permanently cold.
 */
class AccentDetectorLookupTest {
    private val project = mockk<Project>()

    @BeforeTest
    fun setUp() {
        mockkObject(ProjectLanguageDetector)
        every { ProjectLanguageDetector.dominant(any()) } returns null
        every { ProjectLanguageDetector.verdict(any(), any<Boolean>()) } returns ProjectLanguageVerdict.Cold
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    // CacheOnlyLookup

    @Test
    fun `cache-only language rung reads the cached verdict when language candidates exist`() {
        val detected = ProjectLanguageVerdict.Detected("kotlin", mapOf("kotlin" to 1_000L))
        every { ProjectLanguageDetector.verdict(project) } returns detected

        val verdict =
            AccentDetectorLookup.CacheOnlyLookup.languageRungVerdict(
                project,
                hasLanguageCandidate = true,
                hasFallbackCandidate = false,
            )

        assertEquals(detected, verdict)
        verify(exactly = 0) { ProjectLanguageDetector.dominant(any()) }
        verify(exactly = 0) { ProjectLanguageDetector.verdict(project, warmCache = true) }
    }

    @Test
    fun `cache-only language rung consults for a fallback candidate even without language candidates`() {
        val noWinner = ProjectLanguageVerdict.NoWinner(mapOf("kotlin" to 500L, "java" to 500L))
        every { ProjectLanguageDetector.verdict(project) } returns noWinner

        val verdict =
            AccentDetectorLookup.CacheOnlyLookup.languageRungVerdict(
                project,
                hasLanguageCandidate = false,
                hasFallbackCandidate = true,
            )

        assertEquals(noWinner, verdict)
    }

    @Test
    fun `cache-only language rung skips the detector when nothing can use its answer`() {
        assertNull(
            AccentDetectorLookup.CacheOnlyLookup.languageRungVerdict(
                project,
                hasLanguageCandidate = false,
                hasFallbackCandidate = false,
            ),
        )
        verify(exactly = 0) { ProjectLanguageDetector.verdict(project) }
    }

    @Test
    fun `cache-only fallback rung reuses the language rung verdict without a second read`() {
        val noWinner = ProjectLanguageVerdict.NoWinner(mapOf("kotlin" to 500L, "java" to 500L))

        val verdict = AccentDetectorLookup.CacheOnlyLookup.fallbackRungVerdict(project, noWinner)

        assertEquals(noWinner, verdict)
        verify(exactly = 0) { ProjectLanguageDetector.verdict(project, warmCache = true) }
        verify(exactly = 0) { ProjectLanguageDetector.verdict(project) }
    }

    @Test
    fun `cache-only fallback rung warms the cache when the language rung was skipped`() {
        // Pre-existing diagnostics-chain behavior (pinned by AccentResolverChainTest):
        // without the warm-up the fallback answer would stay "Detection pending" forever.
        val noWinner = ProjectLanguageVerdict.NoWinner(mapOf("kotlin" to 500L, "java" to 500L))
        every { ProjectLanguageDetector.verdict(project, warmCache = true) } returns noWinner

        val verdict = AccentDetectorLookup.CacheOnlyLookup.fallbackRungVerdict(project, languageRungVerdict = null)

        assertEquals(noWinner, verdict)
        verify(atLeast = 1) { ProjectLanguageDetector.verdict(project, warmCache = true) }
    }

    // StrictCacheOnlyLookup

    @Test
    fun `strict cache-only fallback rung never warms even when the language rung was skipped`() {
        // The pending Settings preview is read-only: opening or refreshing the
        // resolution panel must not enqueue a background scan (pre-engine
        // pending-walker behavior, re-flagged by two independent reviews).
        val cold = ProjectLanguageVerdict.Cold
        every { ProjectLanguageDetector.verdict(project) } returns cold

        val verdict =
            AccentDetectorLookup.StrictCacheOnlyLookup.fallbackRungVerdict(project, languageRungVerdict = null)

        assertEquals(cold, verdict)
        verify(exactly = 0) { ProjectLanguageDetector.verdict(project, warmCache = true) }
    }

    @Test
    fun `strict cache-only language rung reads the cached verdict without warming`() {
        val detected = ProjectLanguageVerdict.Detected("kotlin", weights = null)
        every { ProjectLanguageDetector.verdict(project) } returns detected

        val verdict =
            AccentDetectorLookup.StrictCacheOnlyLookup.languageRungVerdict(
                project,
                hasLanguageCandidate = true,
                hasFallbackCandidate = false,
            )

        assertEquals(detected, verdict)
        verify(exactly = 0) { ProjectLanguageDetector.verdict(project, warmCache = true) }
        verify(exactly = 0) { ProjectLanguageDetector.dominant(project) }
    }

    // WarmingLookup

    @Test
    fun `warming language rung rides dominant and synthesizes a detected verdict`() {
        every { ProjectLanguageDetector.dominant(project) } returns "kotlin"

        val verdict =
            AccentDetectorLookup.WarmingLookup.languageRungVerdict(
                project,
                hasLanguageCandidate = true,
                hasFallbackCandidate = true,
            )

        assertEquals(ProjectLanguageVerdict.Detected("kotlin", weights = null), verdict)
        verify(exactly = 0) { ProjectLanguageDetector.verdict(project) }
    }

    @Test
    fun `warming language rung reports a cold verdict when dominant has no answer`() {
        every { ProjectLanguageDetector.dominant(project) } returns null

        val verdict =
            AccentDetectorLookup.WarmingLookup.languageRungVerdict(
                project,
                hasLanguageCandidate = true,
                hasFallbackCandidate = false,
            )

        assertEquals(ProjectLanguageVerdict.Cold, verdict)
    }

    @Test
    fun `warming language rung never consults dominant without language candidates`() {
        assertNull(
            AccentDetectorLookup.WarmingLookup.languageRungVerdict(
                project,
                hasLanguageCandidate = false,
                hasFallbackCandidate = true,
            ),
        )
        verify(exactly = 0) { ProjectLanguageDetector.dominant(any()) }
    }

    @Test
    fun `warming fallback rung reads cache-only when the language rung already consulted`() {
        val noWinner = ProjectLanguageVerdict.NoWinner(mapOf("kotlin" to 500L, "java" to 500L))
        every { ProjectLanguageDetector.verdict(project) } returns noWinner

        val verdict =
            AccentDetectorLookup.WarmingLookup.fallbackRungVerdict(
                project,
                languageRungVerdict = ProjectLanguageVerdict.Cold,
            )

        assertEquals(noWinner, verdict)
        verify(exactly = 0) { ProjectLanguageDetector.verdict(project, warmCache = true) }
    }

    @Test
    fun `warming fallback rung warms the cache when the language rung was skipped`() {
        val noWinner = ProjectLanguageVerdict.NoWinner(mapOf("kotlin" to 500L, "java" to 500L))
        every { ProjectLanguageDetector.verdict(project, warmCache = true) } returns noWinner

        val verdict = AccentDetectorLookup.WarmingLookup.fallbackRungVerdict(project, languageRungVerdict = null)

        assertEquals(noWinner, verdict)
        verify(atLeast = 1) { ProjectLanguageDetector.verdict(project, warmCache = true) }
    }
}
