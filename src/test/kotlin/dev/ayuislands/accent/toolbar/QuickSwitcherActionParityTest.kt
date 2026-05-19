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
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Quick-switcher action parity lock.
 *
 * `QuickSwitcherActionGroup.build()` is the SINGLE source of truth for the
 * five quick-action classes that appear in BOTH the chip's right-click
 * context menu AND the popup's premium quick-actions row. Divergence
 * between the two surfaces is forbidden; this test reads the consumer
 * files at test time and asserts they reference the canonical group, NOT
 * inline action constructors.
 *
 * A future edit that inlines `PinAccentAction()` directly in the popup
 * row (bypassing the group) fails this test — that is the intended
 * regression signal.
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
    fun `consumer files reference QuickSwitcherActionGroup and avoid inline action constructors`() {
        // Parity grep. The chip MUST reference the canonical group. The popup
        // MUST NOT inline any of the five action class names outside the group
        // — popup consumers should also reach for `QuickSwitcherActionGroup`.
        val chipPath = Paths.get("src/main/kotlin/dev/ayuislands/accent/toolbar/QuickSwitcherChipComponent.kt")
        val popupPath = Paths.get("src/main/kotlin/dev/ayuislands/accent/toolbar/QuickSwitcherPopup.kt")

        val chipSource = Files.readString(chipPath)
        assertTrue(
            chipSource.contains("QuickSwitcherActionGroup"),
            "QuickSwitcherChipComponent must reference QuickSwitcherActionGroup",
        )

        val popupSource = Files.readString(popupPath)
        val inlineForbidden =
            listOf(
                "PinAccentAction(",
                "RandomAccentAction(",
                "LighterAccentAction(",
                "DarkerAccentAction(",
                "CopyHexAction(",
            )
        for (forbidden in inlineForbidden) {
            assertFalse(
                popupSource.contains(forbidden),
                "QuickSwitcherPopup must NOT inline `$forbidden` — go through QuickSwitcherActionGroup",
            )
        }
    }

    @Test
    fun `build returns a fresh DefaultActionGroup on each call (no shared mutable state)`() {
        // Sharing one group between two surfaces creates double-fire bugs.
        // Each consumer (chip RMB, popup row) gets its own instance.
        assertNotSame(QuickSwitcherActionGroup.build(), QuickSwitcherActionGroup.build())
    }

    @Test
    fun `popup quick-actions row uses each action class exactly once`() {
        // Parity invariant for the popup row.
        // `QuickSwitcherQuickActionsRow` instantiates the five action classes
        // directly (NOT via the group, because the popup row is a Swing
        // `JPanel` of `JButton`s, not a `DefaultActionGroup`). Source-grep at
        // test time is the regression lock — any future edit that drops,
        // duplicates, or swaps a constructor breaks the parity invariant.
        val rowPath = Paths.get("src/main/kotlin/dev/ayuislands/accent/toolbar/QuickSwitcherQuickActionsRow.kt")
        val body = Files.readString(rowPath)
        for (ctor in CANONICAL_CONSTRUCTORS) {
            val matches = body.split(ctor).size - 1
            assertEquals(1, matches, "Expected exactly one `$ctor` in QuickSwitcherQuickActionsRow.kt, got $matches")
        }
    }

    @Test
    fun `quick-actions row constructor order matches QuickSwitcherActionGroup build order`() {
        // Ordering invariant. Read both files, regex-collect the
        // `(Pin|Random|Lighter|Darker)AccentAction()|CopyHexAction()`
        // constructor calls (trailing `()` excludes import statements which
        // share the class names but appear alphabetically sorted in imports).
        val groupPath = Paths.get("src/main/kotlin/dev/ayuislands/accent/toolbar/actions/QuickSwitcherActionGroup.kt")
        val rowPath = Paths.get("src/main/kotlin/dev/ayuislands/accent/toolbar/QuickSwitcherQuickActionsRow.kt")
        val classRegex = "((Pin|Random|Lighter|Darker)AccentAction|CopyHexAction)\\(\\)".toRegex()
        val groupOrder = classRegex.findAll(Files.readString(groupPath)).map { it.groupValues[1] }.toList()
        val rowOrder = classRegex.findAll(Files.readString(rowPath)).map { it.groupValues[1] }.toList()
        val expected =
            listOf(
                "PinAccentAction",
                "RandomAccentAction",
                "LighterAccentAction",
                "DarkerAccentAction",
                "CopyHexAction",
            )
        assertEquals(expected, groupOrder, "QuickSwitcherActionGroup constructor order drifted")
        assertEquals(expected, rowOrder, "QuickSwitcherQuickActionsRow constructor order drifted")
        assertEquals(groupOrder, rowOrder, "Group and row constructor orders MUST match")
    }

    private companion object {
        val CANONICAL_CONSTRUCTORS =
            listOf(
                "PinAccentAction()",
                "RandomAccentAction()",
                "LighterAccentAction()",
                "DarkerAccentAction()",
                "CopyHexAction()",
            )
    }
}
