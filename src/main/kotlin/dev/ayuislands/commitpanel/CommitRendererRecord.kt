package dev.ayuislands.commitpanel

import dev.ayuislands.accent.AccentWriteState
import javax.swing.JTree
import javax.swing.tree.TreeCellRenderer

internal data class CommitRendererRecord(
    val tree: JTree,
    val before: TreeCellRenderer?,
    val planned: TreeCellRenderer?,
    val lastWritten: TreeCellRenderer?,
    val state: AccentWriteState,
)

/** Tracks one native identity independently when a Commit tree is replaced during a transaction. */
internal class CommitRendererCell(
    val tree: JTree,
    private val before: TreeCellRenderer?,
    private val planned: TreeCellRenderer?,
) {
    private var expected = before
    var state = AccentWriteState.PREPARED
        private set
    val record: CommitRendererRecord get() = CommitRendererRecord(tree, before, planned, expected, state)

    fun apply() {
        if (!matches()) return
        state = AccentWriteState.OWNED
        write(planned)
    }

    fun restore(isCurrent: Boolean) {
        if (state == AccentWriteState.PREPARED) {
            state = AccentWriteState.RESTORED
            return
        }
        if (state != AccentWriteState.OWNED) return
        if (!isCurrent) {
            state = AccentWriteState.RELINQUISHED
            return
        }
        if (!matches()) return
        write(before)
        if (matches()) state = AccentWriteState.RESTORED
    }

    fun accept(isCurrent: Boolean) {
        if (state != AccentWriteState.OWNED) return
        state = if (isCurrent && matches()) AccentWriteState.RESTORED else AccentWriteState.RELINQUISHED
    }

    private fun matches(): Boolean {
        if (tree.cellRenderer === expected) return true
        state = AccentWriteState.RELINQUISHED
        return false
    }

    private fun write(next: TreeCellRenderer?) {
        if (expected === next) return
        try {
            tree.cellRenderer = next
        } finally {
            val observed = tree.cellRenderer
            if (observed === next) {
                expected = next
            } else if (observed !== expected) {
                state = AccentWriteState.RELINQUISHED
            }
        }
    }
}
