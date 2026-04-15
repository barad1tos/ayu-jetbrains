package dev.ayuislands.settings

import com.intellij.testFramework.LoggedErrorProcessor
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.settings.mappings.ProjectAccentSwapService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Locks in the [AyuIslandsAccentPanel.applyWithFallbackForTest] failure-recovery contract:
 *  - happy path: applyForFocusedProject runs; no fallback triggered
 *  - corrupted override: applyForFocusedProject throws, fallback applies global hex AND
 *    syncs the swap cache (the bug R5 closed — fallback used to skip the cache sync)
 *  - corrupted global: BOTH paths throw; the panel stays operational, second LOG.error
 *    fires, no exception escapes (the bug R6 closed)
 */
class AyuIslandsAccentPanelTest {
    private lateinit var swapService: ProjectAccentSwapService

    @BeforeTest
    fun setUp() {
        mockkObject(AccentApplicator)
        swapService = mockk(relaxed = true)
        mockkObject(ProjectAccentSwapService.Companion)
        every { ProjectAccentSwapService.getInstance() } returns swapService
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `applyWithFallback happy path delegates to applyForFocusedProject and skips fallback`() {
        every { AccentApplicator.applyForFocusedProject(AyuVariant.MIRAGE) } returns "#ABCDEF"

        val panel = AyuIslandsAccentPanel()
        panel.applyWithFallback(AyuVariant.MIRAGE, "#FFCC66")

        verify(exactly = 1) { AccentApplicator.applyForFocusedProject(AyuVariant.MIRAGE) }
        // Fallback path's apply(effectiveAccent) and notifyExternalApply must NOT fire.
        verify(exactly = 0) { AccentApplicator.apply(any()) }
        verify(exactly = 0) { swapService.notifyExternalApply(any()) }
    }

    @Test
    fun `applyWithFallback corrupted override falls back to global AND syncs swap cache`() {
        // Regression guard for R5: the previous fallback applied the global accent but
        // forgot to call ProjectAccentSwapService.notifyExternalApply, leaving the swap
        // cache stale and silently re-introducing the exact bug applyForFocusedProject
        // was created to prevent.
        every { AccentApplicator.applyForFocusedProject(AyuVariant.MIRAGE) } throws
            IllegalStateException("override hex corrupted")
        every { AccentApplicator.apply("#FFCC66") } just Runs

        val panel = AyuIslandsAccentPanel()
        LoggedErrorProcessor.executeWith<Throwable>(suppressLoggedErrors()) {
            panel.applyWithFallback(AyuVariant.MIRAGE, "#FFCC66")
        }

        verify(exactly = 1) { AccentApplicator.apply("#FFCC66") }
        verify(exactly = 1) { swapService.notifyExternalApply("#FFCC66") }
    }

    @Test
    fun `applyWithFallback corrupted global ALSO does not propagate exception`() {
        // Regression guard for R6: the fallback's own apply(effectiveAccent) can throw
        // when the GLOBAL hex is corrupted (hand-edited XML, legacy writer). Without the
        // second try/catch, the Settings "OK" path failed with a generic dialog. Now it
        // logs and leaves the visible accent unchanged.
        every { AccentApplicator.applyForFocusedProject(AyuVariant.MIRAGE) } throws
            IllegalStateException("override hex corrupted")
        every { AccentApplicator.apply("#FFCC66") } throws
            IllegalStateException("global hex also corrupted")

        val panel = AyuIslandsAccentPanel()
        // No exception escapes — both throws are caught and logged.
        LoggedErrorProcessor.executeWith<Throwable>(suppressLoggedErrors()) {
            panel.applyWithFallback(AyuVariant.MIRAGE, "#FFCC66")
        }
    }

    private fun suppressLoggedErrors(): LoggedErrorProcessor =
        object : LoggedErrorProcessor() {
            override fun processError(
                category: String,
                message: String,
                details: Array<out String>,
                throwable: Throwable?,
            ): Set<Action> = java.util.EnumSet.noneOf(Action::class.java)
        }
}
