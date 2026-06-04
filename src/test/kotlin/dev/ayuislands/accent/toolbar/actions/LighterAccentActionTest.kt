package dev.ayuislands.accent.toolbar.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentHex
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
 * Locks [LighterAccentAction]: Pattern J gate, BGT thread, [AccentHsl.lighten]
 * + apply chain (Pattern D), and the no-op-at-clamp branch (still apply, do
 * not skip — the balloon hint is a future deliverable).
 */
class LighterAccentActionTest {
    private val mockApp = mockk<Application>(relaxed = true)
    private val mockSwap = mockk<ProjectAccentSwapService>(relaxed = true)
    private val mockProject = mockk<Project>(relaxed = true)

    @BeforeTest
    fun setUp() {
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.isAyuActive() } returns true
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        mockkObject(AccentContext.Companion)
        every { AccentContext.isQuickSwitcherActive() } returns true
        every { AccentContext.detectQuickSwitcher() } returns AccentContext.Ayu(AyuVariant.MIRAGE)

        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true

        mockkObject(AccentApplicator)
        every { AccentApplicator.resolveFocusedProject() } returns mockProject
        every { AccentApplicator.applyFromHexString(any()) } returns true

        mockkObject(AccentResolver)
        every { AccentResolver.resolve(any(), any<AccentContext>()) } returns "#FFCC66"
        every { AccentResolver.resolve(any(), any<AyuVariant>()) } returns "#FFCC66"

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
        assertEquals(ActionUpdateThread.BGT, LighterAccentAction().getActionUpdateThread())
    }

    @Test
    fun `update Pattern J two-level gate exhaustive (T,T) (F,T) (T,F) (F,F)`() {
        // Full 4-case truth table; (F,F) locks AND-not-OR.
        val action = LighterAccentAction()
        val event = newEvent()

        every { AccentContext.isQuickSwitcherActive() } returns true
        every { LicenseChecker.isLicensedOrGrace() } returns true
        action.update(event)
        assertTrue(event.presentation.isEnabledAndVisible, "(T,T) must enable")

        every { AccentContext.isQuickSwitcherActive() } returns false
        every { LicenseChecker.isLicensedOrGrace() } returns true
        action.update(event)
        assertFalse(event.presentation.isEnabledAndVisible, "(F,T) inactive variant must disable")

        every { AccentContext.isQuickSwitcherActive() } returns true
        every { LicenseChecker.isLicensedOrGrace() } returns false
        action.update(event)
        assertFalse(event.presentation.isEnabledAndVisible, "(T,F) unlicensed must disable")

        every { AccentContext.isQuickSwitcherActive() } returns false
        every { LicenseChecker.isLicensedOrGrace() } returns false
        action.update(event)
        assertFalse(event.presentation.isEnabledAndVisible, "(F,F) both off must disable — locks AND vs OR")
    }

    @Test
    fun `update is visible in external accent context`() {
        val event = newEvent()
        every { AyuVariant.isAyuActive() } returns false
        every { AccentContext.isQuickSwitcherActive() } returns true
        every { LicenseChecker.isLicensedOrGrace() } returns true

        LighterAccentAction().update(event)

        assertTrue(event.presentation.isEnabledAndVisible)
    }

    @Test
    fun `actionPerformed feeds AccentHsl lighten output into applyFromHexString (Pattern D gated)`() {
        // `lighten` takes/returns `AccentHex`; unwrap via `.value` to match the
        // String-typed apply boundary.
        val expected = AccentHsl.lighten(AccentHex.unsafeOf("#FFCC66")).value
        LighterAccentAction().actionPerformed(newEvent())
        verify(exactly = 1) { AccentApplicator.applyFromHexString(expected) }
        verify(exactly = 1) { mockSwap.notifyExternalApply(expected) }
    }

    @Test
    fun `actionPerformed at MAX_LIGHTNESS clamp still calls applyFromHexString with the unchanged hex`() {
        // At the clamp the action runs; the balloon hint is a future
        // deliverable, not in scope here.
        val ceilingHex = HslColor.toHex(0f, 0f, AccentHsl.MAX_LIGHTNESS)
        every { AccentResolver.resolve(any(), any<AccentContext>()) } returns ceilingHex
        LighterAccentAction().actionPerformed(newEvent())
        // AccentHsl.lighten at the ceiling returns the input unchanged.
        verify(exactly = 1) { AccentApplicator.applyFromHexString(ceilingHex) }
    }
}
