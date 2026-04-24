package dev.ayuislands.settings.mappings

import com.intellij.openapi.project.Project
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ProjectLanguageDetector
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the defense-in-depth contract around [OverridesGroupBuilder.apply]:
 *
 *  - Persisted state is committed from the pending tables BEFORE the
 *    resolver/applicator/swap chain fires.
 *  - A transient failure in the apply chain (LafManager loss, UIManager race,
 *    project-swap service shutdown) MUST NOT skip the `storedProjects` /
 *    `storedLanguages` snapshot refresh or the pending-change `fireChanged()`
 *    broadcast below the try block.
 *  - After apply() returns, `isModified()` must read `false` for the
 *    just-committed rows — or the settings UI drifts into a "Apply is lit even
 *    after saving" loop that users can only break by closing the dialog.
 *
 * Red/green: remove the runCatching wrapper from `apply()` and this test
 * fails; remove the `storedProjects` snapshot line and this test fails.
 */
class OverridesGroupBuilderApplyTest {
    private lateinit var mappingsState: AccentMappingsState

    @BeforeTest
    fun setUp() {
        mappingsState = AccentMappingsState()
        val settings = mockk<AccentMappingsSettings>()
        every { settings.state } returns mappingsState
        mockkObject(AccentMappingsSettings.Companion)
        every { AccentMappingsSettings.getInstance() } returns settings

        mockkObject(ProjectLanguageDetector)
        every { ProjectLanguageDetector.dominant(any()) } returns null

        mockkObject(AyuVariant.Companion)
        mockkObject(AccentResolver)
        mockkObject(AccentApplicator)
        mockkObject(ProjectAccentSwapService.Companion)
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `apply() updates stored snapshot and fires changed when the apply chain throws`() {
        // Simulate the round-2 threat model: the resolver / applicator / swap-cache
        // chain explodes mid-apply (e.g. LafManager returning null on a shutdown
        // race). The pending-model snapshot is already persisted to mappingsState
        // by the time the chain runs, so the user's overrides ARE saved — what
        // we're locking here is the in-memory drift: storedProjects/Languages must
        // still advance and the pending-change listener must still fire, or the
        // settings UI will keep reporting "modified" on already-saved state.
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        every { AccentResolver.resolve(any(), any()) } returns "#FFCC66"
        every { AccentApplicator.apply(any()) } throws RuntimeException("LafManager boom")

        val builder = OverridesGroupBuilder()
        var listenerFired = 0
        builder.addPendingChangeListener { listenerFired += 1 }
        builder.seedPendingForTest(
            projects =
                listOf(
                    ProjectMapping(canonicalPath = "/tmp/apply-exc-a", displayName = "a", hex = "#111111"),
                ),
            languages =
                listOf(LanguageMapping(languageId = "kotlin", displayName = "Kotlin", hex = "#222222")),
        )

        builder.apply()

        // Persisted state reflects the pending snapshot (reachable because
        // `apply()` commits to state BEFORE entering the runCatching block).
        assertEquals(mapOf("/tmp/apply-exc-a" to "#111111"), mappingsState.projectAccents)
        assertEquals(mapOf("kotlin" to "#222222"), mappingsState.languageAccents)

        // storedProjects/Languages advanced past the pending model — `isModified()`
        // now reads `false` even though the apply chain threw. A regression that
        // short-circuited the snapshot refresh would leave `isModified()` true.
        assertFalse(
            builder.isModified(),
            "apply()'s runCatching must not skip the storedProjects/Languages refresh — " +
                "the settings UI would otherwise keep reporting pending changes on saved state",
        )

        // fireChanged() must still fire so any reactive label (e.g., the
        // "Currently active: ..." comment) updates off the newly committed
        // snapshot.
        assertTrue(
            listenerFired > 0,
            "addPendingChangeListener subscribers must be notified even when the " +
                "apply chain throws; got $listenerFired calls",
        )
    }

    @Test
    fun `apply() on happy path invokes the whole resolver-applicator-swap chain once`() {
        // Baseline that the runCatching wrapper doesn't silently convert the
        // success path into a swallow. One AccentApplicator.apply call, one
        // swap-service notification, isModified() returns false.
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        every { AccentResolver.resolve(any(), any()) } returns "#AABBCC"
        every { AccentApplicator.apply(any()) } returns true
        val swapService = mockk<ProjectAccentSwapService>(relaxed = true)
        every { ProjectAccentSwapService.getInstance() } returns swapService

        val stubProject = mockk<Project>(relaxed = true)
        val builder =
            OverridesGroupBuilder().apply {
                setParentProjectForTest(stubProject)
                seedPendingForTest(
                    projects =
                        listOf(
                            ProjectMapping(
                                canonicalPath = "/tmp/apply-ok-b",
                                displayName = "b",
                                hex = "#AABBCC",
                            ),
                        ),
                )
            }

        builder.apply()

        assertEquals(mapOf("/tmp/apply-ok-b" to "#AABBCC"), mappingsState.projectAccents)
        assertFalse(builder.isModified(), "stored snapshot must advance on happy path too")
        io.mockk.verify(exactly = 1) { AccentApplicator.apply("#AABBCC") }
        io.mockk.verify(exactly = 1) { swapService.notifyExternalApply("#AABBCC") }
    }

    @Test
    fun `apply() falls back to AccentApplicator resolveFocusedProject when parentProject is not bound`() {
        // Guards the second-tier project source inside apply(): when setParentProjectForTest
        // has not been called (e.g. Settings Apply reached here before the panel finished
        // binding the context project), the builder MUST route through the shared cascade —
        // NOT `ProjectManager.openProjects.firstOrNull`, which picks the enumeration-first
        // project and reproduces the multi-window status-label mismatch. A regression that
        // swaps resolveFocusedProject back to openProjects.firstOrNull would pass the two
        // tests above (parentProject is bound there) but fail this one.
        val focusedProject =
            mockk<Project>(relaxed = true) {
                every { isDisposed } returns false
                every { isDefault } returns false
            }
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        every { AccentApplicator.resolveFocusedProject() } returns focusedProject
        every { AccentResolver.resolve(focusedProject, AyuVariant.MIRAGE) } returns "#5CCFE6"
        every { AccentApplicator.apply(any()) } returns true
        val swapService = mockk<ProjectAccentSwapService>(relaxed = true)
        every { ProjectAccentSwapService.getInstance() } returns swapService

        val builder =
            OverridesGroupBuilder().apply {
                // Deliberately do NOT call setParentProjectForTest — exercise the fallback.
                seedPendingForTest(
                    projects =
                        listOf(
                            ProjectMapping(
                                canonicalPath = "/tmp/apply-fallback",
                                displayName = "fallback",
                                hex = "#5CCFE6",
                            ),
                        ),
                )
            }

        builder.apply()

        io.mockk.verify(exactly = 1) { AccentApplicator.resolveFocusedProject() }
        io.mockk.verify(exactly = 1) { AccentResolver.resolve(focusedProject, AyuVariant.MIRAGE) }
        io.mockk.verify(exactly = 1) { AccentApplicator.apply("#5CCFE6") }
    }
}
