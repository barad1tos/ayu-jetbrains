package dev.ayuislands.accent.toolbar

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.toolbar.popup.IconPillButton
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.awt.Dimension
import java.awt.FlowLayout
import java.nio.file.Files
import java.nio.file.Paths
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
 *   - each button is 28x28 icon-only with a non-empty tooltip,
 *   - source-grep locks the exact constructor count + Pattern B catch contract.
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
        val row = QuickSwitcherQuickActionsRow(anchor)
        val panel: JPanel = row.component
        val layout = panel.layout
        assertTrue(layout is FlowLayout, "component layout must be FlowLayout, got ${layout?.javaClass?.simpleName}")
        assertEquals(FlowLayout.LEFT, layout.alignment, "FlowLayout alignment must be LEFT")
        assertEquals(EXPECTED_BUTTON_COUNT, panel.componentCount, "Expected $EXPECTED_BUTTON_COUNT children")
    }

    @Test
    fun `all five children are IconPillButton instances 28x28 icon-only with tooltips`() {
        val row = QuickSwitcherQuickActionsRow(anchor)
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
    fun `source contains exactly one Pin Random Lighter Darker CopyHex constructor each (parity grep)`() {
        val source = Files.readString(Paths.get(ROW_SOURCE_PATH))
        val constructors =
            listOf(
                "PinAccentAction()",
                "RandomAccentAction()",
                "LighterAccentAction()",
                "DarkerAccentAction()",
                "CopyHexAction()",
            )
        for (ctor in constructors) {
            val count = source.split(ctor).size - 1
            assertEquals(1, count, "Expected EXACTLY one `$ctor` in QuickSwitcherQuickActionsRow.kt, got $count")
        }
    }

    @Test
    fun `source wraps each click in Pattern B catch (RuntimeException-only) — indirect via IconPillButton`() {
        // QuickSwitcherQuickActionsRow no longer carries its own catch — the Pattern
        // B wrap lives inside IconPillButton.kt. Source-grep that file to lock the
        // contract.
        val source = Files.readString(Paths.get(PILL_SOURCE_PATH))
        val catchCount = "catch \\(exception: RuntimeException\\)".toRegex().findAll(source).count()
        assertTrue(catchCount >= 1, "Expected ≥1 RuntimeException catch in IconPillButton, got $catchCount")
        val throwableCatch = "catch \\(exception: Throwable\\)".toRegex().findAll(source).count()
        assertEquals(0, throwableCatch, "Pattern B violation — must NOT catch Throwable")
    }

    @Test
    fun `actionPerformed call site lives in IconPillButton, exactly one occurrence`() {
        val source = Files.readString(Paths.get(PILL_SOURCE_PATH))
        val count = "action\\.actionPerformed\\(".toRegex().findAll(source).count()
        assertEquals(1, count, "Expected exactly one action.actionPerformed call site inside IconPillButton")
    }

    private companion object {
        const val EXPECTED_BUTTON_COUNT = 5
        const val ROW_SOURCE_PATH = "src/main/kotlin/dev/ayuislands/accent/toolbar/QuickSwitcherQuickActionsRow.kt"
        const val PILL_SOURCE_PATH = "src/main/kotlin/dev/ayuislands/accent/toolbar/popup/IconPillButton.kt"
    }
}
