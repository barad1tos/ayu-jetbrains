package dev.ayuislands.accent.toolbar

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentApplyOutcome
import dev.ayuislands.accent.AccentApplyStep
import dev.ayuislands.accent.AccentApplyStepFailure
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.mappings.AccentMappingsSettings
import dev.ayuislands.settings.mappings.AccentMappingsState
import dev.ayuislands.settings.mappings.ProjectAccentSwapService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.event.MouseEvent
import javax.swing.JComponent
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Locks the chip's inner-island pin-toggle wiring - the new
 * one-click pin/unpin affordance that the layered icon advertises
 * visually (filled inner = pinned, hollow = no pin).
 *
 * Covers:
 *  - **Pin** (no pin -> click inner-square): writes the project's hex
 *    into `AccentMappingsState.projectAccents` under the
 *    `AccentResolver.projectKey` entry, calls
 *    `AccentApplicator.applyFromHexString(hex)`, and (Pattern D)
 *    publishes via `ProjectAccentSwapService.notifyExternalApply`.
 *  - **Unpin** (pin active -> click inner-square): removes the
 *    `projectAccents` entry under the same key, re-applies the
 *    resolved global, publishes the global hex via
 *    `notifyExternalApply`.
 *  - **Outer-region click** (no pin or pin): existing popup-open path
 *    survives; no pin mutation.
 *  - **Pattern J gate**: `LicenseChecker.isLicensedOrGrace() = false`
 *    -> inner-square click is a no-op (popup still opens for clicks
 *    outside, but no inner-island toggle fires).
 *
 * Mirrors the existing `PinAccentActionTest` mock harness so the
 * write contract stays consistent across the popup quick-action row,
 * the right-click context menu, and now the chip's inner click.
 */
class QuickSwitcherChipPinToggleTest {
    private val mockApp = mockk<Application>(relaxed = true)
    private val mockBus = mockk<MessageBus>(relaxed = true)
    private val mockConnection = mockk<MessageBusConnection>(relaxed = true)
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
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns mockApp
        every { mockApp.messageBus } returns mockBus
        every { mockBus.connect(any<Disposable>()) } returns mockConnection
        every { mockApp.getService(ProjectAccentSwapService::class.java) } returns mockSwap

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
        every { AccentApplicator.applyFromHexString(any()) } answers
            {
                AccentHex.of(firstArg<String>())?.let { validatedHex -> AccentApplyOutcome.Applied(validatedHex) }
                    ?: AccentApplyOutcome.Rejected(firstArg())
            }

        mockkObject(AccentResolver)
        every { AccentResolver.resolve(any(), any<AyuVariant>()) } returns "#FFB454"
        every { AccentResolver.source(any()) } returns AccentResolver.Source.GLOBAL
        every { AccentResolver.sourceLabel(any()) } returns "Global"
        every { AccentResolver.projectKey(any()) } returns PROJECT_KEY

        mockkObject(AccentMappingsSettings.Companion)
        every { AccentMappingsSettings.getInstance() } returns mockSettings
        every { mockSettings.state } returns state

        mockkObject(QuickSwitcherPopup)
        every { QuickSwitcherPopup.show(any(), any()) } returns Unit
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `inner-square click WITHOUT existing pin writes projectAccents and applies (Pattern D)`() {
        // GLOBAL source means no project pin - the inner click is the "Pin" path.
        every { AccentResolver.source(mockProject) } returns AccentResolver.Source.GLOBAL

        val chip = QuickSwitcherChipComponent()
        chip.dispatchEvent(innerClick(chip))

        assertEquals(
            "#FFB454",
            state.projectAccents[PROJECT_KEY],
            "Pin path must write currentHex to projectAccents under the project key",
        )
        verify(exactly = 1) { AccentApplicator.applyFromHexString("#FFB454") }
        verify(exactly = 1) {
            mockSwap.notifyExternalApply(AccentApplyOutcome.Applied(requireNotNull(AccentHex.of("#FFB454"))))
        }
        verify(exactly = 0) { QuickSwitcherPopup.show(any(), any()) }
    }

    @Test
    fun `inner-square click WITH existing pin removes projectAccents and re-applies global`() {
        // Seed an existing pin then flip the resolver source so the chip
        // sees `PROJECT_OVERRIDE`. After unpin the resolver returns the
        // global hex on the SAME `resolve(...)` call - that's what the
        // chip re-applies.
        state.projectAccents[PROJECT_KEY] = "#FFB454"
        every { AccentResolver.source(mockProject) } returns AccentResolver.Source.PROJECT_OVERRIDE
        every { AccentResolver.resolve(any(), any<AyuVariant>()) } returns "#73D0FF"

        val chip = QuickSwitcherChipComponent()
        chip.dispatchEvent(innerClick(chip))

        assertFalse(
            state.projectAccents.containsKey(PROJECT_KEY),
            "Unpin path must remove the project key from projectAccents",
        )
        verify(exactly = 1) { AccentApplicator.applyFromHexString("#73D0FF") }
        verify(exactly = 1) {
            mockSwap.notifyExternalApply(AccentApplyOutcome.Applied(requireNotNull(AccentHex.of("#73D0FF"))))
        }
        verify(exactly = 0) { QuickSwitcherPopup.show(any(), any()) }
    }

    @Test
    fun `outer-region click opens the popup and does NOT mutate projectAccents (no pin start)`() {
        every { AccentResolver.source(mockProject) } returns AccentResolver.Source.GLOBAL

        val chip = QuickSwitcherChipComponent()
        chip.dispatchEvent(outerClick(chip))

        verify(exactly = 1) { QuickSwitcherPopup.show(chip, chip) }
        verify(exactly = 0) { AccentApplicator.applyFromHexString(any()) }
        assertTrue(state.projectAccents.isEmpty(), "Outer click must not write to projectAccents")
    }

    @Test
    fun `Pattern J gate - inner click with invalid licence is a NO-OP (no pin mutation)`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        every { AccentResolver.source(mockProject) } returns AccentResolver.Source.GLOBAL

        val chip = QuickSwitcherChipComponent()
        chip.dispatchEvent(innerClick(chip))

        // Inner click with invalid licence falls through to the popup-open
        // path so the chip never "swallows" clicks silently - the popup
        // itself has the full Pattern J gating for premium actions.
        verify(exactly = 1) { QuickSwitcherPopup.show(chip, chip) }
        verify(exactly = 0) { AccentApplicator.applyFromHexString(any()) }
        assertTrue(state.projectAccents.isEmpty(), "Invalid licence must not write projectAccents")
    }

    @Test
    fun `external inner click opens popup instead of swallowing Ayu-only pin`() {
        every { AyuVariant.isAyuActive() } returns false
        every { AyuVariant.detect() } returns null
        every { AccentContext.isQuickSwitcherActive() } returns true
        every { AccentContext.detectQuickSwitcher() } returns AccentContext.External

        val chip = QuickSwitcherChipComponent()
        chip.dispatchEvent(innerClick(chip))

        verify(exactly = 1) { QuickSwitcherPopup.show(chip, chip) }
        verify(exactly = 0) { AccentApplicator.applyFromHexString(any()) }
        assertTrue(state.projectAccents.isEmpty(), "External inner click must not write Ayu project pins")
    }

    @Test
    fun `pin path rolls back projectAccents when applyFromHexString rejects the hex`() {
        // Pin from unpinned + apply rejects (e.g. corrupted upstream
        // settings produce an invalid hex). The pin write must NOT persist
        // ahead of runtime state - restore the pre-toggle map and skip the
        // notify so the persisted store stays consistent with what the
        // accent applicator actually applied.
        every { AccentResolver.source(mockProject) } returns AccentResolver.Source.GLOBAL
        every { AccentApplicator.applyFromHexString(any()) } answers { AccentApplyOutcome.Rejected(firstArg()) }

        val chip = QuickSwitcherChipComponent()
        chip.dispatchEvent(innerClick(chip))

        assertTrue(
            state.projectAccents.isEmpty(),
            "Pin path rejection must roll back the projectAccents write (pre-state had no pin)",
        )
        verify(exactly = 0) { mockSwap.notifyExternalApply(any()) }
    }

    @Test
    fun `unpin path rolls back projectAccents when applyFromHexString rejects the hex`() {
        // Unpin + apply rejects. The pin must be RESTORED (not just left
        // removed) so the next focus refresh re-resolves through the same
        // override the user thought they were unpinning.
        state.projectAccents[PROJECT_KEY] = "#FFB454"
        every { AccentResolver.source(mockProject) } returns AccentResolver.Source.PROJECT_OVERRIDE
        every { AccentResolver.resolve(any(), any<AyuVariant>()) } returns "#73D0FF"
        every { AccentApplicator.applyFromHexString(any()) } answers { AccentApplyOutcome.Rejected(firstArg()) }

        val chip = QuickSwitcherChipComponent()
        chip.dispatchEvent(innerClick(chip))

        assertEquals(
            "#FFB454",
            state.projectAccents[PROJECT_KEY],
            "Unpin path rejection must restore the pre-toggle pin hex under the project key",
        )
        verify(exactly = 0) { mockSwap.notifyExternalApply(any()) }
    }

    @Test
    fun `pin toggle swallows RuntimeException from applyFromHexString and rolls back (Pattern B)`() {
        // Pattern B parity with sibling chip handlers (refresh, openAyuSettings,
        // applyPreset, openCustomColorPicker). A throw from the apply
        // chain MUST NOT propagate out of the click handler AND the pin
        // write must NOT persist past the failure.
        every { AccentResolver.source(mockProject) } returns AccentResolver.Source.GLOBAL
        every { AccentApplicator.applyFromHexString(any()) } throws RuntimeException("transient mid-LAF-swap")

        val chip = QuickSwitcherChipComponent()
        // Must NOT throw out of the click.
        chip.dispatchEvent(innerClick(chip))

        assertTrue(
            state.projectAccents.isEmpty(),
            "RuntimeException from apply must roll back the projectAccents write",
        )
        verify(exactly = 0) { mockSwap.notifyExternalApply(any()) }
    }

    @Test
    fun `inner click with null projectKey is a NO-OP (no pin mutation, no apply)`() {
        // Defensive early-return when `AccentResolver.projectKey` returns
        // null (race with project dispose, basePath read failure). The
        // chip must not crash and must not write a stale pin under a
        // null/empty key.
        every { AccentResolver.projectKey(any()) } returns null
        every { AccentResolver.source(mockProject) } returns AccentResolver.Source.GLOBAL

        val chip = QuickSwitcherChipComponent()
        chip.dispatchEvent(innerClick(chip))

        verify(exactly = 0) { AccentApplicator.applyFromHexString(any()) }
        verify(exactly = 0) { mockSwap.notifyExternalApply(any()) }
        assertTrue(state.projectAccents.isEmpty(), "projectKey null must skip projectAccents write entirely")
    }

    private fun innerClick(source: JComponent): MouseEvent {
        // Centre pixel of the chip is always inside the inner-square bounds.
        val centre = JBUI.scale(QuickSwitcherChipComponent.CHIP_BOX_PX) / 2
        return MouseEvent(source, MouseEvent.MOUSE_PRESSED, 0L, 0, centre, centre, 1, false, MouseEvent.BUTTON1)
    }

    private fun outerClick(source: JComponent): MouseEvent {
        // Corner pixel (0, 0) is outside the inner-square bounds for any
        // INNER_INSET_RATIO > 0, so this reliably hits the outer-ring region.
        return MouseEvent(source, MouseEvent.MOUSE_PRESSED, 0L, 0, 0, 0, 1, false, MouseEvent.BUTTON1)
    }

    private companion object {
        const val PROJECT_KEY: String = "/path/to/project"
    }

    @Test
    fun `torn paint preserves a newly selected pin and existing order`() {
        every { AccentResolver.source(mockProject) } returns AccentResolver.Source.GLOBAL
        state.projectAccents["/unavailable-first"] = "#123456"
        val failedPaint =
            AccentApplyOutcome.Torn(
                requireNotNull(AccentHex.of("#FFB454")),
                listOf(
                    AccentApplyStepFailure(AccentApplyStep.ApplyElements, IllegalStateException("paint")),
                ),
            )
        every { AccentApplicator.applyFromHexString("#FFB454") } returns failedPaint
        val chip = QuickSwitcherChipComponent()

        chip.dispatchEvent(innerClick(chip))

        assertEquals("#FFB454", state.projectAccents[PROJECT_KEY])
        assertEquals("#123456", state.projectAccents["/unavailable-first"])
        assertEquals(listOf("/unavailable-first", PROJECT_KEY), state.projectAccents.keys.toList())
        verify(exactly = 1) { mockSwap.notifyExternalApply(failedPaint) }
    }

    @Test
    fun `torn paint preserves the users unpin choice`() {
        state.projectAccents[PROJECT_KEY] = "#FFB454"
        every { AccentResolver.source(mockProject) } returns AccentResolver.Source.PROJECT_OVERRIDE
        every { AccentResolver.resolve(any(), any<AyuVariant>()) } returns "#73D0FF"
        val failedPaint =
            AccentApplyOutcome.Torn(
                requireNotNull(AccentHex.of("#73D0FF")),
                listOf(
                    AccentApplyStepFailure(AccentApplyStep.ApplyElements, IllegalStateException("paint")),
                ),
            )
        every { AccentApplicator.applyFromHexString("#73D0FF") } returns failedPaint
        val chip = QuickSwitcherChipComponent()

        chip.dispatchEvent(innerClick(chip))

        assertFalse(state.projectAccents.containsKey(PROJECT_KEY))
        verify(exactly = 1) { mockSwap.notifyExternalApply(failedPaint) }
    }

    @Test
    fun `pin restores previous state before propagating platform cancellation`() {
        val cancelled = ProcessCanceledException()
        state.projectAccents["/unavailable"] = "#123456"
        val previousPins = state.projectAccents.toMap()
        every { AccentApplicator.applyFromHexString(any()) } throws cancelled
        val chip = QuickSwitcherChipComponent()

        val thrown = assertFailsWith<RuntimeException> { chip.dispatchEvent(innerClick(chip)) }

        assertSame(cancelled, thrown)
        assertEquals(previousPins, state.projectAccents)
        verify(exactly = 0) { mockSwap.notifyExternalApply(any()) }
    }

    @Test
    fun `unpin restores previous state before propagating platform cancellation`() {
        val cancelled = ProcessCanceledException()
        state.projectAccents["/first"] = "#654321"
        state.projectAccents[PROJECT_KEY] = "#FFB454"
        every { AccentResolver.source(mockProject) } returns AccentResolver.Source.PROJECT_OVERRIDE
        state.projectAccents["/unavailable"] = "#123456"
        val previousPins = state.projectAccents.toMap()
        every { AccentApplicator.applyFromHexString(any()) } throws cancelled
        val chip = QuickSwitcherChipComponent()

        val thrown = assertFailsWith<RuntimeException> { chip.dispatchEvent(innerClick(chip)) }

        assertSame(cancelled, thrown)
        assertEquals(previousPins, state.projectAccents)
        verify(exactly = 0) { mockSwap.notifyExternalApply(any()) }
    }

    @Test
    fun `pin restores previous state before propagating coroutine cancellation`() {
        val cancelled = CancellationException("cancelled accent")
        state.projectAccents["/unavailable"] = "#123456"
        val previousPins = state.projectAccents.toMap()
        every { AccentApplicator.applyFromHexString(any()) } throws cancelled
        val chip = QuickSwitcherChipComponent()

        val thrown = assertFailsWith<RuntimeException> { chip.dispatchEvent(innerClick(chip)) }

        assertSame(cancelled, thrown)
        assertEquals(previousPins, state.projectAccents)
        verify(exactly = 0) { mockSwap.notifyExternalApply(any()) }
    }

    @Test
    fun `unpin restores previous state before propagating coroutine cancellation`() {
        val cancelled = CancellationException("cancelled accent")
        state.projectAccents["/first"] = "#654321"
        state.projectAccents[PROJECT_KEY] = "#FFB454"
        every { AccentResolver.source(mockProject) } returns AccentResolver.Source.PROJECT_OVERRIDE
        state.projectAccents["/unavailable"] = "#123456"
        val previousPins = state.projectAccents.toMap()
        every { AccentApplicator.applyFromHexString(any()) } throws cancelled
        val chip = QuickSwitcherChipComponent()

        val thrown = assertFailsWith<RuntimeException> { chip.dispatchEvent(innerClick(chip)) }

        assertSame(cancelled, thrown)
        assertEquals(previousPins, state.projectAccents)
        verify(exactly = 0) { mockSwap.notifyExternalApply(any()) }
    }

    @Test
    fun `chip refresh propagates platform cancellation from resolve`() {
        val cancelled = ProcessCanceledException()
        every { AccentResolver.resolve(any(), any<AccentContext>()) } throws cancelled
        val chip = QuickSwitcherChipComponent()

        val thrown = assertFailsWith<RuntimeException> { chip.refreshFromFocusedProject() }

        assertSame(cancelled, thrown)
    }

    @Test
    fun `chip refresh propagates coroutine cancellation from resolve`() {
        val cancelled = CancellationException("cancelled refresh")
        every { AccentResolver.resolve(any(), any<AccentContext>()) } throws cancelled
        val chip = QuickSwitcherChipComponent()

        val thrown = assertFailsWith<RuntimeException> { chip.refreshFromFocusedProject() }

        assertSame(cancelled, thrown)
    }

    @Test
    fun `cancelled unpin restores prior pin and preserves other pin edits`() {
        val cancelled = ProcessCanceledException()
        state.projectAccents["/first"] = "#123456"
        state.projectAccents[PROJECT_KEY] = "#abcdef"
        state.projectAccents["/last"] = "#654321"
        every { AccentResolver.source(mockProject) } returns AccentResolver.Source.PROJECT_OVERRIDE
        every { AccentApplicator.applyFromHexString(any()) } answers {
            state.projectAccents["/first"] = "#112233"
            state.projectAccents["/new"] = "#445566"
            throw cancelled
        }
        val chip = QuickSwitcherChipComponent()

        val thrown = assertFailsWith<RuntimeException> { chip.dispatchEvent(innerClick(chip)) }

        assertSame(cancelled, thrown)
        assertEquals(setOf("/first", PROJECT_KEY, "/last", "/new"), state.projectAccents.keys)
        assertEquals("#112233", state.projectAccents["/first"])
        assertEquals("#abcdef", state.projectAccents[PROJECT_KEY])
        assertEquals("#654321", state.projectAccents["/last"])
        assertEquals("#445566", state.projectAccents["/new"])
    }
}
