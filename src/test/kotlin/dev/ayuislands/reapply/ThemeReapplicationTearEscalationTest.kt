package dev.ayuislands.reapply

import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the tear-escalation contract: the applicator contains mid-step throws
 * internally (WARN, `lastApplyOk` stays false), and the accent steps translate
 * that persisted signal back into a [StepFailure] so [ReapplyResult] consumers
 * — the rotation circuit breaker (`result.isClean`) and the license revert
 * notice (`result.failed(ApplyExplicitHex)`) — observe the tear again.
 */
class ThemeReapplicationTearEscalationTest {
    private val mockSettings = mockk<AyuIslandsSettings>(relaxed = true)
    private val state = AyuIslandsState()

    @BeforeTest
    fun setUp() {
        mockkStatic(SwingUtilities::class)
        every { SwingUtilities.isEventDispatchThread() } returns true

        // reapply() dispatches via Application.isDispatchThread, not SwingUtilities.
        val mockApplication = mockk<com.intellij.openapi.application.Application>(relaxed = true)
        mockkStatic(com.intellij.openapi.application.ApplicationManager::class)
        every {
            com.intellij.openapi.application.ApplicationManager
                .getApplication()
        } returns mockApplication
        every { mockApplication.isDispatchThread } returns true

        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns mockSettings
        every { mockSettings.state } returns state

        mockkObject(AccentApplicator)
        every { AccentApplicator.applyForFocusedProject(any<dev.ayuislands.accent.AccentContext>()) } returns "#FFCC66"
        every { AccentApplicator.applyFromHexString(any()) } returns true
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `rotation tick reports the apply step failed when the apply tore`() {
        state.lastApplyOk = false

        var result: ReapplyResult? = null
        ThemeReapplication.reapply(ReapplyReason.RotationTick(AyuVariant.DARK)) { result = it }

        assertTrue(
            requireNotNull(result).failed(ReapplyStep.ApplyResolvedAccent),
            "a torn apply must surface as the accent step's failure so the rotation circuit breaker counts it",
        )
    }

    @Test
    fun `rotation tick is clean when the apply completed cleanly`() {
        state.lastApplyOk = true

        var result: ReapplyResult? = null
        ThemeReapplication.reapply(ReapplyReason.RotationTick(AyuVariant.DARK)) { result = it }

        assertFalse(requireNotNull(result).failed(ReapplyStep.ApplyResolvedAccent))
    }

    @Test
    fun `license revert reports the explicit-hex step failed when the apply tore`() {
        state.lastApplyOk = false

        var result: ReapplyResult? = null
        ThemeReapplication.reapply(ReapplyReason.LicenseRevert("#E6B450")) { result = it }

        assertTrue(
            requireNotNull(result).failed(ReapplyStep.ApplyExplicitHex),
            "a torn apply must surface as the explicit-hex step's failure so the revert-incomplete notice fires",
        )
    }

    @Test
    fun `rotation tick reports the apply step failed when the resolver hex is shape-invalid`() {
        // The one case the clean-flag check cannot see: a shape-invalid hex makes
        // the applicator skip apply() entirely, leaving lastApplyOk stale (true
        // here). The returned hex carries the signal instead.
        state.lastApplyOk = true
        every {
            AccentApplicator.applyForFocusedProject(any<dev.ayuislands.accent.AccentContext>())
        } returns "not-a-hex"

        var result: ReapplyResult? = null
        ThemeReapplication.reapply(ReapplyReason.RotationTick(AyuVariant.DARK)) { result = it }

        assertTrue(requireNotNull(result).failed(ReapplyStep.ApplyResolvedAccent))
    }

    @Test
    fun `license revert reports the explicit-hex step failed when the hex is rejected`() {
        state.lastApplyOk = true
        every { AccentApplicator.applyFromHexString(any()) } returns false

        var result: ReapplyResult? = null
        ThemeReapplication.reapply(ReapplyReason.LicenseRevert("#E6B450")) { result = it }

        assertTrue(requireNotNull(result).failed(ReapplyStep.ApplyExplicitHex))
    }
}
