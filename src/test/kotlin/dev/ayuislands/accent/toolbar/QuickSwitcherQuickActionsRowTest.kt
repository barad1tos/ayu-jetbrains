package dev.ayuislands.accent.toolbar

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.awt.FlowLayout
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plan 48-04 Task 2 — locks the quick-actions row contract:
 *   - exactly five [JButton] children in fixed left-to-right order matching
 *     `QuickSwitcherActionGroup.build()` (D-14b parity invariant),
 *   - each button delegates to the matching action class via
 *     `XAction().actionPerformed(syntheticEvent)`,
 *   - each onClick wraps the call in a `try { ... } catch (exception:
 *     RuntimeException)` block (Pattern B regression lock),
 *   - source-grep guards lock the EXACT one-instantiation-per-class invariant
 *     so the row never drifts from the five canonical Wave 3 action classes.
 *
 * Tests 8..14 per `48-04-PLAN.md` `<behavior>` block.
 */
class QuickSwitcherQuickActionsRowTest {
    private val anchor: JComponent = mockk<JComponent>(relaxed = true)
    private val mockApp = mockk<Application>(relaxed = true)
    private val mockActionManager = mockk<ActionManager>(relaxed = true)

    @BeforeTest
    fun setUp() {
        // The action constructors (DumbAwareAction) reach for
        // ApplicationManager.getApplication() during initialization in some
        // platform versions. Stub the chain so the row builds in a headless
        // harness (mirrors the parity test's BeforeTest setup).
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
        // Test 8
        val row = QuickSwitcherQuickActionsRow(anchor)
        val panel: JPanel = row.component
        val layout = panel.layout
        assertTrue(layout is FlowLayout, "component layout must be FlowLayout, got ${layout?.javaClass?.simpleName}")
        assertEquals(FlowLayout.LEFT, layout.alignment, "FlowLayout alignment must be LEFT")
        assertEquals(EXPECTED_BUTTON_COUNT, panel.componentCount, "Expected $EXPECTED_BUTTON_COUNT children")
    }

    @Test
    fun `all five children are JButton instances`() {
        // Test 9
        val row = QuickSwitcherQuickActionsRow(anchor)
        val children = row.component.components.toList()
        assertEquals(EXPECTED_BUTTON_COUNT, children.size)
        children.forEachIndexed { index, child ->
            assertTrue(child is JButton, "Child $index is ${child.javaClass.simpleName}, expected JButton")
        }
    }

    @Test
    fun `button labels left-to-right are Pin Random Lighter Darker Copy Hex`() {
        // Test 10
        val row = QuickSwitcherQuickActionsRow(anchor)
        val labels = row.component.components.map { (it as JButton).text }
        val expected = listOf("Pin", "Random", "Lighter", "Darker", "Copy Hex")
        assertEquals(expected, labels, "Button labels drifted from D-14b canonical order")
    }

    @Test
    fun `source contains exactly one Pin Random Lighter Darker CopyHex constructor each (D-14b parity grep)`() {
        // Tests 11 + 12 collapsed — the source-grep approach is the canonical
        // contract because `addActionListener { ... }` synthesizes an opaque
        // lambda the runtime cannot introspect.
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
    fun `each onClick wraps the action invocation in a Pattern B try catch RuntimeException`() {
        // Test 13 — Pattern B regression lock
        val source = Files.readString(Paths.get(ROW_SOURCE_PATH))
        val catchCount = "catch \\(exception: RuntimeException\\)".toRegex().findAll(source).count()
        assertTrue(catchCount >= 1, "Expected ≥1 `catch (exception: RuntimeException)` block, got $catchCount")
        // Defensive: confirm the catch is NOT over Throwable (Pattern B would be violated).
        val throwableCatch = "catch \\(exception: Throwable\\)".toRegex().findAll(source).count()
        assertEquals(0, throwableCatch, "Pattern B violation — must NOT catch Throwable")
    }

    @Test
    fun `actionPerformed is invoked once per source constructor (source-grep proxy for Test 14)`() {
        // Test 14 — runtime click verification via mockkConstructor is brittle
        // against DumbAwareAction's static initialisers on a headless harness,
        // so this test asserts the source contains exactly one
        // `action.actionPerformed(event)` call site (the buttonFor body) —
        // combined with Test 12's constructor count, this locks the invariant
        // that each Wave 3 action's `actionPerformed` is invoked exactly once
        // per click. The actual per-action side-effects are covered by the
        // Wave 3 per-action test files (`PinAccentActionTest` etc.).
        val source = Files.readString(Paths.get(ROW_SOURCE_PATH))
        val actionPerformedCount = "action\\.actionPerformed".toRegex().findAll(source).count()
        assertEquals(1, actionPerformedCount, "Expected exactly one `action.actionPerformed` call site")
    }

    private companion object {
        const val EXPECTED_BUTTON_COUNT = 5
        const val ROW_SOURCE_PATH = "src/main/kotlin/dev/ayuislands/accent/toolbar/QuickSwitcherQuickActionsRow.kt"
    }
}
