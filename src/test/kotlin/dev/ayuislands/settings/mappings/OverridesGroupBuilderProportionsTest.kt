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
 * [OverridesGroupBuilder.proportionsPanelLabelsForTest]) — mirrors the
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

        assertEquals(
            "Detected in this project: Python (100%)",
            builder.currentProportionsTextForTest(),
        )
    }

    // ── HTML-safety (T-26-01, post icon-row redesign) ─────────────────────────

    @Test
    fun `JBLabel renders malicious display name literally without HTML interpretation`() {
        // VALIDATION 26-02-04 — threat T-26-01 under the icon-row redesign.
        // Previously the status line was a JEditorPane (text/html), so a third-party
        // Language.displayName containing markup would be interpreted. The redesign
        // uses JBLabel with plain text (no `<html>` prefix) — Swing renders the
        // string literally with no HTML parsing. This test pins that contract: the
        // raw `<script>` substring must appear verbatim in the JBLabel.text
        // (proving no interpretation) and therefore no escape-xml transformation
        // is needed on this path.
        val project = stubProject("/tmp/prop-xss-${System.nanoTime()}")
        every { ProjectLanguageDetector.proportions(project) } returns
            mapOf("<script>evil</script>" to 1_000L)

        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(project) }

        val labels = builder.proportionsPanelLabelsForTest()
        val rendered = labels.joinToString(" | ") { it.second }
        assertTrue(
            labels.any { it.second.contains("<script>evil</script>") },
            "JBLabel must carry the literal <script>…</script> substring — got: $rendered",
        )
        // And no JBLabel starts with `<html>` — that would trigger HTML parsing.
        assertFalse(
            labels.any { it.second.startsWith("<html", ignoreCase = true) },
            "no label may start with <html> — that would enable HTML interpretation. Labels: $rendered",
        )
    }

    // ── Panel structure (icon row + polyglot state) ───────────────────────────

    @Test
    fun `panel renders one JBLabel per display entry in descending order with icons`() {
        // Icon-row redesign: the proportions status row is now a FlowLayout panel
        // containing one JBLabel per DisplayEntry. Each named-language label
        // carries the IDE's platform icon for that language (or null if the
        // language isn't registered in this IDE). The "other" bucket has a null
        // icon and literal "other N%" text.
        val project = stubProject("/tmp/prop-row-${System.nanoTime()}")
        every { ProjectLanguageDetector.proportions(project) } returns
            mapOf(
                "kotlin" to 780L,
                "java" to 150L,
                "scala" to 40L,
                "groovy" to 30L,
            )

        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(project) }

        val labels = builder.proportionsPanelLabelsForTest()
        val texts = labels.map { it.second }
        assertEquals(
            listOf("Kotlin 78%", "Java 15%", "Scala 4%", "other 3%"),
            texts,
            "icon-row must contain top-3 languages plus the other bucket, in weight-descending order",
        )
        // Scala and Groovy may not be registered in the test JVM's Language index —
        // icon can be null for any named entry. The "other" entry (last) must
        // always have a null icon since there's no language to resolve.
        assertEquals(
            null,
            labels.last().first,
            "the 'other' bucket entry must have a null icon",
        )
    }

    @Test
    fun `panel renders single polyglot JBLabel with info icon when detector returns null`() {
        val project = stubProject("/tmp/prop-polyglot-row-${System.nanoTime()}")
        every { ProjectLanguageDetector.proportions(project) } returns null

        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(project) }

        val labels = builder.proportionsPanelLabelsForTest()
        assertEquals(1, labels.size, "polyglot state renders exactly one label")
        assertEquals(
            OverridesGroupBuilder.POLYGLOT_COPY,
            labels.first().second,
            "polyglot label carries the exact D-05 copy",
        )
        assertEquals(
            com.intellij.icons.AllIcons.General.Information,
            labels.first().first,
            "polyglot label uses AllIcons.General.Information so the state is visually distinct from the icon row",
        )
    }

    @Test
    fun `panel renders polyglot state when detector returns empty weights`() {
        // An empty-but-non-null weights map is the "scan ran, nothing matched"
        // state — the structured formatter returns an empty list, and the panel
        // must fall through to the polyglot copy path rather than rendering zero
        // labels (which would leave an invisible row).
        val project = stubProject("/tmp/prop-empty-${System.nanoTime()}")
        every { ProjectLanguageDetector.proportions(project) } returns emptyMap()

        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(project) }

        val labels = builder.proportionsPanelLabelsForTest()
        assertEquals(1, labels.size)
        assertEquals(OverridesGroupBuilder.POLYGLOT_COPY, labels.first().second)
    }

    @Test
    fun `focus-swap rebuilds panel children with the new project's weights`() {
        val projectA = stubProject("/tmp/prop-row-a-${System.nanoTime()}")
        val projectB = stubProject("/tmp/prop-row-b-${System.nanoTime()}")
        every { ProjectLanguageDetector.proportions(projectA) } returns mapOf("kotlin" to 1_000L)
        every { ProjectLanguageDetector.proportions(projectB) } returns mapOf("python" to 1_000L)

        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(projectA) }
        val firstTexts = builder.proportionsPanelLabelsForTest().map { it.second }
        assertEquals(listOf("Kotlin 100%"), firstTexts)

        builder.setParentProjectForTest(projectB)
        val secondTexts = builder.proportionsPanelLabelsForTest().map { it.second }
        assertEquals(
            listOf("Python 100%"),
            secondTexts,
            "refresh must rebuild children from projectB's weights, not leak projectA's Kotlin entry",
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
        builder.proportionsPanelLabelsForTest()

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
