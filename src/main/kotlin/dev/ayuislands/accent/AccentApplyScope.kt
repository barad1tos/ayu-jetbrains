package dev.ayuislands.accent

import javax.swing.SwingUtilities
import javax.swing.UIManager

/** A surface owns its snapshots and recognizes overlapping unresolved receipts. */
internal interface AccentCheckpoint {
    val hasPending: Boolean

    fun restore(blockedBy: List<AccentCheckpoint> = emptyList())

    fun commit()

    /** Retires superseded native resources after all accepted visuals have committed. */
    fun releaseResources() = Unit

    /** Keeps unresolved recovery at the surface owner without retrying its writes. */
    fun retain()
}

/**
 * One synchronous EDT attempt. Successful segments commit together; an abort
 * restores them in reverse order without crossing unresolved newer writes.
 */
internal class AccentApplyScope {
    /** Receipts of one lexical worker or element; identity separates sibling recovery. */
    class Segment {
        private val receipts = mutableSetOf<AccentCheckpoint>()

        fun record(checkpoint: AccentCheckpoint) {
            receipts += checkpoint
        }

        operator fun contains(checkpoint: AccentCheckpoint): Boolean = checkpoint in receipts
    }

    var segment = Segment()
        private set

    private enum class State {
        RECORDING,
        ABORT_REQUESTED,
        RESTORING,
        RESTORING_ABORT_REQUESTED,
        COMMITTED,
        RESTORED,
        INCOMPLETE,
    }

    private var state = State.RECORDING
    private val checkpoints = mutableListOf<AccentCheckpoint>()
    private val failedChildren = mutableSetOf<AccentCheckpoint>()
    private val closeActions = mutableListOf<() -> Unit>()
    private var currentUi: UiDefaultsCheckpoint? = null
    private lateinit var uiTarget: UiDefaultsTarget

    val isCommitted: Boolean
        get() = state == State.COMMITTED

    val uiDefaults: UiDefaultsCheckpoint
        get() {
            checkWritable()
            return currentUi ?: run {
                recoverUiDefaults()
                UiDefaultsCheckpoint(uiTarget).also { checkpoint ->
                    uiDefaultsOwner.record(checkpoint)
                    record(checkpoint)
                    currentUi = checkpoint
                }
            }
        }

    fun open() {
        check(SwingUtilities.isEventDispatchThread()) { "Accent checkpoint scope must run on EDT" }
        check(active == null) { "Accent checkpoint scope is already active" }
        active = this
        uiTarget = UiDefaultsTarget()
    }

    fun close() {
        check(active === this) { "Closing a different accent checkpoint scope" }
        active = null
        checkpoints.filter { it.hasPending }.forEach(AccentCheckpoint::retain)
        closeActions.forEach { it() }
        closeActions.clear()
    }

    /** Runs boundary cleanup or queues derived work after pending recovery has transferred. */
    fun onClose(action: () -> Unit) {
        check(active === this) { "Accent checkpoint scope is not active" }
        closeActions += action
    }

    fun <T> runStep(action: () -> T): T {
        if (!isCommitted) checkWritable()
        val previous = currentUi
        val previousSegment = segment
        currentUi = null
        segment = Segment()
        return try {
            action()
        } finally {
            currentUi = previous
            segment = previousSegment
        }
    }

    fun record(checkpoint: AccentCheckpoint) {
        checkWritable()
        checkpoints += checkpoint
        segment.record(checkpoint)
    }

    fun put(
        key: String,
        value: Any?,
    ) {
        checkWritable()
        val permitted =
            checkpoints.asReversed().firstNotNullOfOrNull { receipt ->
                (receipt as? UiDefaultsCheckpoint)?.permitsWrite(key)
            }
        if (permitted == false) return
        uiDefaults.put(key, value)
    }

    fun commit() {
        checkWritable()
        val accepted = checkpoints.filterNot { it in failedChildren && it.hasPending }
        checkpoints.filterNot { it in accepted }.forEach(AccentCheckpoint::retain)
        accepted.forEach(AccentCheckpoint::commit)
        state = State.COMMITTED
        var failure: Throwable? = null
        for (checkpoint in accepted) {
            try {
                checkpoint.releaseResources()
            } catch (exception: RuntimeException) {
                failure = combineUiFailures(failure, exception)
            }
        }
        checkpoints.removeAll { !it.hasPending }
        failure?.let { throw it }
    }

    fun requestAbort() {
        state =
            when (state) {
                State.RECORDING -> State.ABORT_REQUESTED
                State.RESTORING -> State.RESTORING_ABORT_REQUESTED
                State.ABORT_REQUESTED, State.RESTORING_ABORT_REQUESTED,
                State.COMMITTED, State.RESTORED, State.INCOMPLETE,
                -> state
            }
    }

    fun restore() {
        check(state == State.RECORDING || state == State.ABORT_REQUESTED || state == State.INCOMPLETE)
        state = State.RESTORING
        try {
            restoreReceipts(checkpoints.toList())
        } finally {
            state = if (checkpoints.isEmpty()) State.RESTORED else State.INCOMPLETE
        }
    }

    fun elementRollback(): () -> Unit {
        val older = checkpoints.toSet()
        return {
            val children = checkpoints.filter { it !in older }
            state = if (state == State.ABORT_REQUESTED) State.RESTORING_ABORT_REQUESTED else State.RESTORING
            try {
                restoreReceipts(children)
            } finally {
                failedChildren += children.filter { it.hasPending }
                state = if (state == State.RESTORING_ABORT_REQUESTED) State.ABORT_REQUESTED else State.RECORDING
            }
        }
    }

    private fun restoreReceipts(receipts: List<AccentCheckpoint>) {
        val blocked = mutableListOf<AccentCheckpoint>()
        var failure: Throwable? = null
        for (checkpoint in receipts.asReversed()) {
            val recovery = runCatching { checkpoint.restore(blocked) }.exceptionOrNull()
            if (recovery is VirtualMachineError) throw recovery
            if (!checkpoint.hasPending) checkpoints.remove(checkpoint) else blocked += checkpoint
            if (recovery != null) failure = combineUiFailures(failure, recovery)
        }
        failure?.let { throw it }
    }

    fun <T> restoringOnFailure(action: () -> T): T =
        try {
            action()
        } catch (failure: RuntimeException) {
            val recovery = if (state == State.COMMITTED) null else runCatching { restore() }.exceptionOrNull()
            if (recovery is VirtualMachineError) throw recovery
            throw recovery?.let { combineUiFailures(failure, it) } ?: failure
        }

    fun restoreAfterTear(applyFailures: List<Throwable>): Throwable? {
        if (state == State.COMMITTED) return null
        val recovery = runCatching { restore() }.exceptionOrNull()
        if (recovery is VirtualMachineError) throw recovery
        if (recovery is RuntimeException && recovery.isAccentCancellation()) {
            applyFailures.filter { it !== recovery }.forEach(recovery::addSuppressed)
            throw recovery
        }
        return recovery?.let { IllegalStateException("Accent restoration after torn apply failed", it) }
    }

    fun checkWritable() {
        check(state == State.RECORDING) { "Accent apply was interrupted" }
        if (::uiTarget.isInitialized) {
            check(uiTarget.isCurrent()) { "Look and feel changed during accent application" }
        }
    }

    private fun recoverUiDefaults() = recover(UiDefaultsRecovery::restorePending)

    fun recover(action: () -> Unit) {
        checkWritable()
        state = State.RESTORING
        try {
            action()
        } finally {
            state = if (state == State.RESTORING_ABORT_REQUESTED) State.ABORT_REQUESTED else State.RECORDING
        }
        checkWritable()
    }

    companion object {
        var active: AccentApplyScope? = null
            private set
    }
}

/** Restores newer receipts first, retaining failed ownership before older writes can run. */
internal fun <T : AccentCheckpoint> restoreAccentCheckpoints(pending: MutableList<T>) {
    val blocked = mutableListOf<AccentCheckpoint>()
    var failure: Throwable? = null
    for (checkpoint in pending.toList().asReversed()) {
        try {
            checkpoint.restore(blocked)
        } catch (exception: RuntimeException) {
            failure = combineUiFailures(failure, exception)
        }
        if (checkpoint.hasPending) blocked += checkpoint else pending.remove(checkpoint)
    }
    failure?.let { throw it }
}

/** Keeps standalone element calls unchanged while enrolling synchronous apply writes. */
internal fun putAccentUiDefault(
    key: String,
    value: Any?,
) {
    val scope =
        if (SwingUtilities.isEventDispatchThread()) AccentApplyScope.active?.takeUnless { it.isCommitted } else null
    if (scope == null) UIManager.put(key, value) else scope.put(key, value)
}

internal fun <T> withAccentSegment(action: () -> T): T {
    val scope = AccentApplyScope.active ?: return action()
    return scope.runStep(action)
}
