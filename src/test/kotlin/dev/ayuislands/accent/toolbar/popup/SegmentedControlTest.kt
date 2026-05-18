package dev.ayuislands.accent.toolbar.popup

import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AyuVariant
import java.awt.Container
import java.awt.event.MouseEvent
import javax.swing.JComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the [SegmentedControl] contract per 48-REDESIGN-SPEC §3.3:
 *  - exactly 3 child cells in display order MIRAGE / DARK / LIGHT,
 *  - each cell's accessibleName follows the "Variant: Mirage" / "Variant: Dark" /
 *    "Variant: Light" ARIA-equivalent format,
 *  - clicking a cell invokes onSelectionChanged with the corresponding AyuVariant,
 *  - selected cell tracks via setSelectedVariant — clicks update internal state.
 */
class SegmentedControlTest {
    @Test
    fun `renders three cells in declaration order MIRAGE DARK LIGHT`() {
        val control = SegmentedControl(initialVariant = AyuVariant.MIRAGE) {}
        val cells = collectCells(control)
        assertEquals(EXPECTED_CELL_COUNT, cells.size, "should render exactly three variant cells")

        val orderedVariants = AyuVariant.entries.toList()
        cells.forEachIndexed { index, cell ->
            val expectedName = "Variant: ${orderedVariants[index].displayLabel()}"
            assertEquals(
                expectedName,
                cell.accessibleContext.accessibleName,
                "cell $index accessibleName mismatch",
            )
        }
    }

    @Test
    fun `clicking a cell invokes onSelectionChanged with corresponding AyuVariant`() {
        val captured = mutableListOf<AyuVariant>()
        val control =
            SegmentedControl(initialVariant = AyuVariant.MIRAGE) { variant ->
                captured += variant
            }
        val cells = collectCells(control)

        // Click cell 1 (DARK).
        cells[1].dispatchEvent(
            MouseEvent(
                cells[1],
                MouseEvent.MOUSE_CLICKED,
                0L,
                0,
                JBUI.scale(2),
                JBUI.scale(2),
                1,
                false,
                MouseEvent.BUTTON1,
            ),
        )

        assertEquals(listOf(AyuVariant.DARK), captured, "clicking DARK cell should report DARK")
    }

    @Test
    fun `setSelectedVariant updates which cell paints as selected (state mutation)`() {
        val control = SegmentedControl(initialVariant = AyuVariant.MIRAGE) {}
        assertEquals(AyuVariant.MIRAGE, control.selectedVariant)

        control.setSelectedVariant(AyuVariant.LIGHT)
        assertEquals(AyuVariant.LIGHT, control.selectedVariant)
    }

    @Test
    fun `clicking each cell in turn reports each variant in order`() {
        val captured = mutableListOf<AyuVariant>()
        val control =
            SegmentedControl(initialVariant = AyuVariant.MIRAGE) { variant ->
                captured += variant
            }
        val cells = collectCells(control)
        cells.forEach { cell ->
            cell.dispatchEvent(
                MouseEvent(
                    cell,
                    MouseEvent.MOUSE_CLICKED,
                    0L,
                    0,
                    JBUI.scale(2),
                    JBUI.scale(2),
                    1,
                    false,
                    MouseEvent.BUTTON1,
                ),
            )
        }
        assertEquals(AyuVariant.entries.toList(), captured)
    }

    @Test
    fun `preferred cell height is JBUI scaled 28`() {
        val control = SegmentedControl(initialVariant = AyuVariant.MIRAGE) {}
        val cells = collectCells(control)
        assertTrue(cells.isNotEmpty())
        val expectedH = JBUI.scale(CELL_HEIGHT)
        cells.forEach { cell ->
            assertEquals(expectedH, cell.preferredSize.height, "cell height should be JBUI.scale(28)")
        }
    }

    private fun collectCells(container: Container): List<JComponent> {
        val out = mutableListOf<JComponent>()
        for (i in 0 until container.componentCount) {
            val child = container.getComponent(i)
            if (child is JComponent && child.accessibleContext?.accessibleName?.startsWith("Variant: ") == true) {
                out += child
            }
        }
        return out
    }

    private fun AyuVariant.displayLabel(): String = name.lowercase().replaceFirstChar { it.uppercase() }

    private companion object {
        const val EXPECTED_CELL_COUNT: Int = 3
        const val CELL_HEIGHT: Int = 28
    }
}
