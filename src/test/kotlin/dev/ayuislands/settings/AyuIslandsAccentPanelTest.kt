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
 * Locks in the [AyuIslandsAccentPanel.applyWithFallback] failure-recovery contract:
 *  - happy path: applyForFocusedProject runs; no fallback triggered
 *  - corrupted override: applyForFocusedProject throws, fallback applies the global hex
 *    AND syncs the swap cache (the swap-cache-sync omission was the original bug — the
 *    fallback used to skip it, silently reintroducing the stale-cache → redundant-apply
 *    pattern applyForFocusedProject was created to prevent)
 *  - corrupted global: BOTH paths throw; the panel stays operational, second LOG.error
 *    fires with "also failed" context, no exception escapes — avoids the generic
 *    "Settings can't save" dialog a hand-edited global hex would otherwise trigger
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
        // Regression guard: a previous fallback applied the global accent but forgot to
        // call ProjectAccentSwapService.notifyExternalApply, leaving the swap cache stale
        // and silently re-introducing the exact bug applyForFocusedProject was created
        // to prevent (next WINDOW_ACTIVATED would redundantly re-apply).
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
        // Regression guard: the fallback's own apply(effectiveAccent) can throw when
        // the GLOBAL hex is corrupted (hand-edited XML, legacy writer). Without the
        // second try/catch, the Settings "OK" path would bubble up as a generic
        // "Can't save" dialog. The catch logs and leaves the visible accent unchanged.
        every { AccentApplicator.applyForFocusedProject(AyuVariant.MIRAGE) } throws
            IllegalStateException("override hex corrupted")
        every { AccentApplicator.apply("#FFCC66") } throws
            IllegalStateException("global hex also corrupted")

        val panel = AyuIslandsAccentPanel()
        // No exception escapes — both throws are caught and logged.
        LoggedErrorProcessor.executeWith<Throwable>(suppressLoggedErrors()) {
            panel.applyWithFallback(AyuVariant.MIRAGE, "#FFCC66")
        }
        // notifyExternalApply must NOT be reached when the global-fallback apply throws.
        verify(exactly = 0) { swapService.notifyExternalApply(any()) }
    }

    @Test
    fun `applyWithFallback logs WARN when swap cache sync throws after successful global apply`() {
        // Regression guard for failure mode #3: applyForFocusedProject throws, the
        // global-fallback apply(effectiveAccent) succeeds, but notifyExternalApply
        // throws (swap service mid-dispose, corrupted cache). The visible accent has
        // already changed; only the focus-swap cache is stale. The panel must log at
        // WARN (not ERROR, since apply actually worked) and must NOT rethrow — otherwise
        // the Settings OK path degrades to a generic "Can't save" dialog on a path where
        // the user's intent was actually applied.
        every { AccentApplicator.applyForFocusedProject(AyuVariant.MIRAGE) } throws
            IllegalStateException("override hex corrupted")
        every { AccentApplicator.apply("#FFCC66") } just Runs
        every { swapService.notifyExternalApply("#FFCC66") } throws
            IllegalStateException("swap service disposed mid-save")

        val expectedWarnSubstring = "swap-cache sync failed"
        val capturedWarns = mutableListOf<String>()
        val processor =
            object : LoggedErrorProcessor() {
                override fun processError(
                    category: String,
                    message: String,
                    details: Array<out String>,
                    throwable: Throwable?,
                ): Set<Action> = java.util.EnumSet.noneOf(Action::class.java)

                override fun processWarn(
                    category: String,
                    message: String,
                    throwable: Throwable?,
                ): Boolean {
                    if (!message.contains(expectedWarnSubstring)) return true
                    capturedWarns += message
                    return false
                }
            }

        val panel = AyuIslandsAccentPanel()
        LoggedErrorProcessor.executeWith<Throwable>(processor) {
            panel.applyWithFallback(AyuVariant.MIRAGE, "#FFCC66")
        }

        verify(exactly = 1) { AccentApplicator.apply("#FFCC66") }
        verify(exactly = 1) { swapService.notifyExternalApply("#FFCC66") }
        kotlin.test.assertEquals(
            1,
            capturedWarns.size,
            "notifyExternalApply throw must produce exactly one WARN (not ERROR); got: $capturedWarns",
        )
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
