package dev.ayuislands.accent.toolbar.popup

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.ui.JBUI
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
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
    fun `source dispatches click through 6-arg AnActionEvent createEvent (Wave-4 form)`() {
        // Runtime click verification is brittle under a headless harness because
        // SimpleDataContext.builder() reaches for IDE services that fail-fast outside
        // a real Application. Mirror the Wave-4 QuickSwitcherQuickActionsRowTest
        // approach: source-grep that the 6-arg createEvent path is wired exactly once.
        val source =
            Files.readString(
                Paths.get("src/main/kotlin/dev/ayuislands/accent/toolbar/popup/IconPillButton.kt"),
            )
        val createEventCount = "AnActionEvent\\.createEvent\\(".toRegex().findAll(source).count()
        assertEquals(1, createEventCount, "Expected exactly one AnActionEvent.createEvent(...) call site")
        val actionPerformedCount = "action\\.actionPerformed\\(".toRegex().findAll(source).count()
        assertEquals(1, actionPerformedCount, "Expected exactly one action.actionPerformed(...) call site")
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
