package dev.ayuislands.accent.toolbar.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks [CopyHexAction]: Pattern J gate, BGT thread, clipboard write at
 * invocation time (no cached state — stale-state lock), and non-Ayu
 * belt-and-braces no-op.
 */
class CopyHexActionTest {
    private val mockClipboard = mockk<CopyPasteManager>(relaxed = true)
    private val mockProject = mockk<Project>(relaxed = true)

    @BeforeTest
    fun setUp() {
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.isAyuActive() } returns true
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        mockkObject(AccentContext.Companion)
        every { AccentContext.isAccentActive() } returns true
        every { AccentContext.detect() } returns AccentContext.Ayu(AyuVariant.MIRAGE)

        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true

        mockkObject(AccentApplicator)
        every { AccentApplicator.resolveFocusedProject() } returns mockProject

        mockkObject(AccentResolver)
        every { AccentResolver.resolve(any(), any<AccentContext>()) } returns "#FFCC66"
        every { AccentResolver.resolve(any(), any<AyuVariant>()) } returns "#FFCC66"

        mockkStatic(CopyPasteManager::class)
        every { CopyPasteManager.getInstance() } returns mockClipboard
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
        assertEquals(ActionUpdateThread.BGT, CopyHexAction().getActionUpdateThread())
    }

    @Test
    fun `update Pattern J two-level gate exhaustive (T,T) (F,T) (T,F) (F,F)`() {
        // Full 4-case truth table. A future `&&` → `||` regression would be
        // invisible without the (F,F) case (any single-conjunct test would
        // still pass under an OR gate).
        val action = CopyHexAction()
        val event = newEvent()

        every { AccentContext.isAccentActive() } returns true
        every { LicenseChecker.isLicensedOrGrace() } returns true
        action.update(event)
        assertTrue(event.presentation.isEnabledAndVisible, "(T,T) must enable")

        every { AccentContext.isAccentActive() } returns false
        every { LicenseChecker.isLicensedOrGrace() } returns true
        action.update(event)
        assertFalse(event.presentation.isEnabledAndVisible, "(F,T) inactive variant must disable")

        every { AccentContext.isAccentActive() } returns true
        every { LicenseChecker.isLicensedOrGrace() } returns false
        action.update(event)
        assertFalse(event.presentation.isEnabledAndVisible, "(T,F) unlicensed must disable")

        every { AccentContext.isAccentActive() } returns false
        every { LicenseChecker.isLicensedOrGrace() } returns false
        action.update(event)
        assertFalse(event.presentation.isEnabledAndVisible, "(F,F) both off must disable — locks AND vs OR")
    }

    @Test
    fun `update is visible in external accent context`() {
        val event = newEvent()
        every { AyuVariant.isAyuActive() } returns false
        every { AccentContext.isAccentActive() } returns true
        every { LicenseChecker.isLicensedOrGrace() } returns true

        CopyHexAction().update(event)

        assertTrue(event.presentation.isEnabledAndVisible)
    }

    @Test
    fun `actionPerformed writes resolved hex to clipboard via StringSelection`() {
        val captured = slot<Transferable>()
        every { mockClipboard.setContents(capture(captured)) } returns Unit
        CopyHexAction().actionPerformed(newEvent())
        verify(exactly = 1) { mockClipboard.setContents(any()) }
        assertEquals("#FFCC66", captured.captured.getTransferData(DataFlavor.stringFlavor))
    }

    @Test
    fun `actionPerformed reads hex at invocation time so consecutive calls reflect fresh resolution`() {
        // Stale-state regression lock. Two invocations with different resolver
        // answers must produce two distinct clipboard writes; a cached field
        // implementation would write the first value twice.
        val captured = mutableListOf<Transferable>()
        every { mockClipboard.setContents(any()) } answers {
            captured += firstArg<Transferable>()
        }
        every { AccentResolver.resolve(any(), any<AccentContext>()) } returns "#FFCC66"
        CopyHexAction().actionPerformed(newEvent())
        every { AccentResolver.resolve(any(), any<AccentContext>()) } returns "#5CCFE6"
        CopyHexAction().actionPerformed(newEvent())
        assertEquals(2, captured.size)
        assertEquals("#FFCC66", captured[0].getTransferData(DataFlavor.stringFlavor))
        assertEquals("#5CCFE6", captured[1].getTransferData(DataFlavor.stringFlavor))
    }

    @Test
    fun `actionPerformed is a no-op when AyuVariant detect returns null (belt-and-braces)`() {
        every { AccentContext.detect() } returns null
        CopyHexAction().actionPerformed(newEvent())
        verify(exactly = 0) { mockClipboard.setContents(any()) }
    }
}
