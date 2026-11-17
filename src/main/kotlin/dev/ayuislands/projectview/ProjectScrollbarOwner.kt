package dev.ayuislands.projectview

import dev.ayuislands.accent.AccentCheckpoint
import dev.ayuislands.accent.UiDefaultsTarget
import java.util.WeakHashMap
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
import javax.swing.SwingUtilities

/** Owns only the current pane's horizontal scrollbar policy. */
internal class ProjectScrollbarOwner(
    private val currentPane: () -> JScrollPane?,
    private val canAcquire: () -> Boolean,
    private val replay: () -> Unit,
) {
    private val baselines = WeakHashMap<JScrollPane, Int>()
    private var held: Checkpoint? = null
    private var refreshPending = false

    fun deferRefresh(): Boolean {
        if (held == null) return false
        refreshPending = true
        return true
    }

    fun hideLegacy(pane: JScrollPane) = writeLegacy(pane, hideHorizontal = true)

    fun restoreLegacy() {
        baselines.keys.toList().forEach { writeLegacy(it, hideHorizontal = false) }
    }

    private fun writeLegacy(
        pane: JScrollPane,
        hideHorizontal: Boolean,
    ) {
        val before = observe(pane)
        val planned = before.withHiddenScrollbar(hideHorizontal)
        publishBaseline(pane, planned.baseline)
        if (before.policy != planned.policy) pane.horizontalScrollBarPolicy = planned.policy
    }

    fun prepare(hideHorizontal: Boolean): Checkpoint? {
        checkEdt()
        if (!canAcquire()) return null
        check(held == null) { "Project scrollbar recovery must settle before capture" }
        val pane = currentPane() ?: return null
        val before = observe(pane)
        val planned = before.withHiddenScrollbar(hideHorizontal)
        return if (before == planned) null else Checkpoint(pane, before, planned)
    }

    fun close() {
        refreshPending = false
        val checkpoint = held ?: return
        if (checkpoint.hasPending) checkpoint.restore() else checkpoint.releaseResources()
    }

    private fun observe(pane: JScrollPane): Policy =
        Policy(
            pane.horizontalScrollBarPolicy,
            baselines[pane],
        )

    private fun publishBaseline(
        pane: JScrollPane,
        policy: Int?,
    ) {
        if (policy == null) baselines.remove(pane) else baselines[pane] = policy
    }

    internal inner class Checkpoint internal constructor(
        val pane: JScrollPane,
        val before: Policy,
        val planned: Policy,
    ) : AccentCheckpoint {
        private val admissionTarget = UiDefaultsTarget()
        private var expected = before
        val lastWritten: Policy get() = expected
        var phase = Phase.PREPARED
            private set

        override val hasPending: Boolean get() = phase == Phase.OWNED

        fun apply(): Boolean {
            checkEdt()
            check(phase == Phase.PREPARED)
            if (held != null || !isPreparedTarget() || observe(pane) != before) {
                phase = Phase.FOREIGN
                return false
            }
            held = this
            phase = Phase.OWNED
            write(planned)
            return hasPending
        }

        override fun restore(blockedBy: List<AccentCheckpoint>) {
            checkEdt()
            check(phase != Phase.ACCEPTED)
            if (phase == Phase.PREPARED) phase = Phase.RESTORED
            if (!permitsWrite()) return
            check(blockedBy.none { it is Checkpoint && it.pane === pane && it.hasPending }) {
                "Project scrollbar restoration is blocked by a newer write"
            }
            write(before)
            if (hasPending) {
                phase = Phase.RESTORED
                releaseResources()
            }
        }

        override fun commit() {
            checkEdt()
            if (permitsWrite()) phase = Phase.ACCEPTED
        }

        fun commitStudio() {
            commit()
            // The deferred legacy reader still sees the value from before Studio accepted.
            if (phase == Phase.ACCEPTED && held === this) refreshPending = false
        }

        override fun releaseResources() {
            checkEdt()
            if (hasPending || held !== this) return
            held = null
            if (refreshPending) {
                refreshPending = false
                replay()
            }
        }

        override fun retain() {
            checkEdt()
            check(!hasPending || held === this) { "Project scrollbar recovery lost its hold" }
        }

        private fun write(value: Policy) {
            val previous = expected
            expected = value
            publishBaseline(pane, value.baseline)
            try {
                if (pane.horizontalScrollBarPolicy != value.policy) pane.horizontalScrollBarPolicy = value.policy
            } finally {
                val observed = observe(pane)
                val knownPolicy = observed.policy == value.policy || observed.policy == previous.policy
                if (!isCurrentPane() || observed.baseline != value.baseline || !knownPolicy) {
                    relinquish()
                } else {
                    expected = observed
                }
            }
            check(!hasPending || expected == value) { "Project scrollbar write did not settle" }
        }

        private fun permitsWrite(): Boolean {
            if (hasPending && (!isCurrentPane() || observe(pane) != expected)) relinquish()
            return hasPending
        }

        private fun isPreparedTarget(): Boolean = canAcquire() && admissionTarget.isCurrent() && isCurrentPane()

        private fun isCurrentPane(): Boolean = currentPane() === pane

        private fun relinquish() {
            if (baselines[pane] == expected.baseline) baselines.remove(pane)
            phase = Phase.FOREIGN
            refreshPending = false
            releaseResources()
        }
    }

    internal enum class Phase { PREPARED, OWNED, FOREIGN, RESTORED, ACCEPTED }

    internal data class Policy(
        val policy: Int,
        val baseline: Int?,
    ) {
        fun withHiddenScrollbar(hideHorizontal: Boolean): Policy =
            if (hideHorizontal) {
                Policy(HORIZONTAL_SCROLLBAR_NEVER, baseline ?: policy.takeUnless { it == HORIZONTAL_SCROLLBAR_NEVER })
            } else {
                val restored = baseline?.takeIf { policy == HORIZONTAL_SCROLLBAR_NEVER } ?: policy
                Policy(restored, null)
            }
    }

    private companion object {
        fun checkEdt() {
            check(SwingUtilities.isEventDispatchThread()) { "Project scrollbar checkpoints require EDT" }
        }
    }
}
