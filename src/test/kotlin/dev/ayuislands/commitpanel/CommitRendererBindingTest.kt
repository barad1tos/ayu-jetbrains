package dev.ayuislands.commitpanel

import dev.ayuislands.settings.AyuIslandsState
import java.awt.event.ComponentEvent
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CommitRendererBindingTest {
    @Test
    fun `model and size events coalesce and preserve native listener order`() =
        onEdt {
            val fixture = Fixture()
            val listeners = fixture.tree.componentListeners.toList()
            val binding = fixture.bind()
            fixture.runPreparation()
            val before = fixture.calls
            (fixture.tree.model as DefaultTreeModel).reload()
            fixture.tree.dispatchEvent(ComponentEvent(fixture.tree, ComponentEvent.COMPONENT_RESIZED))

            assertEquals(1, fixture.pending.size)
            assertEquals(before, fixture.calls)
            fixture.runPreparation()
            assertTrue(fixture.calls > before)
            assertFalse(fixture.renderer.isStale)
            binding.close()
            assertEquals(listeners, fixture.tree.componentListeners.toList())
        }

    @Test
    fun `disposal retires a queued refresh without native reads or renderer writes`() =
        onEdt {
            val fixture = Fixture()
            val binding = fixture.bind()
            binding.close()
            val before = fixture.calls
            fixture.runPreparation()
            assertEquals(before, fixture.calls)
            assertSame(fixture.renderer, fixture.tree.cellRenderer)
        }

    @Test
    fun `foreign renderer and inactive project keep queued presentation untouched`() =
        onEdt {
            val fixture = Fixture()
            val binding = fixture.bind()
            val foreign = DefaultTreeCellRenderer()
            fixture.tree.cellRenderer = foreign
            fixture.runPreparation()
            assertEquals(0, fixture.calls)
            assertSame(foreign, fixture.tree.cellRenderer)
            fixture.tree.cellRenderer = fixture.renderer
            fixture.isActive = false
            binding.refresh()
            fixture.runPreparation()
            assertEquals(0, fixture.calls)
            binding.close()
        }

    private class Fixture {
        val tree = JTree(DefaultMutableTreeNode("root").apply { add(DefaultMutableTreeNode("file")) })
        val pending = mutableListOf<Runnable>()
        var calls = 0
        var isActive = true
        private var binding: CommitRendererBinding? = null
        val renderer =
            CommitPathShorteningRenderer(
                { target, value, selected, expanded, leaf, row, focused ->
                    calls++
                    DefaultTreeCellRenderer().getTreeCellRendererComponent(
                        target,
                        value,
                        selected,
                        expanded,
                        leaf,
                        row,
                        focused,
                    )
                },
                styleProvider = { CommitPathStyle.capture(AyuIslandsState()) },
                canApply = { true },
                onInvalidated = { binding?.requestPreparation() },
            )

        init {
            tree.setSize(300, 120)
            tree.cellRenderer = renderer
        }

        fun bind(): CommitRendererBinding =
            CommitRendererBinding(tree, renderer, { isActive }, pending::add).also { binding = it }

        fun runPreparation() {
            val tasks = pending.toList()
            pending.clear()
            tasks.forEach(Runnable::run)
        }
    }

    private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)
}
