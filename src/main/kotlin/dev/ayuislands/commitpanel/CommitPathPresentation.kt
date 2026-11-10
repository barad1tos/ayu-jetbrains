package dev.ayuislands.commitpanel

import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.CommitPathDisplayMode
import dev.ayuislands.settings.PanelWidthMode
import java.awt.Component
import java.awt.Container
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.ToolTipManager

/** The existing path policy, applied only while native presentation is prepared. */
internal object CommitPathPresentation {
    fun apply(
        tree: JTree,
        component: Component,
        style: CommitPathStyle?,
    ) {
        if (style == null || style.widthMode == PanelWidthMode.DEFAULT) return
        component.clearRendererTooltips()
        val text = component.findPathTextComponent() ?: return
        shortenPathFragment(tree, text, component.preferredSize.width, style)
        if (component is JComponent && component !== text) component.toolTipText = text.toolTipText
    }

    fun text(component: Component): SimpleColoredComponent? {
        if (component is SimpleColoredComponent) return component
        if (component !is Container) return null
        return component.components.firstNotNullOfOrNull(::text)
    }

    fun readFragments(component: SimpleColoredComponent): List<CommitFragment> = component.fragments()

    private fun shortenPathFragment(
        tree: JTree,
        component: SimpleColoredComponent,
        fullRowWidth: Int,
        style: CommitPathStyle,
    ) {
        val fragments = component.fragments()
        val pathIndex = fragments.pathFragmentIndex()
        if (pathIndex < 0 || fragments.drop(pathIndex).any { it.tag != null }) return

        val originalPath = PathFragment(text = fragments[pathIndex].text, index = pathIndex)
        if (style.mode == CommitPathDisplayMode.TOOLTIP) {
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
                    minHiddenLevels = style.minimum,
                    maxHiddenLevels = style.maximum,
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
                fragment.tag,
            )
        }
    }

    private fun applyTooltipOnlyPath(
        tree: JTree,
        component: SimpleColoredComponent,
        fragments: List<CommitFragment>,
        path: PathFragment,
    ) {
        val tooltip = path.text.trim().takeIf { it.isNotEmpty() } ?: return
        ToolTipManager.sharedInstance().registerComponent(tree)

        val icon = component.icon
        component.clear()
        component.icon = icon
        if (fragments.hasPathAnchorBefore(path)) {
            for ((index, fragment) in fragments.withIndex()) {
                if (index != path.index) component.append(fragment.text, fragment.attributes, fragment.tag)
            }
        } else {
            val pathLeaf = path.text.lastPathSegment() ?: tooltip
            component.append(pathLeaf, fragments[path.index].attributes, fragments[path.index].tag)
        }
        component.toolTipText = tooltip
    }

    private fun List<CommitFragment>.hasPathAnchorBefore(path: PathFragment): Boolean =
        take(path.index).any { fragment -> fragment.text.isNotBlank() && !fragment.text.isPathText() }

    private fun availableRowWidth(
        tree: JTree,
        fullRowWidth: Int,
        path: PathFragment,
        fragments: List<CommitFragment>,
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
        fragments: List<CommitFragment>,
        measureTextWidth: (String) -> Int,
    ): Int? {
        val anchorText = pathAnchorText(fragments, path) ?: return null
        val pathPrefix = path.text.takeWhile { it.isWhitespace() }
        val targetPathWidth = measureTextWidth(pathPrefix) + measureTextWidth(anchorText)
        val originalPathWidth = measureTextWidth(path.text)
        return (fullRowWidth - originalPathWidth + targetPathWidth).coerceAtLeast(0)
    }

    private fun pathAnchorText(
        fragments: List<CommitFragment>,
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

    private fun Component.clearRendererTooltips() {
        if (this is JComponent) toolTipText = null
        if (this !is Container) return

        for (child in components) {
            child.clearRendererTooltips()
        }
    }

    private fun SimpleColoredComponent.hasPathFragment(): Boolean = fragments().pathFragmentIndex() >= 0

    private fun List<CommitFragment>.pathFragmentIndex(): Int {
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

    internal data class CommitFragment(
        val text: String,
        val attributes: SimpleTextAttributes,
        val tag: Any?,
    )

    private data class PathFragment(
        val text: String,
        val index: Int,
    )

    private fun SimpleColoredComponent.fragments(): List<CommitFragment> {
        val result = mutableListOf<CommitFragment>()
        val iterator = iterator()
        while (iterator.hasNext()) {
            iterator.next()
            result.add(CommitFragment(iterator.fragment, iterator.textAttributes, getFragmentTag(result.size)))
        }
        return result
    }

    private const val DEFAULT_FONT_SIZE = 12
    private const val UNIX_SEPARATOR = "/"
    private const val WINDOWS_SEPARATOR = "\\"
}

internal data class CommitPathStyle(
    val widthMode: PanelWidthMode,
    val mode: CommitPathDisplayMode,
    val minimum: Int,
    val maximum: Int,
) {
    companion object {
        fun capture(state: dev.ayuislands.studio.persistence.AyuSettingsView): CommitPathStyle =
            CommitPathStyle(
                PanelWidthMode.fromString(state.commitPanelWidthMode),
                CommitPathDisplayMode.fromString(state.commitPanelPathDisplayMode),
                state.commitPanelPathMinHiddenLevels,
                state.commitPanelPathMaxHiddenLevels,
            )

        fun capture(state: AyuIslandsState): CommitPathStyle =
            CommitPathStyle(
                PanelWidthMode.fromString(state.commitPanelWidthMode),
                CommitPathDisplayMode.fromString(state.commitPanelPathDisplayMode),
                state.commitPanelPathMinHiddenLevels,
                state.commitPanelPathMaxHiddenLevels,
            )
    }
}
