package dev.ayuislands.editor

import java.awt.Dimension
import javax.swing.JScrollBar

/** A null size means Swing calculates its preferred size from the current UI delegate. */
internal data class ScrollbarSize(
    val width: Int,
    val height: Int,
) {
    fun toDimension(): Dimension = Dimension(width, height)

    companion object {
        fun of(size: Dimension): ScrollbarSize = ScrollbarSize(size.width, size.height)
    }
}

internal data class EditorScrollbarSnapshot(
    val preferredSize: ScrollbarSize?,
    val baseline: Any?,
    val baselineSize: ScrollbarSize? = (baseline as? Dimension)?.let(ScrollbarSize::of),
) {
    fun matches(other: EditorScrollbarSnapshot): Boolean =
        preferredSize == other.preferredSize && matchesBaseline(other)

    fun matchesBaseline(other: EditorScrollbarSnapshot): Boolean =
        baseline === other.baseline && baselineSize == other.baselineSize

    companion object {
        fun read(
            bar: JScrollBar,
            key: String,
        ): EditorScrollbarSnapshot =
            EditorScrollbarSnapshot(
                bar.preferredSize.takeIf { bar.isPreferredSizeSet }?.let(ScrollbarSize::of),
                bar.getClientProperty(key),
            )
    }
}

internal enum class EditorScrollbarWrite { PREPARED, OWNED, RELINQUISHED, RESTORED }

/** Native references live with the prepared transaction; persistence receives only projected evidence. */
internal data class EditorScrollbarRecord(
    val bar: JScrollBar,
    val before: EditorScrollbarSnapshot,
    val planned: EditorScrollbarSnapshot,
    val lastWritten: EditorScrollbarSnapshot,
    val state: EditorScrollbarWrite,
)
