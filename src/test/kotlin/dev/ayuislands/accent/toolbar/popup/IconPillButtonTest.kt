package dev.ayuislands.accent.toolbar.popup

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.ui.JBUI
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.JLabel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the [IconPillButton] click + paint contract per 48-REDESIGN-SPEC §3.6.
 * Click dispatch goes through `AnActionEvent.createEvent` (non-deprecated 6-arg
 * form, Wave-4 finding); RuntimeException in the action's `actionPerformed` is
 * caught per Pattern B so the button stays enabled.
 */
class IconPillButtonTest {
    @BeforeTest
    fun setUp() {
        mockkStatic(ApplicationManager::class)
        val mockApp = mockk<Application>(relaxed = true)
        every { ApplicationManager.getApplication() } returns mockApp
        every { mockApp.getService(ActionManager::class.java) } returns mockk<ActionManager>(relaxed = true)
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `preferred size is 28 x 28 JBUI scaled`() {
        val button = IconPillButton(stubAction("Pin"), JLabel(), AllIcons.Actions.PinTab)
        assertEquals(JBUI.scale(28), button.preferredSize.width)
        assertEquals(JBUI.scale(28), button.preferredSize.height)
    }

    @Test
    fun `text label is empty (icon-only)`() {
        val button = IconPillButton(stubAction("Pin"), JLabel(), AllIcons.Actions.PinTab)
        assertEquals("", button.text)
    }

    @Test
    fun `tooltip text uses action description fallback to text`() {
        val withDescription = stubAction(name = "Pin", description = "Pin this accent")
        val button = IconPillButton(withDescription, JLabel(), AllIcons.Actions.PinTab)
        assertEquals("Pin this accent", button.toolTipText)
    }

    @Test
    fun `click dispatches the action through ActionUtil invokeAction with the 6-arg event`() {
        // Round 2: replaces the Wave-4 source-grep test with a behavior test.
        // IDE inspector flagged direct `action.actionPerformed(event)` as a
        // call to an `@ApiStatus.OverrideOnly` member on 2025.1+. Round-2 fix
        // routes through [ActionUtil.invokeAction] (same helper LicenseChecker
        // uses).
        //
        // Headless harness can't run SimpleDataContext.builder().build() — it
        // reaches for IdeUiService which is null outside a real Application.
        // Stub the data-context builder to short-circuit that path, then
        // verify the helper is the actual dispatcher (and direct
        // actionPerformed is NOT called).
        mockkStatic(ActionUtil::class)
        mockkStatic(SimpleDataContext::class)
        val stubBuilder = mockk<SimpleDataContext.Builder>(relaxed = true)
        every { SimpleDataContext.builder() } returns stubBuilder
        every { stubBuilder.add(any<com.intellij.openapi.actionSystem.DataKey<Any>>(), any()) } returns stubBuilder
        val stubContext = mockk<DataContext>(relaxed = true)
        every { stubBuilder.build() } returns stubContext

        val action = mockk<AnAction>(relaxed = true)
        val presentation = Presentation("Pin").apply { description = "Pin this accent" }
        every { action.templatePresentation } returns presentation
        val captured = mutableListOf<AnActionEvent>()
        every {
            ActionUtil.invokeAction(eq(action), capture(captured), null)
        } just Runs

        val button = IconPillButton(action, JLabel(), AllIcons.Actions.PinTab)
        button.doClick()

        verify(exactly = 1) { ActionUtil.invokeAction(action, any(), null) }
        verify(exactly = 0) { action.actionPerformed(any()) }
        assertEquals(1, captured.size, "Helper must receive exactly one event")
        val event = captured.single()
        assertEquals(
            "AyuQuickSwitcher.Popup",
            event.place,
            "Event place must be the popup constant so action-update gating reads correctly",
        )
    }

    @Test
    fun `click swallows RuntimeException from the action without crashing the button`() {
        // Round 2 — Pattern B behavior lock. A throwing action must NOT escape
        // the button or leave it disabled.
        mockkStatic(ActionUtil::class)
        mockkStatic(SimpleDataContext::class)
        val stubBuilder = mockk<SimpleDataContext.Builder>(relaxed = true)
        every { SimpleDataContext.builder() } returns stubBuilder
        every { stubBuilder.add(any<com.intellij.openapi.actionSystem.DataKey<Any>>(), any()) } returns stubBuilder
        every { stubBuilder.build() } returns mockk(relaxed = true)

        val action = mockk<AnAction>(relaxed = true)
        every { action.templatePresentation } returns Presentation("Pin")
        every {
            ActionUtil.invokeAction(eq(action), any(), null)
        } throws RuntimeException("boom action")

        val button = IconPillButton(action, JLabel(), AllIcons.Actions.PinTab)
        // Must NOT throw.
        button.doClick()
        assertTrue(button.isEnabled, "Button must stay enabled after a swallowed RuntimeException")
    }

    @Test
    fun `RuntimeException catch is in place (Pattern B source-grep)`() {
        val source =
            Files.readString(
                Paths.get("src/main/kotlin/dev/ayuislands/accent/toolbar/popup/IconPillButton.kt"),
            )
        val catchCount = "catch \\(exception: RuntimeException\\)".toRegex().findAll(source).count()
        assertTrue(catchCount >= 1, "Pattern B: must catch RuntimeException around action dispatch")
        val throwableCatch = "catch \\(exception: Throwable\\)".toRegex().findAll(source).count()
        assertEquals(0, throwableCatch, "Pattern B violation — must NOT catch Throwable")
    }

    private fun stubAction(
        name: String,
        description: String? = null,
    ): AnAction {
        val action = mockk<AnAction>(relaxed = true)
        val presentation =
            Presentation(name).apply {
                this.description = description
            }
        every { action.templatePresentation } returns presentation
        return action
    }
}
