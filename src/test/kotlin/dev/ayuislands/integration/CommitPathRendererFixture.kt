package dev.ayuislands.integration

import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNodeRenderer
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vcs.changes.ui.HoverChangesTree
import com.intellij.openapi.vcs.changes.ui.HoverIcon
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.vcsUtil.VcsUtil
import dev.ayuislands.commitpanel.CommitPathShorteningRenderer
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.CommitPathDisplayMode
import dev.ayuislands.settings.PanelWidthMode
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole
import javax.accessibility.AccessibleState
import javax.swing.Icon
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.ToolTipManager
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeCellRenderer
import javax.swing.tree.TreePath
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/** Real SDK tree/renderers; only the read-free row and icons are test-owned presentations. */
internal class CommitPathRendererFixture(
    lockTaking: Boolean = false,
) : AutoCloseable {
    val fileIcon = RendererIcon(Color.BLUE)
    val hoverIcon = RendererIcon(Color.GREEN)
    private var renderingConfiguration: GraphicsConfiguration? = null
    private val root = ChangesBrowserNode.createRoot()
    val rowNode: ChangesBrowserNode<*> = if (lockTaking) lockingNode() else RendererNode(fileIcon)
    val tree: ChangesTree =
        object : ChangesTree(ProjectManager.getInstance().defaultProject, true, false) {
            override fun rebuildTree() = Unit

            override fun getGraphicsConfiguration(): GraphicsConfiguration? =
                renderingConfiguration ?: super.getGraphicsConfiguration()
        }
    val platformRenderer: TreeCellRenderer
    private var wrapper: TreeCellRenderer? = null
    var delegateCalls = 0
    var hoverInvocations = 0
        private set
    var lastInvokedNode: ChangesBrowserNode<*>? = null
        private set
    var lastRequest: RendererRequest? = null
        private set
    val request: RendererRequest
        get() = RendererRequest(rowNode, selected = true, expanded = false, leaf = true, row = 0, focused = true)

    init {
        root.add(rowNode)
        tree.model = DefaultTreeModel(root)
        tree.setSize(WIDTH, HEIGHT)
        tree.isRootVisible = false
        tree.expandPath(TreePath(root.path))
        tree.selectionPath = TreePath(rowNode.path)
        tree.setIncludedChanges(listOf(rowNode.userObject))
        val hoverAction =
            object : HoverIcon(hoverIcon, "Native row action") {
                override fun invokeAction(node: ChangesBrowserNode<*>) {
                    hoverInvocations++
                    lastInvokedNode = node
                }
            }
        object : HoverChangesTree(tree) {
            override fun getHoverIcon(node: ChangesBrowserNode<*>): HoverIcon = hoverAction
        }.install()
        platformRenderer = tree.cellRenderer
    }

    fun usePlatformRenderer() {
        tree.cellRenderer = platformRenderer
    }

    fun useWrappedRenderer(mode: CommitPathDisplayMode) {
        val state =
            AyuIslandsState().apply {
                commitPanelWidthMode = PanelWidthMode.FIXED.name
                commitPanelPathDisplayMode = mode.name
                commitPanelPathMinHiddenLevels = 2
                commitPanelPathMaxHiddenLevels = 2
            }
        val counting =
            TreeCellRenderer { owner, value, selected, expanded, leaf, row, focused ->
                assertSame(tree, owner)
                delegateCalls++
                lastRequest = RendererRequest(value, selected, expanded, leaf, row, focused)
                platformRenderer.getTreeCellRendererComponent(owner, value, selected, expanded, leaf, row, focused)
            }
        val prepared =
            CommitPathShorteningRenderer(counting, {
                dev.ayuislands.commitpanel.CommitPathStyle
                    .capture(state)
            }, canApply = { true })
        tree.cellRenderer = counting
        check(prepared.prepare(tree))
        useRenderer(prepared)
    }

    fun useRenderer(renderer: TreeCellRenderer) {
        wrapper = renderer
        tree.cellRenderer = renderer
    }

    fun setIncluded(included: Boolean) {
        tree.setIncludedChanges(if (included) listOf(rowNode.userObject) else emptyList())
    }

    fun setScale(scale: Double) {
        val base = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        renderingConfiguration =
            object : GraphicsConfiguration() {
                override fun getDevice() = base.device

                override fun getColorModel() = base.colorModel

                override fun getColorModel(transparency: Int) = base.getColorModel(transparency)

                override fun getDefaultTransform(): AffineTransform = AffineTransform.getScaleInstance(scale, scale)

                override fun getNormalizingTransform(): AffineTransform = base.normalizingTransform

                override fun getBounds() = base.bounds
            }
    }

    fun clickHoverAction() {
        moveHover()
        clickAt(hoverPoint())
    }

    fun clickCheckbox() {
        val bounds = assertNotNull(tree.getPathBounds(TreePath(rowNode.path)))
        val nativeCheckbox = checkbox(platformRenderer as Component)
        check(nativeCheckbox.isVisible) { "The native row checkbox must be visible before clicking" }
        clickAt(Point(bounds.x + nativeCheckbox.preferredSize.width / 2, bounds.y + bounds.height / 2))
    }

    fun moveHover() {
        val point = hoverPoint()
        val moved =
            MouseEvent(
                tree,
                MouseEvent.MOUSE_MOVED,
                System.currentTimeMillis(),
                0,
                point.x,
                point.y,
                0,
                false,
                MouseEvent.NOBUTTON,
            )
        tree.mouseMotionListeners.forEach { it.mouseMoved(moved) }
    }

    fun exitHover() {
        val exited =
            MouseEvent(
                tree,
                MouseEvent.MOUSE_EXITED,
                System.currentTimeMillis(),
                0,
                -1,
                -1,
                0,
                false,
                MouseEvent.NOBUTTON,
            )
        tree.mouseListeners.forEach { it.mouseExited(exited) }
    }

    private fun hoverPoint(): Point {
        val bounds = assertNotNull(tree.getPathBounds(TreePath(rowNode.path)))
        val visible = tree.visibleRect
        return Point(visible.x + visible.width - 1, bounds.y + bounds.height / 2)
    }

    private fun clickAt(point: Point) {
        val timestamp = System.currentTimeMillis()
        val pressed =
            MouseEvent(
                tree,
                MouseEvent.MOUSE_PRESSED,
                timestamp,
                InputEvent.BUTTON1_DOWN_MASK,
                point.x,
                point.y,
                1,
                false,
                MouseEvent.BUTTON1,
            )
        tree.mouseListeners.forEach { it.mousePressed(pressed) }
        val released =
            MouseEvent(tree, MouseEvent.MOUSE_RELEASED, timestamp, 0, point.x, point.y, 1, false, MouseEvent.BUTTON1)
        tree.mouseListeners.forEach { it.mouseReleased(released) }
    }

    fun renderPlatform(): JComponent = render(platformRenderer)

    fun renderWrapped(): JComponent = render(checkNotNull(wrapper))

    private fun render(renderer: TreeCellRenderer): JComponent =
        request.let {
            renderer.getTreeCellRendererComponent(
                tree,
                it.value,
                it.selected,
                it.expanded,
                it.leaf,
                it.row,
                it.focused,
            ) as JComponent
        }

    fun accessibility(): RendererAccessibility {
        val child = assertNotNull(tree.accessibleContext.getAccessibleChild(0))
        return accessibility(child.accessibleContext)
    }

    fun accessibility(component: JComponent): RendererAccessibility = accessibility(component.accessibleContext)

    private fun accessibility(context: AccessibleContext): RendererAccessibility =
        RendererAccessibility(
            context.accessibleName.orEmpty(),
            context.accessibleRole,
            context.accessibleStateSet.toArray().toSet(),
        )

    fun treeState(): RendererTreeState =
        RendererTreeState(
            tree.selectionPaths.orEmpty().toList(),
            tree.getExpandedDescendants(TreePath(root.path))?.toList().orEmpty(),
            root.children().toList(),
            tree.includedSet.toSet(),
        )

    fun checkbox(component: Component): JCheckBox = descendants(component).filterIsInstance<JCheckBox>().single()

    fun text(component: Component): SimpleColoredComponent =
        descendants(component).filterIsInstance<SimpleColoredComponent>().single()

    fun fragments(component: SimpleColoredComponent): List<String> =
        buildList {
            val iterator = component.iterator()
            while (iterator.hasNext()) {
                iterator.next()
                add(iterator.fragment)
            }
        }

    fun paint(component: JComponent) {
        component.setSize(WIDTH, HEIGHT)
        component.doLayout()
        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            component.paint(graphics)
        } finally {
            graphics.dispose()
        }
    }

    override fun close() {
        usePlatformRenderer()
        ToolTipManager.sharedInstance().unregisterComponent(tree)
    }

    private fun lockingNode(): ChangesBrowserNode<*> {
        val file = VcsUtil.getFilePath("/renderer/alpha/beta/gamma/delta/File.kt", false)
        val revision =
            object : ContentRevision {
                override fun getFile() = file

                override fun getRevisionNumber(): VcsRevisionNumber = VcsRevisionNumber.NULL

                override fun getContent(): String = ""
            }
        return ChangesBrowserNode.createChange(null, Change(null, revision))
    }

    private fun descendants(component: Component): List<Component> =
        listOf(component) +
            if (component is Container) component.components.flatMap(::descendants) else emptyList()

    companion object {
        const val FILE_NAME = "File.kt"
        const val PATH = "alpha/beta/gamma/delta"
        private const val WIDTH = 260
        private const val HEIGHT = 32
    }
}

private class RendererNode(
    private val fileIcon: Icon,
) : ChangesBrowserNode<String>("Unformatted node identity") {
    override fun getTextPresentation(): String = getUserObject()

    override fun render(
        renderer: ChangesBrowserNodeRenderer,
        selected: Boolean,
        expanded: Boolean,
        hasFocus: Boolean,
    ) {
        renderer.icon = fileIcon
        renderer.append(CommitPathRendererFixture.FILE_NAME, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        renderer.append("  ${CommitPathRendererFixture.PATH}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }
}

internal class RendererIcon(
    private val color: Color,
) : Icon {
    var paints = 0
        private set
    val paintScales = mutableListOf<Double>()

    override fun getIconWidth(): Int = SIZE

    override fun getIconHeight(): Int = SIZE

    override fun paintIcon(
        component: Component?,
        graphics: Graphics,
        x: Int,
        y: Int,
    ) {
        paints++
        paintScales += (graphics as Graphics2D).transform.scaleX
        graphics.color = color
        graphics.fillRect(x, y, SIZE, SIZE)
    }

    private companion object {
        const val SIZE = 12
    }
}

internal data class RendererRequest(
    val value: Any?,
    val selected: Boolean,
    val expanded: Boolean,
    val leaf: Boolean,
    val row: Int,
    val focused: Boolean,
)

internal data class RendererAccessibility(
    val name: String,
    val role: AccessibleRole,
    val states: Set<AccessibleState>,
)

internal data class RendererTreeState(
    val selection: List<TreePath>,
    val expansion: List<TreePath>,
    val children: List<Any>,
    val inclusion: Set<Any>,
)
