package dev.ayuislands.accent.toolbar

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.toolbar.actions.CopyHexAction
import dev.ayuislands.accent.toolbar.actions.DarkerAccentAction
import dev.ayuislands.accent.toolbar.actions.LighterAccentAction
import dev.ayuislands.accent.toolbar.actions.PinAccentAction
import dev.ayuislands.accent.toolbar.actions.RandomAccentAction
import dev.ayuislands.accent.toolbar.popup.IconPillButton
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Quick-actions row coverage:
 *   - exactly five [IconPillButton] children in fixed left-to-right order matching
 *     `QuickSwitcherActionGroup.build()` (parity invariant),
 *   - each button is 28x28 icon-only with a non-empty tooltip.
 */
class QuickSwitcherQuickActionsRowTest {
    private val anchor: JComponent = mockk<JComponent>(relaxed = true)
    private val mockApp = mockk<Application>(relaxed = true)
    private val mockActionManager = mockk<ActionManager>(relaxed = true)

    @BeforeTest
    fun setUp() {
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns mockApp
        every { mockApp.getService(ActionManager::class.java) } returns mockActionManager
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `component is a JPanel with FlowLayout LEFT and five children`() {
        val row = QuickSwitcherQuickActionsRow(anchor, AccentContext.Ayu(AyuVariant.MIRAGE))
        val panel: JPanel = row.component
        val layout = panel.layout
        assertTrue(layout is FlowLayout, "component layout must be FlowLayout, got ${layout?.javaClass?.simpleName}")
        assertEquals(FlowLayout.LEFT, layout.alignment, "FlowLayout alignment must be LEFT")
        assertEquals(EXPECTED_BUTTON_COUNT, panel.componentCount, "Expected $EXPECTED_BUTTON_COUNT children")
    }

    @Test
    fun `all five children are IconPillButton instances 28x28 icon-only with tooltips`() {
        val row = QuickSwitcherQuickActionsRow(anchor, AccentContext.Ayu(AyuVariant.MIRAGE))
        val children = row.component.components.toList()
        assertEquals(EXPECTED_BUTTON_COUNT, children.size)
        val expectedSize = Dimension(JBUI.scale(28), JBUI.scale(28))
        children.forEachIndexed { index, child ->
            val kind = child.javaClass.simpleName
            assertTrue(child is IconPillButton, "Child $index is $kind, expected IconPillButton")
            assertEquals(expectedSize, child.preferredSize, "Child $index size drift")
            assertEquals("", child.text, "Child $index must be icon-only (empty text)")
            assertTrue(child.toolTipText.isNotEmpty(), "Child $index tooltip must be set")
        }
    }

    @Test
    fun `external context omits Ayu-only Pin action`() {
        val row = QuickSwitcherQuickActionsRow(anchor, AccentContext.External)
        val actions =
            row.component.components
                .filterIsInstance<IconPillButton>()
                .map { it.action::class.java.simpleName }

        assertEquals(
            listOf(
                RandomAccentAction::class.java.simpleName,
                LighterAccentAction::class.java.simpleName,
                DarkerAccentAction::class.java.simpleName,
                CopyHexAction::class.java.simpleName,
            ),
            actions,
        )
        assertTrue(PinAccentAction::class.java.simpleName !in actions)
    }

    private companion object {
        const val EXPECTED_BUTTON_COUNT = 5
    }
}
