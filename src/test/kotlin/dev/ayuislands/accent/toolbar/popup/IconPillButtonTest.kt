package dev.ayuislands.accent.toolbar.popup

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.ActionCallback
import com.intellij.util.ui.JBUI
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import javax.swing.JLabel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the [IconPillButton] click + paint contract.
 * Click dispatch goes through `AnActionEvent.createEvent` (non-deprecated
 * 6-arg form); RuntimeException in the action's `actionPerformed` is caught
 * per Pattern B so the button stays enabled.
 */
class IconPillButtonTest {
    private lateinit var actionManager: ActionManager

    @BeforeTest
    fun setUp() {
        mockkStatic(ApplicationManager::class)
        val mockApp = mockk<Application>(relaxed = true)
        actionManager = mockk(relaxed = true)
        every { ApplicationManager.getApplication() } returns mockApp
        every { mockApp.getService(ActionManager::class.java) } returns actionManager
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `preferred size is 28 x 28 JBUI scaled`() {
        val button = IconPillButton(stubPinAction(), JLabel(), AllIcons.Actions.PinTab)
        assertEquals(JBUI.scale(28), button.preferredSize.width)
        assertEquals(JBUI.scale(28), button.preferredSize.height)
    }

    @Test
    fun `text label is empty (icon-only)`() {
        val button = IconPillButton(stubPinAction(), JLabel(), AllIcons.Actions.PinTab)
        assertEquals("", button.text)
    }

    @Test
    fun `tooltip text uses action description fallback to text`() {
        val withDescription = stubPinAction(description = "Pin this accent")
        val button = IconPillButton(withDescription, JLabel(), AllIcons.Actions.PinTab)
        assertEquals("Pin this accent", button.toolTipText)
    }

    @Test
    fun `click dispatches the action through ActionManager tryToExecute`() {
        // Behavior test: direct `action.actionPerformed(event)` is a call to
        // an `@ApiStatus.OverrideOnly` member on 2025.1+, so the dispatch
        // routes through the platform action dispatcher.

        val action = mockk<AnAction>(relaxed = true)
        val presentation = Presentation("Pin").apply { description = "Pin this accent" }
        every { action.templatePresentation } returns presentation
        every {
            actionManager.tryToExecute(eq(action), null, any(), eq("AyuQuickSwitcher.Popup"), true)
        } returns ActionCallback.DONE

        val button = IconPillButton(action, JLabel(), AllIcons.Actions.PinTab)
        button.doClick()

        verify(exactly = 1) {
            actionManager.tryToExecute(action, null, any(), "AyuQuickSwitcher.Popup", true)
        }
    }

    @Test
    fun `click swallows RuntimeException from the action without crashing the button`() {
        // Pattern B behavior lock. A throwing action must NOT escape the
        // button or leave it disabled.
        val action = mockk<AnAction>(relaxed = true)
        every { action.templatePresentation } returns Presentation("Pin")
        every {
            actionManager.tryToExecute(eq(action), null, any(), eq("AyuQuickSwitcher.Popup"), true)
        } throws RuntimeException("boom action")

        val button = IconPillButton(action, JLabel(), AllIcons.Actions.PinTab)
        // Must NOT throw.
        button.doClick()
        assertTrue(button.isEnabled, "Button must stay enabled after a swallowed RuntimeException")
    }

    private fun stubPinAction(description: String? = null): AnAction {
        val action = mockk<AnAction>(relaxed = true)
        val presentation =
            Presentation("Pin").apply {
                this.description = description
            }
        every { action.templatePresentation } returns presentation
        return action
    }
}
