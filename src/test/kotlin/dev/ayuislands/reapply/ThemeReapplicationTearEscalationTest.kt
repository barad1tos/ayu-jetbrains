package dev.ayuislands.reapply

import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentApplyOutcome
import dev.ayuislands.accent.AccentApplyScope
import dev.ayuislands.accent.AccentApplyStep
import dev.ayuislands.accent.AccentApplyStepFailure
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.AccentLifecycle
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.syntax.SyntaxIntensityService
import dev.ayuislands.vcs.VcsColorApplier
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        every { AccentApplicator.applyForFocusedProject(any<AccentContext>()) } returns
            AccentApplyOutcome.Applied(requireNotNull(AccentHex.of("#FFCC66")))
        every { AccentApplicator.applyFromHexString(any()) } answers
            {
                AccentHex.of(firstArg<String>())?.let { validatedHex -> AccentApplyOutcome.Applied(validatedHex) }
                    ?: AccentApplyOutcome.Rejected(firstArg())
            }
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `rotation tick reports the apply step failed when the apply tore`() {
        state.lastApplyOk = true
        val failedPaint =
            AccentApplyOutcome.Torn(
                requireNotNull(AccentHex.of("#FFCC66")),
                listOf(AccentApplyStepFailure(AccentApplyStep.ApplyElements, IllegalStateException("paint"))),
            )
        every { AccentApplicator.applyForFocusedProject(any<AccentContext>()) } returns
            failedPaint
        every { AccentApplicator.applyFromHexString(any()) } returns failedPaint

        var result: ReapplyResult? = null
        ThemeReapplication.reapply(ReapplyReason.RotationTick(AyuVariant.DARK)) { result = it }

        assertTrue(
            requireNotNull(result).failed(ReapplyStep.ApplyResolvedAccent),
            "a torn apply must surface as the accent step's failure so the rotation circuit breaker counts it",
        )
    }

    @Test
    fun `rotation tick is clean when the apply completed cleanly`() {
        state.lastApplyOk = false

        var result: ReapplyResult? = null
        ThemeReapplication.reapply(ReapplyReason.RotationTick(AyuVariant.DARK)) { result = it }

        assertFalse(requireNotNull(result).failed(ReapplyStep.ApplyResolvedAccent))
    }

    @Test
    fun `license revert reports the explicit-hex step failed when the apply tore`() {
        state.lastApplyOk = true
        val failedPaint =
            AccentApplyOutcome.Torn(
                requireNotNull(AccentHex.of("#FFCC66")),
                listOf(AccentApplyStepFailure(AccentApplyStep.ApplyElements, IllegalStateException("paint"))),
            )
        every { AccentApplicator.applyForFocusedProject(any<AccentContext>()) } returns
            failedPaint
        every { AccentApplicator.applyFromHexString(any()) } returns failedPaint

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
            AccentApplicator.applyForFocusedProject(any<AccentContext>())
        } returns AccentApplyOutcome.Rejected("not-a-hex")

        var result: ReapplyResult? = null
        ThemeReapplication.reapply(ReapplyReason.RotationTick(AyuVariant.DARK)) { result = it }

        assertTrue(requireNotNull(result).failed(ReapplyStep.ApplyResolvedAccent))
    }

    @Test
    fun `license revert reports the explicit-hex step failed when the hex is rejected`() {
        state.lastApplyOk = true
        every { AccentApplicator.applyFromHexString(any()) } answers { AccentApplyOutcome.Rejected(firstArg()) }

        var result: ReapplyResult? = null
        ThemeReapplication.reapply(ReapplyReason.LicenseRevert("#E6B450")) { result = it }

        assertTrue(requireNotNull(result).failed(ReapplyStep.ApplyExplicitHex))
    }

    @Test
    fun `license plan reports completion only after an active apply has unwound`() {
        runDeferred(ReapplyReason.LicenseRevert("#E6B450")) { }
    }

    @Test
    fun `queued theme change applies to the context current when it executes`() {
        state.syncEditorScheme = false
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        val applied = mutableListOf<AccentContext>()
        every { AccentApplicator.applyForFocusedProject(any<AccentContext>()) } answers {
            applied += firstArg<AccentContext>()
            AccentApplyOutcome.Applied(requireNotNull(AccentHex.of("#FFCC66")))
        }

        runDeferred(ReapplyReason.ThemeSwitched(AccentContext.Ayu(AyuVariant.MIRAGE))) {
            every { AyuVariant.detect() } returns AyuVariant.DARK
        }

        assertEquals(expected = listOf<AccentContext>(AccentContext.Ayu(AyuVariant.DARK)), actual = applied)
    }

    @Test
    fun `queued downgrade resolves the free accent for the current variant`() {
        mockkObject(AyuVariant.Companion)
        mockkObject(LicenseChecker)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        every { LicenseChecker.currentEntitlement() } returns LicenseEntitlement.UNLICENSED
        every { mockSettings.getAccentForVariant(AyuVariant.DARK) } returns "#E6B450"
        val applied = mutableListOf<String>()
        every { AccentApplicator.applyFromHexString(any()) } answers {
            val value = firstArg<String>()
            applied += value
            AccentApplyOutcome.Applied(requireNotNull(AccentHex.of(value)))
        }

        runDeferred(ReapplyReason.LicenseRevert("#FFCC66")) {
            every { AyuVariant.detect() } returns AyuVariant.DARK
        }

        assertEquals(expected = listOf("#E6B450"), actual = applied)
    }

    @Test
    fun `restored license supersedes a queued downgrade without running its effects`() {
        mockkObject(AyuVariant.Companion)
        mockkObject(LicenseChecker)
        mockkObject(GlowOverlayManager)
        mockkObject(VcsColorApplier)
        mockkObject(SyntaxIntensityService.Companion)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        every { LicenseChecker.currentEntitlement() } returns LicenseEntitlement.UNLICENSED
        val effects = mutableListOf<String>()
        every { AccentApplicator.applyFromHexString(any()) } answers {
            effects += "accent"
            AccentApplyOutcome.Applied(requireNotNull(AccentHex.of(firstArg())))
        }
        every { GlowOverlayManager.syncGlowForAllProjects() } answers { effects += "glow" }
        every { VcsColorApplier.revertAll() } answers { effects += "VCS" }
        val syntax = mockk<SyntaxIntensityService>()
        every { SyntaxIntensityService.getInstance() } returns syntax
        every { syntax.reapplyForActiveLaf() } answers { effects += "syntax" }

        val result =
            runDeferred(ReapplyReason.LicenseRevert("#FFCC66")) {
                every { LicenseChecker.currentEntitlement() } returns LicenseEntitlement.LICENSED
            }

        assertEquals(expected = ReapplyStatus.SUPERSEDED, actual = result.status)
        assertFalse(result.isClean)
        assertTrue(effects.isEmpty(), "a superseded request must not repaint or downgrade any surface")
    }

    private fun runDeferred(
        reason: ReapplyReason,
        beforeDrain: () -> Unit,
    ): ReapplyResult {
        val application =
            com.intellij.openapi.application.ApplicationManager
                .getApplication()
        val posted = ArrayDeque<Runnable>()
        every {
            application.invokeLater(any<Runnable>(), any<com.intellij.openapi.application.ModalityState>())
        } answers { posted.addLast(firstArg()) }
        var outcome: ReapplyResult? = null
        var completions = 0
        val scope = AccentApplyScope()
        scope.open()
        try {
            ThemeReapplication.reapply(reason) {
                completions++
                outcome = it
            }

            assertNull(outcome)
            scope.restore()
        } finally {
            scope.close()
            AccentLifecycle.afterApply()
        }

        beforeDrain()
        while (posted.isNotEmpty()) posted.removeFirst().run()
        assertEquals(expected = 1, actual = completions)
        return assertNotNull(outcome)
    }
}
