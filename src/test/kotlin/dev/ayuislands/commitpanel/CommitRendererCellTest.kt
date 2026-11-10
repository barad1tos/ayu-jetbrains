package dev.ayuislands.commitpanel

import dev.ayuislands.accent.AccentWriteState
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.TreeCellRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class CommitRendererCellTest {
    @Test
    fun `mutation then exception retains actual renderer for rollback and rollback retry`() =
        SwingUtilities.invokeAndWait {
            val tree = FailingTree()
            val before = tree.cellRenderer
            val planned = DefaultTreeCellRenderer()
            val cell = CommitRendererCell(tree, before, planned)
            tree.shouldFail = true
            assertFailsWith<IllegalStateException> { cell.apply() }
            assertSame(planned, cell.record.lastWritten)
            assertEquals(AccentWriteState.OWNED, cell.state)
            tree.shouldFail = true
            assertFailsWith<IllegalStateException> { cell.restore(true) }
            assertSame(before, cell.record.lastWritten)
            cell.restore(true)
            assertSame(before, tree.cellRenderer)
            assertEquals(AccentWriteState.RESTORED, cell.state)
        }

    @Test
    fun `foreign renderer identity survives even when equality claims it matches`() =
        SwingUtilities.invokeAndWait {
            val tree = JTree()
            val planned = EqualRenderer()
            val cell = CommitRendererCell(tree, tree.cellRenderer, planned)
            cell.apply()
            val foreign = EqualRenderer()
            tree.cellRenderer = foreign
            cell.restore(true)
            assertSame(foreign, tree.cellRenderer)
            assertEquals(AccentWriteState.RELINQUISHED, cell.state)
        }

    private class FailingTree : JTree() {
        var shouldFail = false

        override fun setCellRenderer(renderer: TreeCellRenderer?) {
            super.setCellRenderer(renderer)
            if (shouldFail) {
                shouldFail = false
                error("Renderer changed before failure")
            }
        }
    }

    private class EqualRenderer : DefaultTreeCellRenderer() {
        override fun equals(other: Any?): Boolean = other is EqualRenderer

        override fun hashCode(): Int = 1
    }
}
