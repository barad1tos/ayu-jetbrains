package dev.ayuislands.accent.toolbar.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.rotation.ContrastAwareColorGenerator
import dev.ayuislands.settings.mappings.ProjectAccentSwapService
import io.mockk.clearMocks
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
 * Locks [RandomAccentAction]: Pattern J gate, BGT thread, generator call,
 * Pattern D apply gate, Pattern B swallow on generator throw. Tests 11, 16,
 * 25, 26 per `48-03-PLAN.md` `<behavior>`.
 */
class RandomAccentActionTest {
    private val mockApp = mockk<Application>(relaxed = true)
    private val mockSwap = mockk<ProjectAccentSwapService>(relaxed = true)

    @BeforeTest
    fun setUp() {
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.isAyuActive() } returns true
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE

        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true

        mockkObject(AccentApplicator)
        every { AccentApplicator.applyFromHexString(any()) } returns true

        mockkObject(ContrastAwareColorGenerator)
        every { ContrastAwareColorGenerator.generate(any()) } returns "#5CCFE6"

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
        assertEquals(ActionUpdateThread.BGT, RandomAccentAction().getActionUpdateThread())
    }

    @Test
    fun `update Pattern J two-level gate exhaustive (T,T) (F,T) (T,F) (F,F)`() {
        // Full 4-case truth table; (F,F) locks AND-not-OR.
        val action = RandomAccentAction()
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
    fun `actionPerformed generates random hex for detected variant and applies via Pattern D`() {
        // Test 25
        RandomAccentAction().actionPerformed(newEvent())
        verify(exactly = 1) { ContrastAwareColorGenerator.generate(AyuVariant.MIRAGE) }
        verify(exactly = 1) { AccentApplicator.applyFromHexString("#5CCFE6") }
        verify(exactly = 1) { mockSwap.notifyExternalApply("#5CCFE6") }

        // Pattern D rejection path — no swap publish
        clearMocks(mockSwap)
        every { AccentApplicator.applyFromHexString(any()) } returns false
        RandomAccentAction().actionPerformed(newEvent())
        verify(exactly = 0) { mockSwap.notifyExternalApply(any()) }
    }

    @Test
    fun `actionPerformed swallows RuntimeException from generator (Pattern B) without apply`() {
        // Test 26
        every { ContrastAwareColorGenerator.generate(any()) } throws RuntimeException("boom")
        RandomAccentAction().actionPerformed(newEvent())
        verify(exactly = 0) { AccentApplicator.applyFromHexString(any()) }
        verify(exactly = 0) { mockSwap.notifyExternalApply(any()) }
    }
}
