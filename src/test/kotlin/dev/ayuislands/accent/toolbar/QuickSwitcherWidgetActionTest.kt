package dev.ayuislands.accent.toolbar

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D-02 + D-03 + D-06 two-conjunct visibility gate lock. The widget's `update()` must
 * read EXACTLY `AyuVariant.isAyuActive() && state.quickSwitcherWidgetEnabled` — no
 * license predicate (D-06 keeps the chip FREE), no third state field.
 *
 * Pattern J discipline — single-source-of-truth predicate, asserted by toggling
 * each conjunct independently and verifying the presentation flips correctly.
 */
class QuickSwitcherWidgetActionTest {
    private val action = QuickSwitcherWidgetAction()

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `getActionUpdateThread returns BGT`() {
        assertEquals(ActionUpdateThread.BGT, action.getActionUpdateThread())
    }

    @Test
    fun `update sets visible when Ayu LAF active AND toggle on`() {
        stubAyuActive(true)
        val state = stubSettingsState(quickSwitcherEnabled = true)
        val presentation = Presentation()
        val event = mockk<AnActionEvent>(relaxed = true)
        every { event.presentation } returns presentation

        action.update(event)

        assertTrue(
            presentation.isEnabledAndVisible,
            "Both conjuncts true => visible; state=$state",
        )
    }

    @Test
    fun `update sets invisible when Ayu LAF inactive (WIDGET-11)`() {
        stubAyuActive(false)
        stubSettingsState(quickSwitcherEnabled = true)
        val presentation = Presentation()
        val event = mockk<AnActionEvent>(relaxed = true)
        every { event.presentation } returns presentation

        action.update(event)

        assertFalse(
            presentation.isEnabledAndVisible,
            "AyuVariant.isAyuActive() false => chip hidden, even if toggle on (WIDGET-11)",
        )
    }

    @Test
    fun `update sets invisible when settings toggle off (D-02 inverse)`() {
        stubAyuActive(true)
        stubSettingsState(quickSwitcherEnabled = false)
        val presentation = Presentation()
        val event = mockk<AnActionEvent>(relaxed = true)
        every { event.presentation } returns presentation

        action.update(event)

        assertFalse(
            presentation.isEnabledAndVisible,
            "quickSwitcherWidgetEnabled false => chip hidden, even if LAF active",
        )
    }

    @Test
    fun `update sets invisible when BOTH LAF inactive AND toggle off (F,F locks AND vs OR — CRIT-7)`() {
        // CRIT-7 — without this case a future `&&` → `||` regression would land
        // silently because every other case (T,T enables, single-off disables)
        // is consistent with both AND and OR semantics. (F,F) is the only case
        // that distinguishes them.
        stubAyuActive(false)
        stubSettingsState(quickSwitcherEnabled = false)
        val presentation = Presentation()
        val event = mockk<AnActionEvent>(relaxed = true)
        every { event.presentation } returns presentation

        action.update(event)

        assertFalse(
            presentation.isEnabledAndVisible,
            "(F,F) both off must disable — proves the gate is AND, not OR",
        )
    }

    @Test
    fun `createCustomComponent returns a QuickSwitcherChipComponent instance`() {
        val component = action.createCustomComponent(Presentation(), "MainToolbarRight")
        assertTrue(
            component is QuickSwitcherChipComponent,
            "createCustomComponent must return a QuickSwitcherChipComponent; got ${component.javaClass}",
        )
    }

    @Test
    fun `updateCustomComponent calls refreshFromFocusedProject on the chip`() {
        val chip = spyk(QuickSwitcherChipComponent())
        every { chip.refreshFromFocusedProject() } just Runs

        action.updateCustomComponent(chip, Presentation())

        verify(exactly = 1) { chip.refreshFromFocusedProject() }
    }

    @Test
    fun `updateCustomComponent on a non-chip component is a no-op (safe cast)`() {
        // Defensive: if the platform ever passes a different JComponent (theoretical —
        // CustomComponentAction contract pairs createCustomComponent with this), the
        // safe `as?` cast must not throw.
        val notAChip = javax.swing.JLabel()
        // Should not throw.
        action.updateCustomComponent(notAChip, Presentation())
    }

    private fun stubAyuActive(active: Boolean) {
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.isAyuActive() } returns active
    }

    private fun stubSettingsState(quickSwitcherEnabled: Boolean): AyuIslandsState {
        val state = AyuIslandsState()
        state.quickSwitcherWidgetEnabled = quickSwitcherEnabled
        val settings = mockk<AyuIslandsSettings>(relaxed = true)
        every { settings.state } returns state
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings
        return state
    }
}
