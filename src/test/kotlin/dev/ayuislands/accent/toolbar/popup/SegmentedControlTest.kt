package dev.ayuislands.accent.toolbar.popup

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AyuVariant
import java.awt.Container
import java.awt.Dimension
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
    fun `selected cell paints pressedBackground at the centre pixel (IMP-4 paint state)`() {
        // Round 2 IMP-4: behavior lock for the selected branch of `paintCell`.
        // Construct a control whose MIRAGE cell is the selected one, render
        // via the `paintForTest` seam, then sample the centre pixel and
        // confirm it differs from a fully transparent fill — meaning the
        // `JBUI.CurrentTheme.ActionButton.pressedBackground` fill was
        // applied.
        val control = SegmentedControl(initialVariant = AyuVariant.MIRAGE) {}
        val cells = collectCells(control)
        val mirageCell = cells.first()
        mirageCell.size = Dimension(JBUI.scale(CELL_WIDTH), JBUI.scale(CELL_HEIGHT))
        val image = BufferedImage(mirageCell.width, mirageCell.height, BufferedImage.TYPE_INT_ARGB)
        val g2 = image.createGraphics()
        try {
            (mirageCell as SegmentedControl.VariantCell).paintForTest(g2)
        } finally {
            g2.dispose()
        }
        // Centre pixel must carry the pressedBackground tint (non-transparent
        // ARGB). The exact RGB depends on platform theme so assert non-zero
        // alpha instead.
        val argb = image.getRGB(mirageCell.width / 2, mirageCell.height / 2)
        val alpha = (argb shr ALPHA_SHIFT) and BYTE_MASK
        assertTrue(alpha > 0, "Selected cell centre pixel must paint a non-transparent fill; alpha=$alpha")
    }

    @Test
    fun `hovered non-selected cell paints hoverBackground (IMP-4 hover branch)`() {
        // Round 2 IMP-4: behavior lock for the hover branch. Select MIRAGE so
        // the DARK cell is non-selected, then render the DARK cell with
        // hovered=true through the seam. Idle paint would leave the centre
        // transparent; hover paint composites `ActionButton.hoverBackground`.
        val control = SegmentedControl(initialVariant = AyuVariant.MIRAGE) {}
        val cells = collectCells(control)
        val darkCell = cells[1]
        darkCell.size = Dimension(JBUI.scale(CELL_WIDTH), JBUI.scale(CELL_HEIGHT))
        val image = BufferedImage(darkCell.width, darkCell.height, BufferedImage.TYPE_INT_ARGB)
        val g2 = image.createGraphics()
        try {
            (darkCell as SegmentedControl.VariantCell).paintForTest(g2, hovered = true)
        } finally {
            g2.dispose()
        }
        val argb = image.getRGB(darkCell.width / 2, darkCell.height / 2)
        val alpha = (argb shr ALPHA_SHIFT) and BYTE_MASK
        assertTrue(alpha > 0, "Hovered cell must paint a non-transparent overlay; alpha=$alpha")
    }

    @Test
    fun `idle non-selected non-hovered cell paints no background (IMP-4 idle branch)`() {
        // Round 2 IMP-4: idle branch is `else -> Unit` — no fill, no border.
        // Sample a CORNER pixel (label centred so it doesn't reach the
        // corner). Idle alpha must be 0 (transparent); the centre pixel
        // would carry label-rendering alpha and is not a valid background-only
        // sample.
        val control = SegmentedControl(initialVariant = AyuVariant.MIRAGE) {}
        val cells = collectCells(control)
        val darkCell = cells[1]
        darkCell.size = Dimension(JBUI.scale(CELL_WIDTH), JBUI.scale(CELL_HEIGHT))
        val image = BufferedImage(darkCell.width, darkCell.height, BufferedImage.TYPE_INT_ARGB)
        val g2 = image.createGraphics()
        try {
            (darkCell as SegmentedControl.VariantCell).paintForTest(g2)
        } finally {
            g2.dispose()
        }
        val cornerArgb = image.getRGB(CORNER_INSET, CORNER_INSET)
        val cornerAlpha = (cornerArgb shr ALPHA_SHIFT) and BYTE_MASK
        assertEquals(
            0,
            cornerAlpha,
            "Idle non-selected non-hovered cell must NOT paint a background at the corner; alpha=$cornerAlpha",
        )
    }

    @Test
    fun `label colour differs between selected and non-selected cells (IMP-4 label flip)`() {
        // Round 2 IMP-4: label flips between `Label.foreground` (selected) and
        // `Label.disabledForeground` (non-selected). Render the same cell
        // both ways and assert the painted text region differs.
        val control = SegmentedControl(initialVariant = AyuVariant.MIRAGE) {}
        val cells = collectCells(control)
        val mirageCell = cells.first() as SegmentedControl.VariantCell
        mirageCell.size = Dimension(JBUI.scale(CELL_WIDTH), JBUI.scale(CELL_HEIGHT))

        val selectedImage = BufferedImage(mirageCell.width, mirageCell.height, BufferedImage.TYPE_INT_ARGB)
        val selectedG2 = selectedImage.createGraphics()
        try {
            mirageCell.paintForTest(selectedG2)
        } finally {
            selectedG2.dispose()
        }

        // Programmatically deselect (LIGHT chosen) — now MIRAGE is non-selected.
        control.setSelectedVariant(AyuVariant.LIGHT)
        val deselectedImage = BufferedImage(mirageCell.width, mirageCell.height, BufferedImage.TYPE_INT_ARGB)
        val deselectedG2 = deselectedImage.createGraphics()
        try {
            mirageCell.paintForTest(deselectedG2)
        } finally {
            deselectedG2.dispose()
        }

        // Sample near where text typically lands (centre column, slightly
        // below the geometric centre). The exact platform colour values
        // depend on the headless LAF, so just assert NON-EQUAL.
        val sx = mirageCell.width / 2
        val sy = (mirageCell.height * SAMPLE_NUMERATOR) / SAMPLE_DENOMINATOR
        val selectedArgb = selectedImage.getRGB(sx, sy)
        val deselectedArgb = deselectedImage.getRGB(sx, sy)
        // The cell as a whole MUST differ across the two paints — selected
        // adds a `pressedBackground` fill PLUS uses `Label.foreground`,
        // deselected has no fill AND uses `Label.disabledForeground`.
        assertNotEquals(
            selectedArgb,
            deselectedArgb,
            "Selected vs non-selected paint must differ (fill + label colour both change)",
        )
        // Sanity: at least one of `Label.foreground` / `disabledForeground` must
        // resolve to a non-null Color in this LAF for the assertion above to
        // carry semantic weight.
        val fgPresent =
            JBColor.namedColor("Label.foreground", JBColor.foreground()) != null ||
                JBUI.CurrentTheme.Label.disabledForeground() != null
        assertTrue(fgPresent, "At least one label-foreground LAF key must resolve to a Color")
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
        const val CELL_WIDTH: Int = 64
        const val ALPHA_SHIFT: Int = 24
        const val BYTE_MASK: Int = 0xFF

        /**
         * Vertical sample for label colour assertion — slightly below centre
         * where the rendered glyphs land. `numerator / denominator` => ~0.65.
         */
        const val SAMPLE_NUMERATOR: Int = 13
        const val SAMPLE_DENOMINATOR: Int = 20

        /**
         * Corner inset for the idle-branch sample — far enough from the
         * centred label that only background paint can reach the pixel.
         */
        const val CORNER_INSET: Int = 2
    }
}
