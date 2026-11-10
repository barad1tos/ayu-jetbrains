package dev.ayuislands.commitpanel

import com.intellij.openapi.vcs.changes.ui.ChangesTree
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import java.awt.Component
import java.awt.Font
import java.awt.GraphicsConfiguration
import java.awt.Rectangle
import java.util.IdentityHashMap
import javax.accessibility.AccessibleContext
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.tree.TreeCellRenderer
import javax.swing.tree.TreeModel
import javax.swing.tree.TreeNode

/** Native presentation is prepared on the owner's permitted EDT turn, never inside rendering callbacks. */
internal class CommitPathShorteningRenderer(
    internal val delegate: TreeCellRenderer,
    private val styleProvider: () -> CommitPathStyle? = {
        AyuIslandsSettings.getInstance().state?.let(CommitPathStyle::capture)
    },
    private val canApply: () -> Boolean = { LicenseChecker.isLicensedOrGrace() },
    private val onInvalidated: () -> Unit = {},
) : TreeCellRenderer,
    AutoCloseable {
    private val rows = IdentityHashMap<Any, MutableMap<CommitRowState, CommitPreparedRow>>()
    private val pending = IdentityHashMap<Any, MutableSet<CommitRowState>>()
    private var owner: JTree? = null
    private var cell: TreeCellRenderer? = null
    private var context: CommitRenderContext? = null
    private var isPreparing = false
    private var isRequested = false
    private var isClosed = false
    var isStale = true
        private set

    /** Prepares against the exact installed renderer, without changing renderer or model ownership. */
    fun prepare(
        tree: JTree,
        expectedRenderer: TreeCellRenderer? = delegate,
    ): Boolean {
        check(SwingUtilities.isEventDispatchThread()) { "Commit presentation preparation must run on EDT" }
        val expected = if (expectedRenderer === delegate && tree.cellRenderer === this) this else expectedRenderer
        if (!owns(tree, expected)) return false
        val captured = CommitRenderContext(tree, style())
        bind(tree)
        isPreparing = true
        try {
            val requests: Map<Any, Set<CommitRowState>> = requests(tree)
            val prepared = IdentityHashMap<Any, MutableMap<CommitRowState, CommitPreparedRow>>()
            for ((node, states) in requests) {
                for (state in states) {
                    val presentation = CommitPreparedRow.capture(tree, delegate, node, state, captured.style)
                    prepared.getOrPut(node) { mutableMapOf() }[state] = presentation
                }
            }
            if (!owns(tree, expected) || !captured.matches(tree, style())) return false
            rows.clear()
            rows.putAll(prepared)
            pending.clear()
            context = captured
            isStale = false
            isRequested = false
            tree.repaint()
            tree.accessibleContext.firePropertyChange(AccessibleContext.ACCESSIBLE_VISIBLE_DATA_PROPERTY, false, true)
            return true
        } finally {
            isPreparing = false
            if (isStale) requestPreparation()
        }
    }

    /** Only matching-node text can remain while the owner prepares an invalidated presentation again. */
    fun invalidate() {
        if (isClosed) return
        isStale = true
        requestPreparation()
    }

    override fun getTreeCellRendererComponent(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ): Component {
        bind(tree)
        if (context?.matches(tree, style()) != true) invalidate()
        return checkNotNull(cell).getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
    }

    override fun close() {
        isClosed = true
        isStale = true
        rows.clear()
        pending.clear()
        context = null
    }

    private fun bind(tree: JTree) {
        if (owner === tree) return
        check(owner == null) { "A prepared Commit renderer belongs to one tree" }
        owner = tree
        cell = commitPreparedCell(tree, ::presentation)
    }

    private fun presentation(
        value: Any?,
        state: CommitRowState,
    ): CommitRowView {
        val sameNode = value?.let(rows::get)
        val exact = sameNode?.get(state)
        if (exact == null && value != null && !isClosed) {
            pending.getOrPut(value) { mutableSetOf() }.add(state)
            invalidate()
        }
        return CommitRowView(exact ?: sameNode?.values?.firstOrNull(), isStale || exact == null)
    }

    private fun owns(
        tree: JTree,
        expectedRenderer: TreeCellRenderer?,
    ): Boolean =
        !isClosed &&
            (owner == null || owner === tree) &&
            tree.cellRenderer === expectedRenderer

    private fun style(): CommitPathStyle? = if (canApply()) styleProvider() else null

    private fun requestPreparation() {
        if (isClosed || isPreparing || isRequested) return
        isRequested = true
        onInvalidated()
    }

    private fun requests(tree: JTree): IdentityHashMap<Any, MutableSet<CommitRowState>> {
        val result = IdentityHashMap<Any, MutableSet<CommitRowState>>()
        pending.forEach { (node, states) ->
            if (containsNode(tree, node, states)) result[node] = states.toMutableSet()
        }
        if (tree.rowCount == 0) return result
        val visible = tree.visibleRect
        val first = tree.getClosestRowForLocation(visible.x, visible.y).coerceAtLeast(0)
        val last = tree.getClosestRowForLocation(visible.x, visible.y + visible.height).coerceAtLeast(first)
        for (row in first..last) {
            val path = tree.getPathForRow(row) ?: continue
            val node = path.lastPathComponent
            val states = result.getOrPut(node) { mutableSetOf() }
            for (index in setOf(row, -1)) {
                for (selected in listOf(false, true)) {
                    for (focused in listOf(false, true)) {
                        states +=
                            CommitRowState(selected, tree.isExpanded(path), tree.model.isLeaf(node), index, focused)
                    }
                }
            }
        }
        return result
    }

    private fun containsNode(
        tree: JTree,
        node: Any,
        states: Set<CommitRowState>,
    ): Boolean =
        if (node is TreeNode) {
            generateSequence(node) { it.parent }.last() === tree.model.root
        } else {
            states.any { tree.getPathForRow(it.row)?.lastPathComponent === node }
        }
}

/** Public Swing facts and native inclusion identities; comparison never renders a model node. */
private class CommitRenderContext(
    tree: JTree,
    val style: CommitPathStyle?,
) {
    private val model: TreeModel = tree.model
    private val font: Font? = tree.font
    private val visible = Rectangle(tree.visibleRect)
    private val width = tree.width
    private val configuration: GraphicsConfiguration? = tree.graphicsConfiguration
    private val transform = configuration?.defaultTransform
    private val lookAndFeel = UIManager.getLookAndFeel()
    private val ui = tree.ui
    private val lead = tree.leadSelectionRow
    private val changes = tree as? ChangesTree
    private val inclusionModel = changes?.inclusionModel
    private val included =
        IdentityHashMap<Any, Boolean>().apply {
            changes?.includedSet?.forEach { put(it, true) }
        }

    fun matches(
        tree: JTree,
        currentStyle: CommitPathStyle?,
    ): Boolean =
        style == currentStyle &&
            geometryMatches(tree) &&
            model === tree.model &&
            lookAndFeel === UIManager.getLookAndFeel() &&
            ui === tree.ui &&
            inclusionMatches()

    private fun geometryMatches(tree: JTree): Boolean =
        font == tree.font &&
            visible == tree.visibleRect &&
            width == tree.width &&
            configuration === tree.graphicsConfiguration &&
            transform == tree.graphicsConfiguration?.defaultTransform &&
            lead == tree.leadSelectionRow

    private fun inclusionMatches(): Boolean {
        if (changes == null) return true
        val current: Set<Any> = changes.includedSet
        return changes.inclusionModel === inclusionModel &&
            current.size == included.size &&
            current.all(included::containsKey)
    }
}
