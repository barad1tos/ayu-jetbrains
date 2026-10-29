package dev.ayuislands.accent

import java.awt.Color
import java.awt.Container
import javax.swing.JComponent
import javax.swing.SwingUtilities

/** Journals only peer properties touched by the current synchronous apply. */
internal object ChromePeerOverrides {
    private val pending = mutableListOf<PeerCheckpoint>()
    private val receipts = mutableListOf<PeerCheckpoint>()
    private var boundScope: AccentApplyScope? = null

    fun put(
        component: JComponent,
        color: Color?,
    ) {
        val scope =
            if (SwingUtilities.isEventDispatchThread()) AccentApplyScope.active?.takeUnless { it.isCommitted } else null
        if (scope == null) {
            restorePending(component)
            component.background = color
            return
        }
        scope.checkWritable()
        if (pending.any { it.component === component }) scope.recover { restorePending(component) }
        if (boundScope !== scope) {
            boundScope = scope
            receipts.clear()
            scope.onClose {
                receipts.clear()
                boundScope = null
            }
        }
        val previous = receipts.lastOrNull { it.component === component && it.isUnsettled }
        if (previous?.permitsWrite() == false) return
        val checkpoint = PeerCheckpoint(component)
        receipts += checkpoint
        scope.record(checkpoint)
        checkpoint.write(color)
    }

    private fun restorePending(component: JComponent) {
        val related = pending.filter { it.component === component }.toMutableList()
        try {
            restoreAccentCheckpoints(related)
        } catch (exception: RuntimeException) {
            exception.rethrowIfCancelled()
            throw PeerRecoveryFailure(exception)
        } finally {
            pending.removeAll { !it.hasPending }
        }
    }

    private class PeerCheckpoint(
        val component: JComponent,
    ) : AccentCheckpoint {
        private enum class State { OWNED, RELINQUISHED, RESTORED }

        private val target = UiDefaultsTarget()
        private val attachment: List<Container> = parents()
        private val original: Color? = rawBackground()
        private var expected: Color? = original
        private var state = State.OWNED

        override val hasPending: Boolean
            get() = state == State.OWNED

        val isUnsettled: Boolean
            get() = state != State.RESTORED

        fun permitsWrite(): Boolean {
            if (state != State.OWNED) return false
            if (!target.isCurrent() || !isAttached() || rawBackground() !== expected) {
                state = State.RELINQUISHED
            }
            return state == State.OWNED
        }

        fun write(color: Color?) {
            val previous = expected
            expected = color
            try {
                component.background = color
            } finally {
                val observed = rawBackground()
                when {
                    observed === expected -> Unit
                    observed === previous -> expected = previous
                    else -> state = State.RELINQUISHED
                }
            }
        }

        override fun restore(blockedBy: List<AccentCheckpoint>) {
            if (!hasPending) return
            check(blockedBy.none { it is PeerCheckpoint && it.component === component && it.hasPending }) {
                "Chrome peer restoration blocked by a newer write"
            }
            if (!permitsWrite()) return
            if (expected !== original) write(original)
            component.repaint()
            commit()
        }

        override fun commit() {
            if (state != State.RELINQUISHED) state = State.RESTORED
        }

        override fun retain() {
            if (hasPending && this !in pending) pending += this
        }

        private fun rawBackground(): Color? = if (component.isBackgroundSet) component.background else null

        private fun isAttached(): Boolean {
            val currentParents = parents()
            return currentParents.size == attachment.size &&
                attachment.indices.all { attachment[it] === currentParents[it] }
        }

        private fun parents(): List<Container> = generateSequence(component.parent) { it.parent }.toList()
    }
}

/** Known unresolved recovery must reach element orchestration through standalone tree isolation. */
internal class PeerRecoveryFailure(
    cause: RuntimeException,
) : IllegalStateException("Pending chrome peer recovery failed", cause)
