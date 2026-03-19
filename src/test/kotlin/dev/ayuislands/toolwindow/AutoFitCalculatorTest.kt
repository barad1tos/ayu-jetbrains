package dev.ayuislands.toolwindow

import java.awt.FlowLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTree
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoFitCalculatorTest {
    @Test
    fun `calculateDesiredWidth adds padding to max row width`() {
        val result =
            AutoFitCalculator.calculateDesiredWidth(
                maxRowWidth = 200,
                maxWidth = 500,
                minWidth = 100,
            )
        assertEquals(220, result)
    }

    @Test
    fun `calculateDesiredWidth clamps to max width`() {
        val result =
            AutoFitCalculator.calculateDesiredWidth(
                maxRowWidth = 600,
                maxWidth = 500,
                minWidth = 100,
            )
        assertEquals(500, result)
    }

    @Test
    fun `calculateDesiredWidth clamps to min width`() {
        val result =
            AutoFitCalculator.calculateDesiredWidth(
                maxRowWidth = 30,
                maxWidth = 500,
                minWidth = 200,
            )
        assertEquals(200, result)
    }

    @Test
    fun `calculateDesiredWidth with zero row width returns min`() {
        val result =
            AutoFitCalculator.calculateDesiredWidth(
                maxRowWidth = 0,
                maxWidth = 500,
                minWidth = 100,
            )
        assertEquals(100, result)
    }

    @Test
    fun `calculateDesiredWidth min takes precedence over max when min greater than max`() {
        val result =
            AutoFitCalculator.calculateDesiredWidth(
                maxRowWidth = 100,
                maxWidth = 50,
                minWidth = 200,
            )
        assertEquals(200, result)
    }

    @Test
    fun `isJitterOnly returns true for small delta`() {
        assertTrue(AutoFitCalculator.isJitterOnly(currentWidth = 300, desiredWidth = 305))
    }

    @Test
    fun `isJitterOnly returns true at threshold boundary`() {
        assertTrue(AutoFitCalculator.isJitterOnly(currentWidth = 300, desiredWidth = 308))
    }

    @Test
    fun `isJitterOnly returns false for large delta`() {
        assertFalse(AutoFitCalculator.isJitterOnly(currentWidth = 300, desiredWidth = 320))
    }

    @Test
    fun `isJitterOnly handles negative delta`() {
        assertTrue(AutoFitCalculator.isJitterOnly(currentWidth = 300, desiredWidth = 295))
    }

    @Test
    fun `isJitterOnly returns false just above threshold`() {
        assertFalse(AutoFitCalculator.isJitterOnly(currentWidth = 300, desiredWidth = 309))
    }

    @Test
    fun `isJitterOnly returns true for equal widths`() {
        assertTrue(AutoFitCalculator.isJitterOnly(currentWidth = 300, desiredWidth = 300))
    }

    @Test
    fun `findFirstOfType returns component when it matches type`() {
        val label = JLabel("test")
        val result = AutoFitCalculator.findFirstOfType(label, JLabel::class.java)
        assertEquals(label, result)
    }

    @Test
    fun `findFirstOfType finds nested component`() {
        val tree = JTree()
        val inner = JPanel(FlowLayout())
        inner.add(tree)
        val outer = JPanel(FlowLayout())
        outer.add(JLabel("skip"))
        outer.add(inner)

        val result = AutoFitCalculator.findFirstOfType(outer, JTree::class.java)
        assertEquals(tree, result)
    }

    @Test
    fun `findFirstOfType returns null when type not found`() {
        val panel = JPanel(FlowLayout())
        panel.add(JLabel("only label"))

        assertNull(AutoFitCalculator.findFirstOfType(panel, JTree::class.java))
    }

    @Test
    fun `findFirstOfType returns first match in depth-first order`() {
        val first = JLabel("first")
        val second = JLabel("second")
        val panel = JPanel(FlowLayout())
        panel.add(first)
        panel.add(second)

        val result = AutoFitCalculator.findFirstOfType(panel, JLabel::class.java)
        assertEquals(first, result)
    }

    @Test
    fun `findAllOfType returns all matching components`() {
        val label1 = JLabel("a")
        val label2 = JLabel("b")
        val inner = JPanel(FlowLayout())
        inner.add(label2)
        val outer = JPanel(FlowLayout())
        outer.add(label1)
        outer.add(inner)

        val results = AutoFitCalculator.findAllOfType(outer, JLabel::class.java)
        assertEquals(2, results.size)
        assertContains(results, label1)
        assertContains(results, label2)
    }

    @Test
    fun `findAllOfType returns empty list when no matches`() {
        val panel = JPanel(FlowLayout())
        panel.add(JLabel("only label"))

        val results = AutoFitCalculator.findAllOfType(panel, JTree::class.java)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `findAllOfType includes root component if it matches`() {
        val tree = JTree()
        val results = AutoFitCalculator.findAllOfType(tree, JTree::class.java)
        assertEquals(1, results.size)
        assertEquals(tree, results.first())
    }
}
