package dev.ayuislands.settings.mappings

import com.intellij.openapi.project.Project
import dev.ayuislands.accent.ProjectLanguageDetector
import dev.ayuislands.accent.ProjectLanguageScanner
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Wiring tests for the detected-language proportions status line rendered by
 * [OverridesGroupBuilder] inside the Settings → Accent → Overrides group.
 *
 * Exercises the builder through its `@TestOnly` seams
 * ([OverridesGroupBuilder.setParentProjectForTest],
 * [OverridesGroupBuilder.currentProportionsTextForTest],
 * [OverridesGroupBuilder.refreshProportionsLabelForTest]) — mirrors the
 * builder-level pattern already established by [OverridesGroupBuilderPendingTest],
 * does NOT spin up the Swing panel (no `BasePlatformTestCase` per project
 * `TESTING.md`).
 *
 * Covers VALIDATION tasks 26-02-01 through 26-02-05.
 */
class OverridesGroupBuilderProportionsTest {
    private lateinit var mappingsState: AccentMappingsState

    @BeforeTest
    fun setUp() {
        // Mirror OverridesGroupBuilderPendingTest so an accidental loadFromState()
        // on builder init doesn't pull in the real service graph.
        mappingsState = AccentMappingsState()
        val settings = mockk<AccentMappingsSettings>()
        every { settings.state } returns mappingsState
        mockkObject(AccentMappingsSettings.Companion)
        every { AccentMappingsSettings.getInstance() } returns settings

        mockkObject(ProjectLanguageDetector)
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    // ── Proportion rendering states ───────────────────────────────────────────

    @Test
    fun `proportions text uses warm cache single-language weights`() {
        // VALIDATION 26-02-01 — warm cache with a single code language renders as
        // "Detected in this project: Kotlin (100%)".
        val project = stubProject("/tmp/prop-single-${System.nanoTime()}")
        every { ProjectLanguageDetector.proportions(project) } returns mapOf("kotlin" to 1_000L)

        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(project) }

        assertEquals(
            "Detected in this project: Kotlin (100%)",
            builder.currentProportionsTextForTest(),
        )
    }

    @Test
    fun `proportions text renders fixed polyglot copy when detector returns null`() {
        // VALIDATION 26-02-02 — cold cache / polyglot no-winner / legacy-SDK fallback
        // all surface as null from ProjectLanguageDetector.proportions. The builder
        // must render the exact D-05 polyglot literal, em-dash included.
        val project = stubProject("/tmp/prop-polyglot-${System.nanoTime()}")
        every { ProjectLanguageDetector.proportions(project) } returns null

        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(project) }

        assertEquals(
            "Polyglot — no single dominant language; global accent applies",
            builder.currentProportionsTextForTest(),
        )
    }

    @Test
    fun `proportions text renders fixed polyglot copy when parentProject is null`() {
        // Settings opened with no focused project window — the builder has nothing
        // to query; the fixed polyglot copy is the correct fallback.
        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(null) }

        assertEquals(
            "Polyglot — no single dominant language; global accent applies",
            builder.currentProportionsTextForTest(),
        )
    }

    @Test
    fun `proportions text shows only code tier on code-plus-markup projects`() {
        // Reinforces VALIDATION 26-02-01 at the builder layer: the two-tier filter
        // from D-06 drops XML out of the display base when any code language is
        // present. Prevents the misleading "Kotlin (40%) • XML (60%)" surface on
        // Android-style projects called out by Phase 26 success criterion #4.
        val project = stubProject("/tmp/prop-code-markup-${System.nanoTime()}")
        every { ProjectLanguageDetector.proportions(project) } returns
            mapOf("kotlin" to 400L, "xml" to 600L)

        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(project) }

        assertEquals(
            "Detected in this project: Kotlin (100%)",
            builder.currentProportionsTextForTest(),
        )
    }

    // ── Refresh lifecycle (D-03) ──────────────────────────────────────────────

    @Test
    fun `refresh re-reads detector cache so focus-swap shows new project proportions`() {
        // VALIDATION 26-02-03 — focus-swap path. The orchestrator rebinds
        // parentProject on Settings re-open; the refresh helper must re-read from
        // the detector cache rather than cache a stale snapshot inside the builder.
        val projectA = stubProject("/tmp/prop-a-${System.nanoTime()}")
        val projectB = stubProject("/tmp/prop-b-${System.nanoTime()}")
        every { ProjectLanguageDetector.proportions(projectA) } returns mapOf("kotlin" to 1_000L)
        every { ProjectLanguageDetector.proportions(projectB) } returns mapOf("python" to 1_000L)

        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(projectA) }
        assertEquals(
            "Detected in this project: Kotlin (100%)",
            builder.currentProportionsTextForTest(),
        )

        // Simulate focus swap — parentProject rebinds then refresh fires (same
        // surface reset() uses in production).
        builder.setParentProjectForTest(projectB)
        builder.refreshProportionsLabelForTest()

        assertEquals(
            "Detected in this project: Python (100%)",
            builder.currentProportionsTextForTest(),
        )
    }

    // ── HTML-safety (T-26-01) ─────────────────────────────────────────────────

    @Test
    fun `proportions text escapes HTML entities in language display names`() {
        // VALIDATION 26-02-04 — threat T-26-01. JEditorPane defaults to text/html;
        // a third-party plugin could register a Language whose `.displayName`
        // contains markup. Here we synthesise a weight whose id is not registered,
        // which exercises the formatter's title-case fallback path and lets the raw
        // "<" character reach StringUtil.escapeXmlEntities inside computeProportionsText.
        val project = stubProject("/tmp/prop-xss-${System.nanoTime()}")
        every { ProjectLanguageDetector.proportions(project) } returns
            mapOf("<script>evil</script>" to 1_000L)

        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(project) }

        val text = builder.currentProportionsTextForTest()
        assertFalse(
            text.contains("<script>"),
            "raw <script> must not appear in rendered text — got: $text",
        )
        assertTrue(
            text.contains("&lt;script&gt;"),
            "HTML entities must be escaped — got: $text",
        )
    }

    // ── No scanner call from read path (SC-05 / T-26-02) ──────────────────────

    @Test
    fun `proportions read does not invoke ProjectLanguageScanner scan`() {
        // VALIDATION 26-02-05 — the builder MUST NOT trigger a scan from the
        // status-line read path; proportions() is the read-only projection by
        // contract. A regression (e.g. calling scan(project) as a "warm" path)
        // would reintroduce EDT blocking on Settings open.
        mockkObject(ProjectLanguageScanner)
        every { ProjectLanguageScanner.scan(any()) } returns emptyMap()

        val project = stubProject("/tmp/prop-noscan-${System.nanoTime()}")
        every { ProjectLanguageDetector.proportions(project) } returns mapOf("kotlin" to 1_000L)

        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(project) }
        builder.currentProportionsTextForTest()
        builder.refreshProportionsLabelForTest()

        verify(exactly = 0) { ProjectLanguageScanner.scan(any()) }
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private fun stubProject(basePath: String): Project {
        val project = mockk<Project>()
        every { project.basePath } returns basePath
        every { project.isDefault } returns false
        every { project.isDisposed } returns false
        every { project.name } returns basePath.substringAfterLast('/')
        return project
    }
}
