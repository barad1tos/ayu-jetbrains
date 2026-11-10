package dev.ayuislands.toolwindow

import com.intellij.openapi.ui.ThreeComponentsSplitter
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.openapi.wm.ex.ToolWindowEx
import java.awt.Component
import java.awt.Frame
import java.awt.Rectangle
import java.awt.Window
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.event.TreeExpansionListener

internal data class ToolWindowBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    constructor(bounds: Rectangle) : this(bounds.x, bounds.y, bounds.width, bounds.height)

    fun rectangle(): Rectangle = Rectangle(x, y, width, height)
}

internal sealed interface ToolWindowWidthValue {
    data class Size(
        val pixels: Int,
    ) : ToolWindowWidthValue

    data class Bounds(
        val rectangle: ToolWindowBounds,
    ) : ToolWindowWidthValue
}

internal enum class ToolWindowWidthField { FIRST_SIZE, LAST_SIZE, BOUNDS }

internal data class ToolWindowListenerValue(
    val attached: Boolean,
    val enabled: Boolean,
)

internal data class ToolWindowListenerRecord(
    val tree: JTree,
    val listener: TreeExpansionListener,
    val before: ToolWindowListenerValue,
    val planned: ToolWindowListenerValue,
    val lastWritten: ToolWindowListenerValue,
    val state: dev.ayuislands.accent.AccentWriteState,
)

/** Native identity and values for the outer Workspace receipt projection. */
internal data class ToolWindowWidthRecord(
    val component: Component,
    val field: ToolWindowWidthField,
    val before: ToolWindowWidthValue,
    val planned: ToolWindowWidthValue,
    val lastWritten: ToolWindowWidthValue,
    val state: dev.ayuislands.accent.AccentWriteState,
)

internal data class ToolWindowWidthGeometry(
    val value: ToolWindowWidthValue,
    val layout: List<ToolWindowBounds?> = emptyList(),
)

/** Only the three native setters already reached by ToolWindowAutoFitter's forward path. */
internal sealed interface ToolWindowWidthPeer {
    val component: Component
    val field: ToolWindowWidthField
    val before: ToolWindowWidthGeometry

    fun current(): Boolean

    fun geometry(): ToolWindowWidthGeometry

    fun plan(
        desiredWidth: Int,
        delta: Int,
    ): ToolWindowWidthValue

    fun restore()

    companion object {
        fun capture(
            window: ToolWindowEx,
            content: JComponent,
        ): ToolWindowWidthPeer? =
            when (window.type) {
                ToolWindowType.DOCKED -> {
                    docked(content, window.anchor)
                }

                ToolWindowType.SLIDING -> {
                    sliding(window, content)
                }

                ToolWindowType.FLOATING, ToolWindowType.WINDOWED -> {
                    SwingUtilities
                        .getWindowAncestor(content)
                        ?.takeIf {
                            it.isDisplayable && it.isVisible && (it !is Frame || it.extendedState == Frame.NORMAL)
                        }?.let(::WindowPeer)
                }
            }

        private fun docked(
            content: JComponent,
            anchor: ToolWindowAnchor,
        ): DockedPeer? =
            generateSequence<Component>(content) { it.parent }
                .zipWithNext()
                .mapNotNull { (child, parent) ->
                    val splitter = parent as? ThreeComponentsSplitter ?: return@mapNotNull null
                    val side =
                        (if (first(anchor)) splitter.firstComponent else splitter.lastComponent)
                            ?: return@mapNotNull null
                    if (splitter.orientation == anchor.isHorizontal && side === child && side.isVisible) {
                        DockedPeer(splitter, side, anchor)
                    } else {
                        null
                    }
                }.toList()
                .singleOrNull()

        private fun sliding(
            window: ToolWindowEx,
            content: JComponent,
        ): SlidingPeer? {
            val decorator: JComponent = window.decorator
            val parent = decorator.parent as? JLayeredPane ?: return null
            if (parent.getLayer(decorator) != JLayeredPane.PALETTE_LAYER ||
                !SwingUtilities.isDescendingFrom(content, decorator)
            ) {
                return null
            }
            val splitters =
                parent.getComponentsInLayer(JLayeredPane.DEFAULT_LAYER).flatMap {
                    AutoFitCalculator
                        .findAllOfType(
                            it,
                            ThreeComponentsSplitter::class.java,
                        ).filterIsInstance<ThreeComponentsSplitter>()
                }
            val clamp = splitters.singleOrNull { it.orientation == window.anchor.isHorizontal } ?: return null
            return SlidingPeer(window, decorator, parent, clamp)
        }

        private fun first(anchor: ToolWindowAnchor): Boolean =
            anchor == ToolWindowAnchor.LEFT || anchor == ToolWindowAnchor.TOP
    }
}

private class DockedPeer(
    private val splitter: ThreeComponentsSplitter,
    private val side: JComponent,
    anchor: ToolWindowAnchor,
) : ToolWindowWidthPeer {
    private val first = anchor == ToolWindowAnchor.LEFT || anchor == ToolWindowAnchor.TOP
    private val vertical = anchor.isHorizontal
    private val context = context()
    override val component: Component = splitter
    override val field = if (first) ToolWindowWidthField.FIRST_SIZE else ToolWindowWidthField.LAST_SIZE
    override val before = geometry()

    override fun current(): Boolean = context() == context

    override fun geometry(): ToolWindowWidthGeometry =
        ToolWindowWidthGeometry(
            ToolWindowWidthValue.Size(if (first) splitter.firstSize else splitter.lastSize),
            listOf(splitter.firstComponent, splitter.innerComponent, splitter.lastComponent).map {
                it?.bounds?.let(::ToolWindowBounds)
            },
        )

    override fun plan(
        desiredWidth: Int,
        delta: Int,
    ): ToolWindowWidthValue =
        ToolWindowWidthValue.Size(
            maxOf(
                splitter.getMinSize(first),
                minOf(
                    splitter.getMaxSize(first),
                    (if (vertical) side.height else side.width) + delta,
                ),
            ),
        )

    override fun restore() {
        val size = (before.value as ToolWindowWidthValue.Size).pixels
        if (first) splitter.firstSize = size else splitter.lastSize = size
    }

    private fun context(): DockedContext =
        DockedContext(
            listOf(splitter.firstComponent, splitter.innerComponent, splitter.lastComponent),
            listOf(
                splitter.firstComponent?.isVisible,
                splitter.innerComponent?.isVisible,
                splitter.lastComponent?.isVisible,
            ),
            ToolWindowBounds(splitter.bounds),
            splitter.orientation,
            splitter.dividerWidth,
            splitter.getMinSize(first),
            splitter.getMaxSize(first),
            if (first) splitter.lastSize else splitter.firstSize,
        )

    private data class DockedContext(
        val components: List<JComponent?>,
        val visible: List<Boolean?>,
        val bounds: ToolWindowBounds,
        val vertical: Boolean,
        val dividerWidth: Int,
        val minimum: Int,
        val maximum: Int,
        val oppositeSize: Int,
    )
}

private class WindowPeer(
    private val window: Window,
) : ToolWindowWidthPeer {
    private val minimum = window.minimumSize
    private val explicitMinimum = window.isMinimumSizeSet
    private val graphics = window.graphicsConfiguration
    override val component: Component = window
    override val field = ToolWindowWidthField.BOUNDS
    override val before = geometry()

    override fun current(): Boolean =
        window.isVisible &&
            window.isDisplayable &&
            window.minimumSize == minimum &&
            window.isMinimumSizeSet == explicitMinimum &&
            window.graphicsConfiguration === graphics &&
            (window !is Frame || window.extendedState == Frame.NORMAL)

    override fun geometry(): ToolWindowWidthGeometry =
        ToolWindowWidthGeometry(ToolWindowWidthValue.Bounds(ToolWindowBounds(window.bounds)))

    override fun plan(
        desiredWidth: Int,
        delta: Int,
    ): ToolWindowWidthValue =
        ToolWindowWidthValue.Bounds(
            ToolWindowBounds(window.bounds).copy(
                width = if (explicitMinimum) maxOf(desiredWidth, minimum.width) else desiredWidth,
                height = if (explicitMinimum) maxOf(window.height, minimum.height) else window.height,
            ),
        )

    override fun restore() {
        val bounds = (before.value as ToolWindowWidthValue.Bounds).rectangle
        window.setSize(bounds.width, bounds.height)
    }
}

private class SlidingPeer(
    private val window: ToolWindowEx,
    private val decorator: JComponent,
    private val parent: JLayeredPane,
    private val clamp: ThreeComponentsSplitter,
) : ToolWindowWidthPeer {
    private val anchor = window.anchor
    private val first = anchor == ToolWindowAnchor.LEFT || anchor == ToolWindowAnchor.TOP
    private val parentBounds = parent.bounds
    private val clampBounds = clamp.bounds
    private val minimum = clamp.getMinSize(first)
    private val maximum = clamp.getMaxSize(first)
    private val zOrder = parent.getComponentZOrder(decorator)
    override val component: Component = decorator
    override val field = ToolWindowWidthField.BOUNDS
    override val before = geometry()

    override fun current(): Boolean =
        window.decorator === decorator &&
            decorator.parent === parent &&
            parent.bounds == parentBounds &&
            parent.getLayer(
                decorator,
            ) == JLayeredPane.PALETTE_LAYER &&
            parent.getComponentZOrder(decorator) == zOrder &&
            clamp.bounds == clampBounds &&
            clamp.getMinSize(first) == minimum &&
            clamp.getMaxSize(first) == maximum

    override fun geometry(): ToolWindowWidthGeometry =
        ToolWindowWidthGeometry(ToolWindowWidthValue.Bounds(ToolWindowBounds(decorator.bounds)))

    override fun plan(
        desiredWidth: Int,
        delta: Int,
    ): ToolWindowWidthValue {
        val before = ToolWindowBounds(decorator.bounds)
        val extent = if (anchor.isHorizontal) before.height else before.width
        val next = maxOf(minimum, minOf(maximum, extent + delta))
        val bounds =
            when (anchor) {
                ToolWindowAnchor.LEFT -> before.copy(width = next)
                ToolWindowAnchor.RIGHT -> before.copy(x = before.x - (next - before.width), width = next)
                ToolWindowAnchor.TOP -> before.copy(height = next)
                ToolWindowAnchor.BOTTOM -> before.copy(y = before.y - (next - before.height), height = next)
                else -> error("Unsupported tool window anchor")
            }
        return ToolWindowWidthValue.Bounds(bounds)
    }

    override fun restore() {
        decorator.bounds = (before.value as ToolWindowWidthValue.Bounds).rectangle.rectangle()
    }
}
