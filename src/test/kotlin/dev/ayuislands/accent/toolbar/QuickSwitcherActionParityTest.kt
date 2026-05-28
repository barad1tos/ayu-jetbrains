package dev.ayuislands.accent.toolbar

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import dev.ayuislands.accent.toolbar.actions.CopyHexAction
import dev.ayuislands.accent.toolbar.actions.DarkerAccentAction
import dev.ayuislands.accent.toolbar.actions.LighterAccentAction
import dev.ayuislands.accent.toolbar.actions.PinAccentAction
import dev.ayuislands.accent.toolbar.actions.QuickSwitcherActionGroup
import dev.ayuislands.accent.toolbar.actions.RandomAccentAction
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

/**
 * Quick-switcher canonical action-group lock.
 *
 * `QuickSwitcherActionGroup.build()` is the SINGLE source of truth for the
 * chip's right-click context menu. The popup row has its own Swing-component
 * tests because it renders buttons instead of an IntelliJ `ActionGroup`.
 */
class QuickSwitcherActionParityTest {
    private val mockApp = mockk<Application>(relaxed = true)
    private val mockActionManager = mockk<ActionManager>(relaxed = true)

    @BeforeTest
    fun setUp() {
        // DefaultActionGroup.add(action) calls ActionManager.getInstance() internally
        // for registration. Stub the chain so the group builds in a headless harness.
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns mockApp
        every { mockApp.getService(ActionManager::class.java) } returns mockActionManager
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `QuickSwitcherActionGroup build has exactly 5 children`() {
        val group = QuickSwitcherActionGroup.build()
        val children = group.childActionsOrStubs.toList()
        assertEquals(5, children.size, "Expected 5 children, got: $children")
    }

    @Test
    fun `children appear in fixed order Pin Random Lighter Darker CopyHex`() {
        val group = QuickSwitcherActionGroup.build()
        val expected =
            listOf(
                PinAccentAction::class.java.simpleName,
                RandomAccentAction::class.java.simpleName,
                LighterAccentAction::class.java.simpleName,
                DarkerAccentAction::class.java.simpleName,
                CopyHexAction::class.java.simpleName,
            )
        val actual = group.childActionsOrStubs.map { it::class.java.simpleName }
        assertEquals(expected, actual, "QuickSwitcherActionGroup child order drifted")
    }

    @Test
    fun `build returns a fresh DefaultActionGroup on each call (no shared mutable state)`() {
        // Sharing one group between two surfaces creates double-fire bugs.
        // Each consumer (chip RMB, popup row) gets its own instance.
        assertNotSame(QuickSwitcherActionGroup.build(), QuickSwitcherActionGroup.build())
    }
}
