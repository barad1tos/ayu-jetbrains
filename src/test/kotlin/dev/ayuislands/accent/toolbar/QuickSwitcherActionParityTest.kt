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
 * D-14b parity lock — the single most important test in Plan 48-03.
 *
 * `QuickSwitcherActionGroup.build()` is the SINGLE source of truth for the
 * five quick-action classes that appear in BOTH the chip's right-click
 * context menu (Wave 3, this Plan) AND the popup's premium quick-actions
 * row (Wave 4, Plan 48-04). Divergence between the two surfaces is
 * forbidden by D-14b; this test reads the consumer files at test time and
 * asserts they reference the canonical group, NOT inline action
 * constructors.
 *
 * A future Wave 4 edit that inlines `PinAccentAction()` directly in the
 * popup row (bypassing the group) fails this test — that is the intended
 * regression signal.
 *
 * Tests 34..37 per `48-03-PLAN.md` `<behavior>`.
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
        // Test 34
        val group = QuickSwitcherActionGroup.build()
        val children = group.childActionsOrStubs.toList()
        assertEquals(5, children.size, "Expected 5 children, got: $children")
    }

    @Test
    fun `children appear in fixed order Pin Random Lighter Darker CopyHex`() {
        // Test 35
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
        assertEquals(expected, actual, "QuickSwitcherActionGroup child order drifted (D-14b violation)")
    }

    @Test
    fun `consumer files reference QuickSwitcherActionGroup and avoid inline action constructors (D-14b)`() {
        // Test 36 — parity grep. The chip (Wave 3) MUST reference the
        // canonical group. The popup (Wave 2 placeholder, Wave 4 fills the
        // premium row) MUST NOT inline any of the five action class names
        // outside the group — Wave 4 should also reach for `QuickSwitcherActionGroup`.
        val chipPath = Paths.get("src/main/kotlin/dev/ayuislands/accent/toolbar/QuickSwitcherChipComponent.kt")
        val popupPath = Paths.get("src/main/kotlin/dev/ayuislands/accent/toolbar/QuickSwitcherPopup.kt")

        val chipSource = Files.readString(chipPath)
        assertTrue(
            chipSource.contains("QuickSwitcherActionGroup"),
            "QuickSwitcherChipComponent must reference QuickSwitcherActionGroup (D-14b)",
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
                "QuickSwitcherPopup must NOT inline `$forbidden` — go through QuickSwitcherActionGroup (D-14b)",
            )
        }
    }

    @Test
    fun `build returns a fresh DefaultActionGroup on each call (no shared mutable state)`() {
        // Test 37 — sharing one group between two surfaces creates double-fire
        // bugs. Each consumer (chip RMB, popup row) gets its own instance.
        assertNotSame(QuickSwitcherActionGroup.build(), QuickSwitcherActionGroup.build())
    }
}
