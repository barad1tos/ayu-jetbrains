package dev.ayuislands.commitpanel

import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNodeRenderer
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vcs.changes.ui.ChangesTreeCellRenderer
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.ImageUtil
import com.intellij.util.ui.UIUtil
import dev.ayuislands.commitpanel.CommitPathPresentation.CommitFragment
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.image.BufferedImage
import javax.swing.Icon
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.tree.TreeCellRenderer

internal data class CommitRowState(
    val selected: Boolean,
    val expanded: Boolean,
    val leaf: Boolean,
    val row: Int,
    val focused: Boolean,
)

internal data class CommitRowView(
    val presentation: CommitPreparedRow?,
    val isStale: Boolean,
)

/** Native pixels contain decoration; the separately captured text is safe while decoration is stale. */
internal class CommitPreparedRow private constructor(
    val fragments: List<CommitFragment>,
    val icon: Icon?,
    val font: Font?,
    val tooltip: String?,
    val preferred: Dimension,
    private val nativePixels: BufferedImage,
    private val textPixels: BufferedImage,
    private val textBounds: Rectangle,
) {
    fun paint(
        graphics: Graphics,
        isStale: Boolean,
        checkboxWidth: Int,
    ) {
        val copy = graphics.create()
        try {
            if (isStale) {
                copy.clipRect(checkboxWidth, 0, Int.MAX_VALUE - checkboxWidth, Int.MAX_VALUE)
                UIUtil.drawImage(copy, textPixels, textBounds.x, textBounds.y, null)
            } else {
                val left = maxOf(checkboxWidth, textBounds.x)
                copy.clipRect(left, 0, Int.MAX_VALUE - left, Int.MAX_VALUE)
                UIUtil.drawImage(copy, nativePixels, 0, 0, null)
            }
        } finally {
            copy.dispose()
        }
    }

    companion object {
        fun capture(
            tree: JTree,
            delegate: TreeCellRenderer,
            value: Any,
            state: CommitRowState,
            style: CommitPathStyle?,
        ): CommitPreparedRow {
            val component =
                delegate.getTreeCellRendererComponent(
                    tree,
                    value,
                    state.selected,
                    state.expanded,
                    state.leaf,
                    state.row,
                    state.focused,
                )
            CommitPathPresentation.apply(tree, component, style)
            val preferred = Dimension(component.preferredSize)
            component.setSize(
                tree.width.coerceAtLeast(1),
                tree.rowHeight.takeIf { it > 0 } ?: preferred.height.coerceAtLeast(1),
            )
            if (component is java.awt.Container) component.doLayout()
            val text = CommitPathPresentation.text(component)
            val fragments =
                text?.let(CommitPathPresentation::readFragments)
                    ?: listOf(
                        CommitFragment(
                            component.accessibleContext?.accessibleName.orEmpty(),
                            SimpleTextAttributes.REGULAR_ATTRIBUTES,
                            null,
                        ),
                    )
            val textBounds =
                text?.let { SwingBounds.relative(it, component) } ?: Rectangle(0, 0, component.width, component.height)
            return CommitPreparedRow(
                fragments,
                text?.icon,
                text?.font ?: component.font,
                (component as? JComponent)?.toolTipText,
                preferred,
                pixels(component, tree),
                pixels(text ?: component, tree),
                textBounds,
            )
        }

        private fun pixels(
            component: Component,
            tree: JTree,
        ): BufferedImage {
            val image =
                ImageUtil.createImage(
                    tree.graphicsConfiguration,
                    component.width.coerceAtLeast(1),
                    component.height.coerceAtLeast(1),
                    BufferedImage.TYPE_INT_ARGB,
                )
            val graphics = image.createGraphics()
            try {
                component.paint(graphics)
            } finally {
                graphics.dispose()
            }
            return image
        }
    }
}

private object SwingBounds {
    fun relative(
        child: Component,
        parent: Component,
    ): Rectangle {
        if (child === parent) return Rectangle(0, 0, child.width, child.height)
        return javax.swing.SwingUtilities.convertRectangle(child.parent, child.bounds, parent)
    }
}

internal fun commitPreparedCell(
    tree: JTree,
    presentation: (Any?, CommitRowState) -> CommitRowView,
): TreeCellRenderer =
    if (tree is ChangesTree) PreparedChangesCell(tree, presentation) else PreparedTextCell(presentation)

private class PreparedChangesCell private constructor(
    private val presenter: PreparedNodeRenderer,
) : ChangesTreeCellRenderer(presenter) {
    constructor(tree: ChangesTree, presentation: (Any?, CommitRowState) -> CommitRowView) :
        this(PreparedNodeRenderer(tree, presentation))

    private val checkbox = components.filterIsInstance<JCheckBox>().single()

    override fun getPreferredSize(): Dimension =
        presenter.current
            ?.presentation
            ?.preferred
            ?.let(::Dimension) ?: super.getPreferredSize()

    override fun paintChildren(graphics: Graphics) {
        if (checkbox.isVisible) {
            val copy = graphics.create(checkbox.x, checkbox.y, checkbox.width, checkbox.height)
            try {
                checkbox.paint(copy)
            } finally {
                copy.dispose()
            }
        }
        val view = presenter.current
        if (view?.presentation != null) {
            view.presentation.paint(graphics, view.isStale, if (checkbox.isVisible) checkbox.x + checkbox.width else 0)
        } else {
            val copy = graphics.create(presenter.x, presenter.y, presenter.width, presenter.height)
            try {
                presenter.paint(copy)
            } finally {
                copy.dispose()
            }
        }
    }
}

private class PreparedNodeRenderer(
    tree: ChangesTree,
    private val presentation: (Any?, CommitRowState) -> CommitRowView,
) : ChangesBrowserNodeRenderer(tree.project, { tree.isShowFlatten }, tree.isHighlightProblems) {
    var current: CommitRowView? = null
        private set

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        val view = presentation(value, CommitRowState(selected, expanded, leaf, row, hasFocus))
        current = view
        showPresentation(view.presentation, tree.font)
    }
}

private class PreparedTextCell(
    private val presentation: (Any?, CommitRowState) -> CommitRowView,
) : SimpleColoredComponent(),
    TreeCellRenderer {
    private var current: CommitRowView? = null

    override fun getTreeCellRendererComponent(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ): Component {
        clear()
        val view = presentation(value, CommitRowState(selected, expanded, leaf, row, hasFocus))
        current = view
        showPresentation(view.presentation, tree.font)
        return this
    }

    override fun getPreferredSize(): Dimension =
        current?.presentation?.preferred?.let(::Dimension) ?: super.getPreferredSize()

    override fun paint(graphics: Graphics) {
        val view = current
        if (view?.presentation == null) super.paint(graphics) else view.presentation.paint(graphics, view.isStale, 0)
    }
}

private fun SimpleColoredComponent.showPresentation(
    presentation: CommitPreparedRow?,
    treeFont: Font?,
) {
    icon = presentation?.icon
    font = presentation?.font ?: treeFont
    toolTipText = presentation?.tooltip
    if (presentation == null) {
        append("Loading change…", SimpleTextAttributes.REGULAR_ATTRIBUTES)
    } else {
        for ((text, attributes, tag) in presentation.fragments) append(text, attributes, tag)
    }
}
