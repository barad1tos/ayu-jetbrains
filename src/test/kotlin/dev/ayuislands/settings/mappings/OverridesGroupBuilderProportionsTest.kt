package dev.ayuislands.settings.mappings

import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBusConnection
import dev.ayuislands.accent.LanguageDetectionRules
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
import kotlin.test.assertNull
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

        // `buildRescanAffordanceLabel.mouseClicked` re-reads
        // `LicenseChecker.isLicensedOrGrace()` live on click as
        // defence-in-depth against a license that expires while Settings
        // is open. Without this mock the real `LicenseChecker` hits
        // `ApplicationManager.getApplication()` (null in unit tests) and
        // the click-dispatch tests fail. Default to licensed so the
        // click tests drive the rescan path; a dedicated
        // unlicensed-suppression spec flips it to false.
        mockkObject(dev.ayuislands.licensing.LicenseChecker)
        every {
            dev.ayuislands.licensing.LicenseChecker
                .isLicensedOrGrace()
        } returns true
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
        // Post-refinement, entry labels show only the percentage — language name
        // moved into the JBLabel.toolTipText. Both `.text` (icon+percent) and
        // `.toolTipText` (display name) are plain-text by default in Swing: neither
        // starts with `<html>`, so a Language whose displayName contains markup
        // renders literally with no HTML parsing.
        //
        // The security invariant is now: display names appear ONLY in tooltip,
        // and that tooltip keeps the raw `<script>…</script>` substring intact
        // (proof of no interpretation). No JBLabel text or tooltip may open
        // with `<html>` — that would flip Swing into the HTML-parsing branch.
        val project = stubProject("/tmp/prop-xss-${System.nanoTime()}")
        every { ProjectLanguageDetector.proportions(project) } returns
            mapOf("<script>evil</script>" to 1_000L)

        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(project) }

        val labels = builder.proportionsPanelLabelsForTest()
        val allTooltips = labels.mapNotNull { it.third }
        val renderedTooltips = allTooltips.joinToString(" | ")
        assertTrue(
            allTooltips.any { it.contains("<script>evil</script>") },
            "one tooltip must carry the literal <script>…</script> substring — got: $renderedTooltips",
        )
        // No text or tooltip may start with <html> — that would trigger Swing
        // HTML parsing.
        val allStrings = labels.flatMap { listOf(it.second) + listOfNotNull(it.third) }
        assertFalse(
            allStrings.any { it.startsWith("<html", ignoreCase = true) },
            "no label text or tooltip may start with <html>. Strings: $allStrings",
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
            listOf(
                "Detected:",
                "78%",
                "·",
                "15%",
                "·",
                "4%",
                "·",
                "other 3%",
                "·",
                "Rescan",
            ),
            texts,
            "icon row opens with the Detected prefix, then alternates percent-only " +
                "entries and standalone middle-dot separator labels — the final bucket " +
                "is labeled 'other' inline, and a trailing Rescan affordance sits at " +
                "the end so users can force a re-detection without leaving the row",
        )
        // Prefix and separators carry no icon. Every named-language entry tries
        // to resolve an IDE-platform icon; the "other" bucket entry and the
        // trailing Rescan label have no associated language and therefore no icon.
        assertEquals(null, labels.first().first, "the prefix label carries no icon")
        assertEquals(null, labels.last().first, "the Rescan affordance carries no icon")
    }

    @Test
    fun `panel renders polyglot JBLabel with info icon and trailing Rescan when detector returns null`() {
        val project = stubProject("/tmp/prop-polyglot-row-${System.nanoTime()}")
        every { ProjectLanguageDetector.proportions(project) } returns null

        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(project) }

        val labels = builder.proportionsPanelLabelsForTest()
        assertEquals(
            3,
            labels.size,
            "polyglot state renders the main polyglot label, a · separator, and the Rescan affordance",
        )
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
        assertEquals(
            OverridesGroupBuilder.PROPORTIONS_SEPARATOR.toString(),
            labels[1].second,
            "· separator sits between the polyglot copy and the Rescan affordance",
        )
        assertEquals(
            OverridesGroupBuilder.RESCAN_LABEL,
            labels.last().second,
            "Rescan affordance MUST appear in the polyglot path — otherwise users " +
                "have no escape hatch to force a re-detection from the polyglot state",
        )
    }

    @Test
    fun `panel renders polyglot state with trailing Rescan when detector returns empty weights`() {
        // An empty-but-non-null weights map is the "scan ran, nothing matched"
        // state — the structured formatter returns an empty list, and the panel
        // must fall through to the polyglot copy path rather than rendering zero
        // labels (which would leave an invisible row). The Rescan affordance
        // still appears at the trailing end.
        val project = stubProject("/tmp/prop-empty-${System.nanoTime()}")
        every { ProjectLanguageDetector.proportions(project) } returns emptyMap()

        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(project) }

        val labels = builder.proportionsPanelLabelsForTest()
        assertEquals(3, labels.size)
        assertEquals(OverridesGroupBuilder.POLYGLOT_COPY, labels.first().second)
        assertEquals(OverridesGroupBuilder.RESCAN_LABEL, labels.last().second)
    }

    @Test
    fun `focus-swap rebuilds panel children with the new project's weights`() {
        val projectA = stubProject("/tmp/prop-row-a-${System.nanoTime()}")
        val projectB = stubProject("/tmp/prop-row-b-${System.nanoTime()}")
        every { ProjectLanguageDetector.proportions(projectA) } returns mapOf("kotlin" to 1_000L)
        every { ProjectLanguageDetector.proportions(projectB) } returns mapOf("python" to 1_000L)

        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(projectA) }
        val firstTexts = builder.proportionsPanelLabelsForTest().map { it.second }
        assertEquals(listOf("Detected:", "100%", "·", "Rescan"), firstTexts)

        builder.setParentProjectForTest(projectB)
        val secondTexts = builder.proportionsPanelLabelsForTest().map { it.second }
        assertEquals(
            listOf("Detected:", "100%", "·", "Rescan"),
            secondTexts,
            "refresh must rebuild children from projectB's weights — both project A " +
                "and project B are single-language so the rendered text collapses to " +
                "Detected: + 100%; the Rescan affordance is always appended so the " +
                "difference between projects shows up in the resolved icon, not text",
        )
    }

    @Test
    fun `panel repopulate swallows an iconForLanguageId throw without propagating`() {
        // Defense-in-depth lock: `populateProportionsPanel` wraps its entire
        // body in runCatchingPreservingCancellation because a third-party
        // Language plugin can throw from `associatedFileType.icon`. If the
        // wrapper is ever removed, the EDT callback would bubble an exception
        // that the builder's pending-change listener chain and `reset()` also
        // dispatch through — breaking the entire Overrides group, not just
        // the proportions row. Red/green: force iconForLanguageId to throw and
        // assert the seam still returns (polyglot copy, previous state) rather
        // than surfacing the exception.
        mockkObject(LanguageDetectionRules)
        every { LanguageDetectionRules.pickDisplayEntries(any(), any()) } returns
            listOf(LanguageDetectionRules.DisplayEntry(id = "kotlin", label = "Kotlin", percent = 100))
        every { LanguageDetectionRules.iconForLanguageId(any()) } throws RuntimeException("plugin boom")

        val project = stubProject("/tmp/prop-throw-${System.nanoTime()}")
        every { ProjectLanguageDetector.proportions(project) } returns mapOf("kotlin" to 1_000L)
        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(project) }

        // Must not throw — the seam rebuilds the panel under runCatching.
        // Load-bearing claim: with the staging-panel atomic swap strategy from
        // round 3, a mid-render throw leaves the LIVE panel untouched. The
        // first invocation of `proportionsPanelLabelsForTest()` seeds an empty
        // panel and then attempts to render — the render fails, so the live
        // panel stays empty (no partially-populated children).
        val labels = builder.proportionsPanelLabelsForTest()
        assertEquals(
            0,
            labels.size,
            "mid-render throw must leave the live panel untouched, not half-populated; " +
                "got ${labels.size} labels: ${labels.map { it.second }}",
        )
    }

    // ── Rescan affordance (Phase 29) ──────────────────────────────────────────

    @Test
    fun `Rescan label triggers ProjectLanguageDetector rescan on click`() {
        // User-space contract: clicking "Rescan" in the proportions row MUST
        // call ProjectLanguageDetector.rescan(project). Any other binding
        // (invalidate alone, dominant alone) would miss part of the
        // invalidate + schedule + publish chain and the UI would stay stale.
        val project = stubProject("/tmp/rescan-click-${System.nanoTime()}")
        every { ProjectLanguageDetector.proportions(project) } returns mapOf("kotlin" to 1_000L)
        every { ProjectLanguageDetector.rescan(project) } returns Unit

        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(project) }
        val rescanComponent = findRescanComponent(builder)

        // Simulate a real mouseClicked MouseEvent — the label swallows the
        // event without consulting x/y, so a zero-location event is fine.
        val event =
            java.awt.event.MouseEvent(
                rescanComponent,
                java.awt.event.MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                0,
                0,
                1,
                false,
            )
        rescanComponent.mouseListeners.forEach { it.mouseClicked(event) }

        verify(exactly = 1) { ProjectLanguageDetector.rescan(project) }
    }

    @Test
    fun `Rescan label carries a tooltip clarifying the affordance`() {
        val project = stubProject("/tmp/rescan-tooltip-${System.nanoTime()}")
        every { ProjectLanguageDetector.proportions(project) } returns mapOf("kotlin" to 1_000L)

        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(project) }

        val tooltip = builder.proportionsPanelLabelsForTest().last().third
        assertEquals(
            OverridesGroupBuilder.RESCAN_TOOLTIP,
            tooltip,
            "Rescan tooltip explains what the click does without bloating the visible text",
        )
    }

    @Test
    fun `Rescan label is NOT appended when parentProject is null`() {
        // Settings opened with no focused project: ProjectLanguageDetector.rescan
        // would have nowhere to dispatch, so the click would be a dead-end. The
        // affordance is deliberately suppressed in that state.
        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(null) }

        val labels = builder.proportionsPanelLabelsForTest()
        assertEquals(
            1,
            labels.size,
            "with no parentProject, only the polyglot copy renders — no separator, no Rescan affordance",
        )
        assertEquals(OverridesGroupBuilder.POLYGLOT_COPY, labels.first().second)
    }

    @Test
    fun `Rescan label hover flips foreground to currentAccent then reverts on exit`() {
        // Hover contract: muted foreground by default (blends with the
        // detected-proportions row), currently-applied accent on hover (signals
        // which color the click will influence). Locks the mouseEntered /
        // mouseExited round-trip so a regression that drops either branch gets
        // caught — e.g. a refactor that leaves the label permanently accent-tinted
        // after the first hover.
        val project = stubProject("/tmp/rescan-hover-${System.nanoTime()}")
        every { ProjectLanguageDetector.proportions(project) } returns mapOf("kotlin" to 1_000L)

        val mirage = dev.ayuislands.accent.AyuVariant.MIRAGE
        mockkObject(dev.ayuislands.accent.AyuVariant.Companion)
        every {
            dev.ayuislands.accent.AyuVariant
                .detect()
        } returns mirage
        mockkObject(dev.ayuislands.accent.AccentResolver)
        every {
            dev.ayuislands.accent.AccentResolver
                .resolve(project, mirage)
        } returns "#5CCFE6"

        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(project) }
        val rescan = findRescanComponent(builder)
        val subdued = rescan.foreground

        val enter =
            java.awt.event.MouseEvent(rescan, java.awt.event.MouseEvent.MOUSE_ENTERED, 0L, 0, 0, 0, 0, false)
        val exit =
            java.awt.event.MouseEvent(rescan, java.awt.event.MouseEvent.MOUSE_EXITED, 0L, 0, 0, 0, 0, false)
        ourMouseAdapters(rescan).forEach { it.mouseEntered(enter) }
        val hoverColor = rescan.foreground
        ourMouseAdapters(rescan).forEach { it.mouseExited(exit) }
        val restoredColor = rescan.foreground

        val expectedAccent =
            com.intellij.ui.ColorUtil
                .fromHex("#5CCFE6")
        assertEquals(expectedAccent, hoverColor, "mouseEntered must flip foreground to the resolved accent color")
        assertEquals(subdued, restoredColor, "mouseExited must restore the original muted foreground")
    }

    @Test
    fun `Rescan label hover falls back to subdued when AccentResolver throws`() {
        // Defensive lock on the runCatchingPreservingCancellation wrap
        // inside the hover handler. If `AccentResolver.resolve` throws
        // (malformed stored hex, plugin-unload race on EDT), the handler
        // must fall back to the subdued foreground rather than propagate
        // an uncaught EDT exception that would break the entire
        // Settings row.
        val project = stubProject("/tmp/rescan-hover-throw-${System.nanoTime()}")
        every { ProjectLanguageDetector.proportions(project) } returns mapOf("kotlin" to 1_000L)

        val mirage = dev.ayuislands.accent.AyuVariant.MIRAGE
        mockkObject(dev.ayuislands.accent.AyuVariant.Companion)
        every {
            dev.ayuislands.accent.AyuVariant
                .detect()
        } returns mirage
        mockkObject(dev.ayuislands.accent.AccentResolver)
        every {
            dev.ayuislands.accent.AccentResolver
                .resolve(project, mirage)
        } throws
            RuntimeException("malformed hex")

        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(project) }
        val rescan = findRescanComponent(builder)
        val subdued = rescan.foreground

        val enter =
            java.awt.event.MouseEvent(rescan, java.awt.event.MouseEvent.MOUSE_ENTERED, 0L, 0, 0, 0, 0, false)
        ourMouseAdapters(rescan).forEach { it.mouseEntered(enter) }

        assertEquals(
            subdued,
            rescan.foreground,
            "a throwing AccentResolver must be contained by the hover runCatching; foreground stays subdued",
        )
    }

    @Test
    fun `Rescan click is a no-op when license flips to unlicensed mid-session`() {
        // Defence-in-depth lock on the click-time license re-check. The
        // affordance was built while licensed, but a mid-session expiry
        // (grace roll-off, LicensingFacade revocation) must prevent the
        // click from firing a rescan.
        val project = stubProject("/tmp/rescan-click-expiry-${System.nanoTime()}")
        every { ProjectLanguageDetector.proportions(project) } returns mapOf("kotlin" to 1_000L)
        every { ProjectLanguageDetector.rescan(project) } returns Unit

        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(project) }
        val rescan = findRescanComponent(builder)

        // Flip license AFTER the affordance is wired — simulates mid-session expiry.
        every {
            dev.ayuislands.licensing.LicenseChecker
                .isLicensedOrGrace()
        } returns false

        val click =
            java.awt.event.MouseEvent(
                rescan,
                java.awt.event.MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                0,
                0,
                1,
                false,
            )
        ourMouseAdapters(rescan).forEach { it.mouseClicked(click) }

        verify(exactly = 0) { ProjectLanguageDetector.rescan(project) }
    }

    @Test
    fun `Rescan label hover falls back to subdued when variant detect returns null`() {
        // Defensive path: no Ayu theme active → AyuVariant.detect returns null,
        // currentAccentColorFor hits the `?: return@... fallback` branch, and
        // the hover foreground stays on the subdued base color instead of
        // defaulting to an arbitrary Color or throwing.
        val project = stubProject("/tmp/rescan-hover-null-${System.nanoTime()}")
        every { ProjectLanguageDetector.proportions(project) } returns mapOf("kotlin" to 1_000L)

        mockkObject(dev.ayuislands.accent.AyuVariant.Companion)
        every {
            dev.ayuislands.accent.AyuVariant
                .detect()
        } returns null

        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(project) }
        val rescan = findRescanComponent(builder)
        val subdued = rescan.foreground

        val enter =
            java.awt.event.MouseEvent(rescan, java.awt.event.MouseEvent.MOUSE_ENTERED, 0L, 0, 0, 0, 0, false)
        ourMouseAdapters(rescan).forEach { it.mouseEntered(enter) }

        assertEquals(subdued, rescan.foreground, "null variant must keep the subdued foreground")
    }

    @Test
    fun `Rescan label uses HAND cursor to signal interactivity`() {
        val project = stubProject("/tmp/rescan-cursor-${System.nanoTime()}")
        every { ProjectLanguageDetector.proportions(project) } returns mapOf("kotlin" to 1_000L)

        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(project) }
        val rescan = findRescanComponent(builder)

        assertEquals(
            java.awt.Cursor.HAND_CURSOR,
            rescan.cursor.type,
            "HAND cursor is the discoverability affordance — the label is visually muted, " +
                "so the cursor is one of the few in-panel signals that this text is clickable",
        )
    }

    /**
     * Return only the MouseAdapter listeners that the Ayu Islands plugin
     * installed — filters out Swing's internal `ToolTipManager` listener,
     * which auto-registers when `toolTipText` is set and starts an undisposed
     * shared Timer when we dispatch `mouseEntered` directly on the listener.
     * IntelliJ's `SwingTimerWatcherExtension` fails the test if any Timer
     * remains after teardown.
     */
    private fun ourMouseAdapters(component: javax.swing.JComponent): List<java.awt.event.MouseListener> =
        component.mouseListeners.filter { it.javaClass.name.startsWith("dev.ayuislands") }

    /**
     * Reflection seam: install a mock `MessageBusConnection` into the
     * builder's private `detectionConnection` field so disposal contract
     * tests can drive `dispose()` without spinning up a real
     * `Project.messageBus`. Production sets the same field via
     * `buildGroup`; tests bypass Swing and the platform MessageBus graph.
     */
    private fun installDetectionConnection(
        builder: OverridesGroupBuilder,
        connection: MessageBusConnection?,
    ) {
        builder.javaClass
            .getDeclaredField("detectionConnection")
            .apply { isAccessible = true }
            .set(builder, connection)
    }

    private fun readDetectionConnection(builder: OverridesGroupBuilder): MessageBusConnection? =
        builder.javaClass
            .getDeclaredField("detectionConnection")
            .apply { isAccessible = true }
            .get(builder) as MessageBusConnection?

    /**
     * Resolve the Rescan JBLabel inside the builder's proportions panel by
     * matching on the companion-level literal — keeps the helper robust against
     * cosmetic label rewordings as long as the literal stays in sync.
     */
    private fun findRescanComponent(builder: OverridesGroupBuilder): javax.swing.JComponent {
        val panel =
            builder.javaClass
                .getDeclaredField("proportionsPanel")
                .apply { isAccessible = true }
                .get(builder) as? javax.swing.JPanel
                ?: javax.swing.JPanel().also {
                    // Mirror the seam used by proportionsPanelLabelsForTest(): lazily
                    // build the panel before reflecting into it.
                    builder.javaClass
                        .getDeclaredField("proportionsPanel")
                        .apply { isAccessible = true }
                        .set(builder, it)
                }
        // Force one render pass so the Rescan label is in the tree.
        builder.proportionsPanelLabelsForTest()
        return panel.components
            .filterIsInstance<com.intellij.ui.components.JBLabel>()
            .firstOrNull { it.text == OverridesGroupBuilder.RESCAN_LABEL }
            ?: error(
                "Rescan JBLabel missing from proportions panel — components were " +
                    "${panel.components.map { (it as? com.intellij.ui.components.JBLabel)?.text }}",
            )
    }

    @Test
    fun `Rescan affordance is suppressed when the user is unlicensed`() {
        // Rescan is premium by project policy. The inline affordance must
        // not appear for unlicensed users even though the proportions row
        // itself stays free to read. The @TestOnly seam mirrors the
        // `buildGroup` license read with an explicit `licensed` flag;
        // passing `false` locks the unlicensed-suppression contract.
        val project = stubProject("/tmp/rescan-unlicensed-${System.nanoTime()}")
        every { ProjectLanguageDetector.proportions(project) } returns mapOf("kotlin" to 1_000L)

        val builder = OverridesGroupBuilder().apply { setParentProjectForTest(project, licensed = false) }
        val texts = builder.proportionsPanelLabelsForTest().map { it.second }

        assertEquals(
            listOf("Detected:", "100%"),
            texts,
            "unlicensed users must see the proportions row without the trailing Rescan affordance",
        )
    }

    // ── detection subscription lifecycle (leak guard) ─────────────────────────

    @Test
    fun `dispose disconnects the live detection subscription`() {
        // Lock the leak-fix: when `buildGroup` installed a MessageBus
        // subscription, `dispose()` must call `disconnect()` on it and
        // null the field so the next Settings open re-acquires a fresh
        // connection instead of piling onto the stale one.
        val builder = OverridesGroupBuilder()
        val connection = mockk<MessageBusConnection>(relaxed = true)
        installDetectionConnection(builder, connection)

        builder.dispose()

        verify(exactly = 1) { connection.disconnect() }
        assertNull(
            readDetectionConnection(builder),
            "dispose() must null the field so the next wiring doesn't observe a disconnected handle",
        )
    }

    @Test
    fun `dispose is idempotent across repeated calls`() {
        // The Configurable's disposeUIResources may fire more than once
        // under pathological shutdown paths. A second dispose() must be
        // a no-op — specifically it must NOT call disconnect() on an
        // already-null field (NPE) and must NOT double-disconnect.
        val builder = OverridesGroupBuilder()
        val connection = mockk<MessageBusConnection>(relaxed = true)
        installDetectionConnection(builder, connection)

        builder.dispose()
        builder.dispose()

        verify(exactly = 1) { connection.disconnect() }
    }

    @Test
    fun `dispose is a no-op before buildGroup wires up the subscription`() {
        // The Configurable may invoke disposeUIResources on a builder whose
        // Settings panel was never actually painted (user dismissed the dialog
        // before the tab rendered). `dispose()` must tolerate the pre-wired
        // state without NPE and must remain idempotent across multiple
        // teardown calls.
        val builder = OverridesGroupBuilder()

        builder.dispose()
        builder.dispose()
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
