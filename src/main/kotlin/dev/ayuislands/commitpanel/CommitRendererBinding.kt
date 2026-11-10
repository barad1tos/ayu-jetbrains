package dev.ayuislands.commitpanel

import com.intellij.openapi.vcs.changes.InclusionListener
import com.intellij.openapi.vcs.changes.InclusionModel
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.beans.PropertyChangeListener
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.event.TreeModelEvent
import javax.swing.event.TreeModelListener
import javax.swing.event.TreeSelectionListener
import javax.swing.tree.TreeModel

/** Owns only Ayu's refresh listeners; native tree listeners and their order stay intact. */
internal class CommitRendererBinding(
    private val tree: JTree,
    val renderer: CommitPathShorteningRenderer,
    private val isActive: () -> Boolean,
    private val post: (Runnable) -> Unit = SwingUtilities::invokeLater,
) : AutoCloseable {
    private enum class Preparation { IDLE, QUEUED, CLOSED }

    private var preparation = Preparation.IDLE
    private var model: TreeModel? = null
    private var inclusion: InclusionModel? = null
    private val modelListener =
        object : TreeModelListener {
            override fun treeNodesChanged(event: TreeModelEvent) = refresh()

            override fun treeNodesInserted(event: TreeModelEvent) = refresh()

            override fun treeNodesRemoved(event: TreeModelEvent) = refresh()

            override fun treeStructureChanged(event: TreeModelEvent) = refresh()
        }
    private val inclusionListener =
        object : InclusionListener {
            override fun inclusionChanged() = refresh()
        }
    private val propertyListener =
        PropertyChangeListener { event ->
            if (event.propertyName == "model") observeModel()
            if (event.propertyName != "cellRenderer") refresh()
        }
    private val selectionListener = TreeSelectionListener { refresh() }
    private val expansionListener =
        object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) = refresh()

            override fun treeCollapsed(event: TreeExpansionEvent) = refresh()
        }
    private val geometryListener =
        object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = refresh()

            override fun componentShown(event: ComponentEvent) = refresh()
        }
    private val mouseListener =
        object : MouseAdapter() {
            override fun mouseMoved(event: MouseEvent) = refresh()

            override fun mouseEntered(event: MouseEvent) = refresh()

            override fun mouseExited(event: MouseEvent) = refresh()
        }

    init {
        observeModel()
        observeInclusion()
        tree.addPropertyChangeListener(propertyListener)
        tree.addTreeSelectionListener(selectionListener)
        tree.addTreeExpansionListener(expansionListener)
        tree.addComponentListener(geometryListener)
        tree.addMouseListener(mouseListener)
        tree.addMouseMotionListener(mouseListener)
        requestPreparation()
    }

    fun refresh() {
        if (preparation == Preparation.CLOSED) return
        renderer.invalidate()
        requestPreparation()
    }

    /** A plain EDT turn prepares model presentation before paint/accessibility can consume it. */
    fun requestPreparation() {
        if (preparation != Preparation.IDLE) return
        preparation = Preparation.QUEUED
        post {
            if (preparation != Preparation.QUEUED) return@post
            preparation = Preparation.IDLE
            if (!isActive() || tree.cellRenderer !== renderer) return@post
            observeModel()
            observeInclusion()
            renderer.prepare(tree)
        }
    }

    override fun close() {
        if (preparation == Preparation.CLOSED) return
        preparation = Preparation.CLOSED
        model?.removeTreeModelListener(modelListener)
        inclusion?.removeInclusionListener(inclusionListener)
        model = null
        inclusion = null
        tree.removePropertyChangeListener(propertyListener)
        tree.removeTreeSelectionListener(selectionListener)
        tree.removeTreeExpansionListener(expansionListener)
        tree.removeComponentListener(geometryListener)
        tree.removeMouseListener(mouseListener)
        tree.removeMouseMotionListener(mouseListener)
        renderer.close()
    }

    private fun observeModel() {
        val current = tree.model
        if (model === current) return
        model?.removeTreeModelListener(modelListener)
        model = current
        current?.addTreeModelListener(modelListener)
    }

    private fun observeInclusion() {
        val current = (tree as? ChangesTree)?.inclusionModel
        if (inclusion === current) return
        inclusion?.removeInclusionListener(inclusionListener)
        inclusion = current
        current?.addInclusionListener(inclusionListener)
    }
}
