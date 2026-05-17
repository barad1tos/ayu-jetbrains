package dev.ayuislands.accent.toolbar.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.mappings.AccentMappingsSettings
import dev.ayuislands.settings.mappings.AccentMappingsState
import dev.ayuislands.settings.mappings.ProjectAccentSwapService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks [PinAccentAction]'s Pattern J two-level gate (`isAyuActive && licensed`),
 * `AccentMappingsState.projectAccents` writer path (RESEARCH §6 — Phase 41 split
 * deferred), and Pattern D Boolean gate on `notifyExternalApply`. Tests 10..14
 * (per-action Pattern J), 15..19 (per-action BGT), 20..24 (Pin-specific) in
 * `48-03-PLAN.md` `<behavior>`.
 */
class PinAccentActionTest {
    private val mockApp = mockk<Application>(relaxed = true)
    private val mockSettings = mockk<AccentMappingsSettings>(relaxed = true)
    private val state = AccentMappingsState()
    private val mockSwap = mockk<ProjectAccentSwapService>(relaxed = true)
    private val mockProject =
        mockk<Project> {
            every { name } returns "test-project"
            every { isDefault } returns false
            every { isDisposed } returns false
        }

    @BeforeTest
    fun setUp() {
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.isAyuActive() } returns true
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE

        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true

        mockkObject(AccentApplicator)
        every { AccentApplicator.resolveFocusedProject() } returns mockProject
        every { AccentApplicator.applyFromHexString(any()) } returns true

        mockkObject(AccentResolver)
        every { AccentResolver.resolve(any(), any()) } returns "#7F52FF"
        every { AccentResolver.projectKey(any()) } returns "/path/to/project"

        mockkObject(AccentMappingsSettings.Companion)
        every { AccentMappingsSettings.getInstance() } returns mockSettings
        every { mockSettings.state } returns state

        // ProjectAccentSwapService.getInstance() delegates to
        // ApplicationManager.getApplication().getService(...) — mock the chain like
        // QuickSwitcherAccentGridTest does.
        io.mockk.mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns mockApp
        every { mockApp.getService(ProjectAccentSwapService::class.java) } returns mockSwap
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    private fun newEvent(): AnActionEvent {
        val event = mockk<AnActionEvent>(relaxed = true)
        every { event.presentation } returns Presentation()
        return event
    }

    @Test
    fun `getActionUpdateThread is BGT`() {
        // Test 15
        assertEquals(ActionUpdateThread.BGT, PinAccentAction().getActionUpdateThread())
    }

    @Test
    fun `update enables and shows action only when BOTH AyuActive AND licensed (Pattern J)`() {
        // Test 10 — exhaustive two-conjunct gate verification.
        val action = PinAccentAction()
        val event = newEvent()

        every { AyuVariant.isAyuActive() } returns true
        every { LicenseChecker.isLicensedOrGrace() } returns true
        action.update(event)
        assertTrue(event.presentation.isEnabledAndVisible, "Active + licensed must enable")

        every { AyuVariant.isAyuActive() } returns false
        every { LicenseChecker.isLicensedOrGrace() } returns true
        action.update(event)
        assertFalse(event.presentation.isEnabledAndVisible, "Inactive variant must disable")

        every { AyuVariant.isAyuActive() } returns true
        every { LicenseChecker.isLicensedOrGrace() } returns false
        action.update(event)
        assertFalse(event.presentation.isEnabledAndVisible, "Unlicensed must disable")

        every { AyuVariant.isAyuActive() } returns false
        every { LicenseChecker.isLicensedOrGrace() } returns false
        action.update(event)
        assertFalse(event.presentation.isEnabledAndVisible, "Both off must disable")
    }

    @Test
    fun `actionPerformed writes the resolved hex to projectAccents under the projectKey`() {
        // Test 20
        PinAccentAction().actionPerformed(newEvent())
        assertEquals("#7F52FF", state.projectAccents["/path/to/project"])
    }

    @Test
    fun `actionPerformed calls notifyExternalApply only when applyFromHexString returns true (Pattern D)`() {
        // Test 21 — Pattern D Boolean gate
        every { AccentApplicator.applyFromHexString("#7F52FF") } returns true
        PinAccentAction().actionPerformed(newEvent())
        verify(exactly = 1) { mockSwap.notifyExternalApply("#7F52FF") }

        // When apply rejects, swap must NOT be published.
        io.mockk.clearMocks(mockSwap)
        every { AccentApplicator.applyFromHexString("#7F52FF") } returns false
        PinAccentAction().actionPerformed(newEvent())
        verify(exactly = 0) { mockSwap.notifyExternalApply(any()) }
    }

    @Test
    fun `actionPerformed is a no-op when resolveFocusedProject returns null`() {
        // Test 22
        every { AccentApplicator.resolveFocusedProject() } returns null
        PinAccentAction().actionPerformed(newEvent())
        assertTrue(state.projectAccents.isEmpty(), "Must not write state when no focused project")
        verify(exactly = 0) { AccentApplicator.applyFromHexString(any()) }
    }

    @Test
    fun `actionPerformed is a no-op when projectKey returns null (defensive)`() {
        // Test 23
        every { AccentResolver.projectKey(any()) } returns null
        PinAccentAction().actionPerformed(newEvent())
        assertTrue(state.projectAccents.isEmpty(), "Must not write state when projectKey null")
        verify(exactly = 0) { AccentApplicator.applyFromHexString(any()) }
    }

    @Test
    fun `source carries the Phase 41 split TODO marker (hand-off lock)`() {
        // Test 24 — Phase 41 hand-off marker. Read the source file at test time
        // so a future deletion of the marker fails the test, surfacing the
        // integration point for the Phase 41 planner.
        val source =
            Files.readString(
                Paths.get(
                    "src/main/kotlin/dev/ayuislands/accent/toolbar/actions/PinAccentAction.kt",
                ),
            )
        assertTrue(
            source.contains("TODO Phase 41"),
            "PinAccentAction must keep the `TODO Phase 41` marker for the integration hand-off",
        )
    }
}
