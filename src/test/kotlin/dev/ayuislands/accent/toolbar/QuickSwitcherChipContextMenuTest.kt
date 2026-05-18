package dev.ayuislands.accent.toolbar

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPopupMenu
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.MessageBusConnection
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.toolbar.actions.CopyHexAction
import dev.ayuislands.accent.toolbar.actions.DarkerAccentAction
import dev.ayuislands.accent.toolbar.actions.LighterAccentAction
import dev.ayuislands.accent.toolbar.actions.PinAccentAction
import dev.ayuislands.accent.toolbar.actions.RandomAccentAction
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.Component
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPopupMenu
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the chip's right-click context-menu wiring (Plan 48-03 Wave 3).
 *
 *  - RMB invokes `ActionManager.createActionPopupMenu("AyuQuickSwitcher.ContextMenu", group)`.
 *  - The passed group has 5 children matching the canonical order.
 *  - When LAF is non-Ayu (`AyuVariant.detect() == null`), RMB is a no-op —
 *    early return in `mousePressed` before reaching `showContextMenu`.
 *
 * Tests 38..40 per `48-03-PLAN.md` `<behavior>`.
 */
class QuickSwitcherChipContextMenuTest {
    private val mockApplication = mockk<Application>(relaxed = true)
    private val mockMessageBus = mockk<MessageBus>(relaxed = true)
    private val mockConnection = mockk<MessageBusConnection>(relaxed = true)
    private val mockActionManager = mockk<ActionManager>(relaxed = true)
    private val mockMenu = mockk<ActionPopupMenu>(relaxed = true)
    private val mockMenuComponent = mockk<JPopupMenu>(relaxed = true)

    @BeforeTest
    fun setUp() {
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns mockApplication
        every { mockApplication.messageBus } returns mockMessageBus
        every { mockMessageBus.connect(any<Disposable>()) } returns mockConnection

        mockkStatic(ActionManager::class)
        every { ActionManager.getInstance() } returns mockActionManager
        every { mockActionManager.createActionPopupMenu(any(), any()) } returns mockMenu
        every { mockMenu.component } returns mockMenuComponent
        every { mockMenuComponent.show(any<Component>(), any(), any()) } returns Unit

        mockkObject(AyuVariant.Companion)
        every { AyuVariant.isAyuActive() } returns true
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    private fun rightClick(source: JComponent) =
        MouseEvent(
            source,
            MouseEvent.MOUSE_PRESSED,
            0L,
            MouseEvent.BUTTON3_DOWN_MASK,
            5,
            7,
            1,
            false,
            MouseEvent.BUTTON3,
        )

    @Test
    fun `mousePressed RMB invokes ActionManager createActionPopupMenu with the AyuQuickSwitcher ContextMenu place`() {
        // Test 38
        val chip = QuickSwitcherChipComponent()
        chip.dispatchEvent(rightClick(chip))

        verify(exactly = 1) {
            mockActionManager.createActionPopupMenu("AyuQuickSwitcher.ContextMenu", any())
        }
        verify(exactly = 1) { mockMenuComponent.show(chip, 5, 7) }
    }

    @Test
    fun `passed ActionGroup has 5 children in the canonical Pin-Random-Lighter-Darker-CopyHex order`() {
        // Test 39 — capture the group passed to ActionManager and verify
        // child shape; the same predicate the parity test uses.
        val captured = slot<ActionGroup>()
        every { mockActionManager.createActionPopupMenu(any(), capture(captured)) } returns mockMenu

        val chip = QuickSwitcherChipComponent()
        chip.dispatchEvent(rightClick(chip))

        val group = captured.captured as com.intellij.openapi.actionSystem.DefaultActionGroup
        val classes = group.childActionsOrStubs.map { it::class.java.simpleName }
        assertEquals(
            listOf(
                PinAccentAction::class.java.simpleName,
                RandomAccentAction::class.java.simpleName,
                LighterAccentAction::class.java.simpleName,
                DarkerAccentAction::class.java.simpleName,
                CopyHexAction::class.java.simpleName,
            ),
            classes,
        )
    }

    @Test
    fun `showContextMenu swallows RuntimeException from createActionPopupMenu (Pattern B)`() {
        // Pattern B regression lock — the ActionManager can throw on a
        // mid-shutdown action-group resolution race. The chip mouse handler
        // must absorb the throw so the chip stays usable for the next click.
        every {
            mockActionManager.createActionPopupMenu(any(), any())
        } throws RuntimeException("shutdown race")

        val chip = QuickSwitcherChipComponent()
        // Must NOT throw.
        chip.dispatchEvent(rightClick(chip))
    }

    @Test
    fun `showContextMenu swallows RuntimeException from menu show on disposed peer (Pattern B)`() {
        // Pattern B — `JPopupMenu.show` can throw on a disposed AWT peer.
        every {
            mockMenuComponent.show(any<Component>(), any(), any())
        } throws RuntimeException("disposed peer")

        val chip = QuickSwitcherChipComponent()
        // Must NOT throw.
        chip.dispatchEvent(rightClick(chip))
        // The menu build still succeeded; only the `show` call threw.
        verify(exactly = 1) { mockActionManager.createActionPopupMenu(any(), any()) }
    }

    @Test
    fun `mousePressed RMB is a no-op when AyuVariant detect returns null (WIDGET-11)`() {
        // Test 40 — Wave 2 guard. createActionPopupMenu must NOT be reached
        // when LAF is non-Ayu; the early return in `mousePressed` short-circuits.
        every { AyuVariant.isAyuActive() } returns false
        every { AyuVariant.detect() } returns null

        val chip = QuickSwitcherChipComponent()
        chip.dispatchEvent(rightClick(chip))

        verify(exactly = 0) { mockActionManager.createActionPopupMenu(any(), any()) }
    }
}
