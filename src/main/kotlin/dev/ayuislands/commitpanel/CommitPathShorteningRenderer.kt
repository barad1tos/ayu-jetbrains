package dev.ayuislands.commitpanel

import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.CommitPathDisplayMode
import dev.ayuislands.settings.PanelWidthMode
import java.awt.Component
import java.awt.Container
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.ToolTipManager
import javax.swing.tree.TreeCellRenderer

internal class CommitPathShorteningRenderer(
    internal val delegate: TreeCellRenderer,
    private val stateProvider: () -> AyuIslandsState = { AyuIslandsSettings.getInstance().state },
) : TreeCellRenderer {
    override fun getTreeCellRendererComponent(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ): Component {
        val component =
            delegate.getTreeCellRendererComponent(
                tree,
                value,
                selected,
                expanded,
                leaf,
                row,
                hasFocus,
            )
        val textComponent = component.findPathTextComponent() ?: return component
        shortenPathFragment(
            tree = tree,
            component = textComponent,
            fullRowWidth = component.preferredSize.width,
        )
        if (component is JComponent && component !== textComponent) {
            component.toolTipText = textComponent.toolTipText
        }
        return component
    }

    private fun shortenPathFragment(
        tree: JTree,
        component: SimpleColoredComponent,
        fullRowWidth: Int,
    ) {
        val state = stateProvider()
        if (PanelWidthMode.fromString(state.commitPanelWidthMode) == PanelWidthMode.DEFAULT) return

        val fragments = component.fragments()
        val pathIndex = fragments.pathFragmentIndex()
        if (pathIndex < 0) return

        val originalPath = PathFragment(text = fragments[pathIndex].text, index = pathIndex)
        if (CommitPathDisplayMode.fromString(state.commitPanelPathDisplayMode) == CommitPathDisplayMode.TOOLTIP) {
            applyTooltipOnlyPath(tree, component, fragments, originalPath)
            return
        }

        component.toolTipText = null
        val font =
            component.font
                ?: tree.font
                ?: Font(Font.DIALOG, Font.PLAIN, DEFAULT_FONT_SIZE)
        val metrics = component.getFontMetrics(font)
        val availableRowWidth =
            availableRowWidth(
                tree = tree,
                fullRowWidth = fullRowWidth,
                path = originalPath,
                fragments = fragments,
                measureTextWidth = metrics::stringWidth,
            ) ?: return
        val shortened =
            CommitPathShortener.shorten(
                CommitPathShorteningRequest(
                    pathText = originalPath.text,
                    fullRowWidth = fullRowWidth,
                    availableRowWidth = availableRowWidth,
                    minHiddenLevels = state.commitPanelPathMinHiddenLevels,
                    maxHiddenLevels = state.commitPanelPathMaxHiddenLevels,
                    measureTextWidth = metrics::stringWidth,
                ),
            )
        if (shortened == originalPath.text) return

        val icon = component.icon
        component.clear()
        component.icon = icon
        for ((index, fragment) in fragments.withIndex()) {
            component.append(
                if (index == pathIndex) shortened else fragment.text,
                fragment.attributes,
            )
        }
    }

    private fun applyTooltipOnlyPath(
        tree: JTree,
        component: SimpleColoredComponent,
        fragments: List<Fragment>,
        path: PathFragment,
    ) {
        val tooltip = path.text.trim().takeIf { it.isNotEmpty() } ?: return
        ToolTipManager.sharedInstance().registerComponent(tree)

        val icon = component.icon
        component.clear()
        component.icon = icon
        if (fragments.hasPathAnchorBefore(path)) {
            for ((index, fragment) in fragments.withIndex()) {
                if (index != path.index) component.append(fragment.text, fragment.attributes)
            }
        } else {
            val pathLeaf = path.text.lastPathSegment() ?: tooltip
            component.append(pathLeaf, fragments[path.index].attributes)
        }
        component.toolTipText = tooltip
    }

    private fun List<Fragment>.hasPathAnchorBefore(path: PathFragment): Boolean =
        take(path.index).any { fragment -> fragment.text.isNotBlank() && !fragment.text.isPathText() }

    private fun availableRowWidth(
        tree: JTree,
        fullRowWidth: Int,
        path: PathFragment,
        fragments: List<Fragment>,
        measureTextWidth: (String) -> Int,
    ): Int? {
        val targetRowWidth =
            targetRowWidth(
                fullRowWidth = fullRowWidth,
                path = path,
                fragments = fragments,
                measureTextWidth = measureTextWidth,
            )
        val treeWidth = treeAvailableWidth(tree)
        return when {
            targetRowWidth == null -> treeWidth
            treeWidth == null -> targetRowWidth
            else -> minOf(targetRowWidth, treeWidth)
        }
    }

    private fun targetRowWidth(
        fullRowWidth: Int,
        path: PathFragment,
        fragments: List<Fragment>,
        measureTextWidth: (String) -> Int,
    ): Int? {
        val anchorText = pathAnchorText(fragments, path) ?: return null
        val pathPrefix = path.text.takeWhile { it.isWhitespace() }
        val targetPathWidth = measureTextWidth(pathPrefix) + measureTextWidth(anchorText)
        val originalPathWidth = measureTextWidth(path.text)
        return (fullRowWidth - originalPathWidth + targetPathWidth).coerceAtLeast(0)
    }

    private fun pathAnchorText(
        fragments: List<Fragment>,
        path: PathFragment,
    ): String? =
        fragments
            .take(path.index)
            .lastOrNull { fragment -> fragment.text.isNotBlank() && !fragment.text.isPathText() }
            ?.text
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: path.text.lastPathSegment()

    private fun treeAvailableWidth(tree: JTree): Int? {
        val visibleWidth = tree.visibleRect.width.takeIf { it > 0 } ?: tree.width
        if (visibleWidth <= 0) return null

        val insets = tree.insets
        return (visibleWidth - insets.left - insets.right).coerceAtLeast(0)
    }

    private fun Component.findPathTextComponent(): SimpleColoredComponent? {
        if (this is SimpleColoredComponent && hasPathFragment()) return this
        if (this !is Container) return null

        for (child in components) {
            val found = child.findPathTextComponent()
            if (found != null) return found
        }
        return null
    }

    private fun SimpleColoredComponent.hasPathFragment(): Boolean = fragments().pathFragmentIndex() >= 0

    private fun List<Fragment>.pathFragmentIndex(): Int {
        val pathIndex = indexOfLast { fragment -> fragment.text.isPathText() }
        if (pathIndex >= 0) return pathIndex

        val lastContentIndex = indexOfLast { fragment -> fragment.text.isNotBlank() }
        if (lastContentIndex <= 0) return -1

        val location = this[lastContentIndex].text.trim()
        val hasFileAnchor =
            take(lastContentIndex).any { fragment ->
                val text = fragment.text.trim()
                text.isNotEmpty() && !text.isPathText()
            }
        return if (hasFileAnchor && location.none { it.isWhitespace() }) lastContentIndex else -1
    }

    private fun String.isPathText(): Boolean {
        val text = trim()
        return text.contains('/') || text.contains('\\')
    }

    private fun String.lastPathSegment(): String? {
        val path = trim()
        if (path.isEmpty()) return null

        val separator = pathSeparatorFor(path)
        return path
            .split(separator)
            .lastOrNull { segment -> segment.isNotEmpty() }
    }

    private fun pathSeparatorFor(path: String): String {
        val windowsSeparators = path.count { it == '\\' }
        val unixSeparators = path.count { it == '/' }
        return if (windowsSeparators > unixSeparators) WINDOWS_SEPARATOR else UNIX_SEPARATOR
    }

    private data class Fragment(
        val text: String,
        val attributes: SimpleTextAttributes,
    )

    private data class PathFragment(
        val text: String,
        val index: Int,
    )

    private fun SimpleColoredComponent.fragments(): List<Fragment> {
        val result = mutableListOf<Fragment>()
        val iterator = iterator()
        while (iterator.hasNext()) {
            iterator.next()
            result.add(Fragment(iterator.fragment, iterator.textAttributes))
        }
        return result
    }

    companion object {
        private const val DEFAULT_FONT_SIZE = 12
        private const val UNIX_SEPARATOR = "/"
        private const val WINDOWS_SEPARATOR = "\\"
    }
}
