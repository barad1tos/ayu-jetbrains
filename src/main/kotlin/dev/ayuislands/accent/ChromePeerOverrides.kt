package dev.ayuislands.accent

import java.awt.Color
import java.awt.Container
import javax.swing.JComponent
import javax.swing.SwingUtilities

/** Retains exact peer backgrounds for immediate and acknowledged accent applies. */
internal object ChromePeerOverrides {
    private val pending = mutableListOf<PeerCheckpoint>()
    private val receipts = mutableListOf<PeerCheckpoint>()
    private var boundScope: AccentApplyScope? = null

    /** Observes one exact peer without running pending recovery or changing its background. */
    fun prepare(
        component: JComponent,
        color: Color?,
    ): PeerCheckpoint {
        check(SwingUtilities.isEventDispatchThread()) { "Chrome peer preparation must run on EDT" }
        AccentApplyScope.active?.takeUnless { it.isCommitted }?.checkWritable()
        checkNoPending(component)
        return PeerCheckpoint(component, color)
    }

    private fun checkNoPending(component: JComponent) {
        check(pending.none { it.component === component && it.hasPending }) {
            "Chrome peer has unresolved recovery"
        }
    }

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
        val checkpoint = prepare(component, color)
        receipts += checkpoint
        scope.record(checkpoint)
        checkpoint.apply()
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

    class PeerCheckpoint internal constructor(
        val component: JComponent,
        val planned: Color?,
    ) : AccentWriteCheckpoint {
        val target = UiDefaultsTarget()
        private val attachment: List<Container> = parents()
        val before: Color? = rawBackground()
        private var expected: Color? = before
        override var state = AccentWriteState.PREPARED
            private set

        override val hasPending: Boolean
            get() = state == AccentWriteState.OWNED

        val isUnsettled: Boolean
            get() = state != AccentWriteState.RESTORED

        /** Applies the captured value once, after checking raw identity, attachment and theme. */
        override fun apply(): Boolean {
            check(SwingUtilities.isEventDispatchThread()) { "Chrome peer writes must run on EDT" }
            if (state != AccentWriteState.PREPARED) return false
            AccentApplyScope.active?.takeUnless { it.isCommitted }?.checkWritable()
            checkNoPending(component)
            if (!target.isCurrent() || !isAttached() || rawBackground() !== before) {
                state = AccentWriteState.RELINQUISHED
                return false
            }
            state = AccentWriteState.OWNED
            write(planned)
            return permitsWrite() && expected === planned
        }

        fun permitsWrite(): Boolean {
            if (state != AccentWriteState.OWNED) return false
            if (!target.isCurrent() || !isAttached() || rawBackground() !== expected) {
                state = AccentWriteState.RELINQUISHED
            }
            return state == AccentWriteState.OWNED
        }

        private fun write(color: Color?) {
            val previous = expected
            expected = color
            try {
                component.background = color
            } finally {
                val observed = rawBackground()
                when {
                    observed === expected -> Unit
                    observed === previous -> expected = previous
                    else -> state = AccentWriteState.RELINQUISHED
                }
            }
        }

        override fun restore(blockedBy: List<AccentCheckpoint>) {
            if (state == AccentWriteState.PREPARED) commit()
            if (!hasPending) return
            check(blockedBy.none { it is PeerCheckpoint && it.component === component && it.hasPending }) {
                "Chrome peer restoration blocked by a newer write"
            }
            if (!permitsWrite()) return
            if (expected !== before) write(before)
            component.repaint()
            commit()
        }

        override fun commit() {
            if (state != AccentWriteState.RELINQUISHED) state = AccentWriteState.RESTORED
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
