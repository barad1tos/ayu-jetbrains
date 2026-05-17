package dev.ayuislands.accent.toolbar

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.laf.UIThemeLookAndFeelInfo
import dev.ayuislands.accent.AyuVariant
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.event.ActionEvent
import javax.swing.JCheckBox
import javax.swing.JRadioButton
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Plan 48-02 Task 3 — locks the variant switcher row contract:
 *   - 3 radios + 1 Islands UI checkbox,
 *   - initial selection / Islands UI initial state mirror the LAF,
 *   - radio click resolves theme name via [VariantThemeNameResolver] and
 *     applies via `LafManager.setCurrentLookAndFeel(laf, false)` (Pitfall 5),
 *   - null `findLaf` is a warn-and-return no-op (Pitfall 7 fail-safe),
 *   - toggling Islands UI re-applies the currently selected variant.
 */
class VariantSwitcherRowTest {
    private val lafManager = mockk<LafManager>(relaxed = true)
    private val mirageTheme = mockk<UIThemeLookAndFeelInfo>(relaxed = true)

    @BeforeTest
    fun setUp() {
        mockkStatic(LafManager::class)
        every { LafManager.getInstance() } returns lafManager
        every { lafManager.currentUIThemeLookAndFeel } returns mirageTheme
        every { mirageTheme.name } returns "Ayu Mirage"
        every { lafManager.findLaf(any()) } returns mirageTheme
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    private fun checkboxOf(row: VariantSwitcherRow): JCheckBox =
        row.component.components
            .filterIsInstance<JCheckBox>()
            .single()

    @Test
    fun `component has 3 radios + 1 Islands UI checkbox`() {
        val row = VariantSwitcherRow(AyuVariant.MIRAGE)
        val children = row.component.components
        assertEquals(4, children.size, "Expected 3 radios + 1 checkbox")
        val radios = children.filterIsInstance<JRadioButton>()
        val checkboxes = children.filterIsInstance<JCheckBox>()
        assertEquals(3, radios.size)
        assertEquals(1, checkboxes.size)
        assertEquals("Islands UI", checkboxes.single().text)
    }

    @Test
    fun `initial selection matches the initialVariant arg`() {
        val row = VariantSwitcherRow(AyuVariant.DARK)
        val radios = row.component.components.filterIsInstance<JRadioButton>()
        val dark = radios.single { it.text.equals("Dark", ignoreCase = true) }
        assertTrue(dark.isSelected, "Dark radio must be selected for initialVariant=DARK")
        radios.filter { it != dark }.forEach { assertFalse(it.isSelected) }
    }

    @Test
    fun `Islands UI checkbox reflects active LAF (false when non-Islands theme)`() {
        every { mirageTheme.name } returns "Ayu Mirage"
        val row = VariantSwitcherRow(AyuVariant.MIRAGE)
        val checkbox = checkboxOf(row)
        assertFalse(checkbox.isSelected, "Checkbox must be unselected for plain 'Ayu Mirage'")
    }

    @Test
    fun `Islands UI checkbox reflects active LAF (true when Islands UI theme)`() {
        every { mirageTheme.name } returns "Ayu Mirage (Islands UI)"
        val row = VariantSwitcherRow(AyuVariant.MIRAGE)
        val checkbox = checkboxOf(row)
        assertTrue(checkbox.isSelected, "Checkbox must be selected for '(Islands UI)' theme")
    }

    @Test
    fun `selecting a different radio calls findLaf then setCurrentLookAndFeel(laf, false) then updateUI`() {
        every { mirageTheme.name } returns "Ayu Mirage"
        val darkTheme = mockk<UIThemeLookAndFeelInfo>(relaxed = true)
        every { lafManager.findLaf("Ayu Dark") } returns darkTheme

        val row = VariantSwitcherRow(AyuVariant.MIRAGE)
        val radios = row.component.components.filterIsInstance<JRadioButton>()
        val darkRadio = radios.single { it.text.equals("Dark", ignoreCase = true) }
        darkRadio.isSelected = true
        darkRadio.doClick()

        verify { lafManager.findLaf("Ayu Dark") }
        verify { lafManager.setCurrentLookAndFeel(darkTheme, false) }
        verify { lafManager.updateUI() }
    }

    @Test
    fun `null findLaf result is a warn-and-return no-op (Pitfall 7)`() {
        every { mirageTheme.name } returns "Ayu Mirage"
        every { lafManager.findLaf("Ayu Dark") } returns null

        val row = VariantSwitcherRow(AyuVariant.MIRAGE)
        val radios = row.component.components.filterIsInstance<JRadioButton>()
        val darkRadio = radios.single { it.text.equals("Dark", ignoreCase = true) }
        darkRadio.isSelected = true
        darkRadio.doClick()

        verify(exactly = 0) { lafManager.setCurrentLookAndFeel(any<UIThemeLookAndFeelInfo>(), any()) }
        verify(exactly = 0) { lafManager.updateUI() }
    }

    @Test
    fun `toggling Islands UI re-applies the currently selected variant with the new flavour`() {
        every { mirageTheme.name } returns "Ayu Mirage"
        val islandsTheme = mockk<UIThemeLookAndFeelInfo>(relaxed = true)
        every { lafManager.findLaf("Ayu Mirage (Islands UI)") } returns islandsTheme

        val row = VariantSwitcherRow(AyuVariant.MIRAGE)
        val checkbox = checkboxOf(row)
        checkbox.isSelected = true
        checkbox.doClick() // triggers action listener after doClick toggles selection
        // After doClick() the checkbox state inverts again — set explicitly and fire:
        checkbox.isSelected = true
        for (listener in checkbox.actionListeners) {
            listener.actionPerformed(ActionEvent(checkbox, 0, "toggled"))
        }

        verify { lafManager.findLaf("Ayu Mirage (Islands UI)") }
    }
}
