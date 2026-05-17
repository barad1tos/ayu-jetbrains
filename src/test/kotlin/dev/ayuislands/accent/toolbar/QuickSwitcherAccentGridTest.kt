package dev.ayuislands.accent.toolbar

import com.intellij.ui.ColorUtil
import com.intellij.util.ui.ColorIcon
import dev.ayuislands.accent.AYU_ACCENT_PRESETS
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.settings.mappings.ProjectAccentSwapService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.GridLayout
import javax.swing.JButton
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plan 48-02 Task 3 — locks the slim 12-preset accent grid behaviour:
 *   - layout matches the documented 3×4 grid with the chosen gap,
 *   - cell count matches `AYU_ACCENT_PRESETS.size`,
 *   - each cell renders the preset's hex via [ColorIcon] + tooltip,
 *   - click flows through [AccentApplicator.applyFromHexString] and gates
 *     [ProjectAccentSwapService.notifyExternalApply] on the Boolean return
 *     (Pattern D), with Pattern B swallow for transient [RuntimeException].
 */
class QuickSwitcherAccentGridTest {
    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `component uses GridLayout(3, 4, 4, 4)`() {
        val grid = QuickSwitcherAccentGrid()
        val layout = grid.component.layout as GridLayout
        assertEquals(3, layout.rows)
        assertEquals(4, layout.columns)
        assertEquals(4, layout.hgap)
        assertEquals(4, layout.vgap)
    }

    @Test
    fun `component renders exactly one cell per AYU_ACCENT_PRESETS entry`() {
        val grid = QuickSwitcherAccentGrid()
        assertEquals(AYU_ACCENT_PRESETS.size, grid.component.componentCount)
        assertEquals(12, grid.component.componentCount, "Expected 12 presets per D-12 / RESEARCH §4")
    }

    @Test
    fun `each cell's icon color matches the preset hex`() {
        val grid = QuickSwitcherAccentGrid()
        AYU_ACCENT_PRESETS.forEachIndexed { index, preset ->
            val cell = grid.component.getComponent(index) as JButton
            val icon = cell.icon as ColorIcon
            val expected = ColorUtil.fromHex(preset.hex)
            assertEquals(
                expected,
                icon.iconColor,
                "Cell $index (${preset.name}) icon color must equal ColorUtil.fromHex(${preset.hex})",
            )
        }
    }

    @Test
    fun `each cell's tooltip is name then em-dash then hex`() {
        val grid = QuickSwitcherAccentGrid()
        AYU_ACCENT_PRESETS.forEachIndexed { index, preset ->
            val cell = grid.component.getComponent(index) as JButton
            assertEquals("${preset.name} — ${preset.hex}", cell.toolTipText)
        }
    }

    @Test
    fun `click invokes applyFromHexString AND notifyExternalApply when apply returns true (Pattern D)`() {
        mockkObject(AccentApplicator)
        every { AccentApplicator.applyFromHexString(any()) } returns true
        mockkObject(ProjectAccentSwapService.Companion)
        val swapService = mockk<ProjectAccentSwapService>(relaxed = true)
        every { ProjectAccentSwapService.getInstance() } returns swapService

        val grid = QuickSwitcherAccentGrid()
        val firstPreset = AYU_ACCENT_PRESETS.first()
        val firstCell = grid.component.getComponent(0) as JButton
        firstCell.doClick()

        verify(exactly = 1) { AccentApplicator.applyFromHexString(firstPreset.hex) }
        verify(exactly = 1) { swapService.notifyExternalApply(firstPreset.hex) }
    }

    @Test
    fun `click does NOT call notifyExternalApply when apply returns false (Pattern D inverse)`() {
        mockkObject(AccentApplicator)
        every { AccentApplicator.applyFromHexString(any()) } returns false
        mockkObject(ProjectAccentSwapService.Companion)
        val swapService = mockk<ProjectAccentSwapService>(relaxed = true)
        every { ProjectAccentSwapService.getInstance() } returns swapService

        val grid = QuickSwitcherAccentGrid()
        (grid.component.getComponent(0) as JButton).doClick()

        verify(exactly = 0) { swapService.notifyExternalApply(any()) }
    }

    @Test
    fun `click swallows RuntimeException from applyFromHexString (Pattern B)`() {
        mockkObject(AccentApplicator)
        every { AccentApplicator.applyFromHexString(any()) } throws RuntimeException("transient")
        mockkObject(ProjectAccentSwapService.Companion)
        val swapService = mockk<ProjectAccentSwapService>(relaxed = true)
        every { ProjectAccentSwapService.getInstance() } returns swapService

        val grid = QuickSwitcherAccentGrid()
        val cell = grid.component.getComponent(0) as JButton
        // Must not propagate — the click handler catches RuntimeException only.
        cell.doClick()

        verify(exactly = 0) { swapService.notifyExternalApply(any()) }
        assertTrue(cell.isEnabled, "Cell must stay enabled after a swallowed RuntimeException")
    }
}
