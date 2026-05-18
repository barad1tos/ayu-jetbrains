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
import dev.ayuislands.accent.color.AccentHsl
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.rotation.HslColor
import dev.ayuislands.settings.mappings.ProjectAccentSwapService
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks [DarkerAccentAction]: Pattern J gate, BGT thread, [AccentHsl.darken]
 * + apply chain (Pattern D), and the no-op-at-clamp branch symmetric with
 * `LighterAccentActionTest`. Tests 13, 18, 29, 30 per `48-03-PLAN.md`.
 */
class DarkerAccentActionTest {
    private val mockApp = mockk<Application>(relaxed = true)
    private val mockSwap = mockk<ProjectAccentSwapService>(relaxed = true)
    private val mockProject = mockk<Project>(relaxed = true)

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
        every { AccentResolver.resolve(any(), any()) } returns "#FFCC66"

        mockkStatic(ApplicationManager::class)
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
        assertEquals(ActionUpdateThread.BGT, DarkerAccentAction().getActionUpdateThread())
    }

    @Test
    fun `update Pattern J two-level gate exhaustive (T,T) (F,T) (T,F) (F,F)`() {
        // CRIT-7 — full 4-case truth table; (F,F) locks AND-not-OR.
        val action = DarkerAccentAction()
        val event = newEvent()

        every { AyuVariant.isAyuActive() } returns true
        every { LicenseChecker.isLicensedOrGrace() } returns true
        action.update(event)
        assertTrue(event.presentation.isEnabledAndVisible, "(T,T) must enable")

        every { AyuVariant.isAyuActive() } returns false
        every { LicenseChecker.isLicensedOrGrace() } returns true
        action.update(event)
        assertFalse(event.presentation.isEnabledAndVisible, "(F,T) inactive variant must disable")

        every { AyuVariant.isAyuActive() } returns true
        every { LicenseChecker.isLicensedOrGrace() } returns false
        action.update(event)
        assertFalse(event.presentation.isEnabledAndVisible, "(T,F) unlicensed must disable")

        every { AyuVariant.isAyuActive() } returns false
        every { LicenseChecker.isLicensedOrGrace() } returns false
        action.update(event)
        assertFalse(event.presentation.isEnabledAndVisible, "(F,F) both off must disable — locks AND vs OR")
    }

    @Test
    fun `actionPerformed feeds AccentHsl darken output into applyFromHexString (Pattern D gated)`() {
        // Test 29
        val expected = AccentHsl.darken("#FFCC66")
        DarkerAccentAction().actionPerformed(newEvent())
        verify(exactly = 1) { AccentApplicator.applyFromHexString(expected) }
        verify(exactly = 1) { mockSwap.notifyExternalApply(expected) }
    }

    @Test
    fun `actionPerformed at MIN_LIGHTNESS clamp still calls applyFromHexString with the unchanged hex`() {
        // Test 30
        val floorHex = HslColor.toHex(0f, 0f, AccentHsl.MIN_LIGHTNESS)
        every { AccentResolver.resolve(any(), any()) } returns floorHex
        DarkerAccentAction().actionPerformed(newEvent())
        verify(exactly = 1) { AccentApplicator.applyFromHexString(floorHex) }
    }
}
