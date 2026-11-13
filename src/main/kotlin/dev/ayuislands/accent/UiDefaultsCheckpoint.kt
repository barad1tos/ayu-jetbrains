package dev.ayuislands.accent

import java.util.IdentityHashMap
import java.util.function.BiConsumer
import javax.swing.SwingUtilities
import javax.swing.UIDefaults
import javax.swing.UIManager

/** Raw references captured without evaluating native lazy values; identity makes each preparation one-shot. */
internal open class UiDefaultObservation(
    val target: UiDefaultsTarget,
    val key: String,
    val before: Any?,
)

/** A native setter intent, distinct from confirming an observation without a property event. */
internal class UiDefaultWrite(
    target: UiDefaultsTarget,
    key: String,
    before: Any?,
    val planned: Any?,
    val baseline: Any?,
) : UiDefaultObservation(target, key, before)

/**
 * An EDT-local checkpoint for explicitly recorded developer-default writes.
 * Restores raw overrides, including absence and lazy values, only while their
 * identity and look and feel still belong to this operation.
 */
internal class UiDefaultsCheckpoint(
    private val target: UiDefaultsTarget = UiDefaultsTarget(),
) : AccentCheckpoint {
    private val defaults = target.defaults
    private val changes = linkedMapOf<String, Change>()
    private val prepared = IdentityHashMap<UiDefaultObservation, Change?>()
    private val restoreActions = mutableListOf<() -> Unit>()

    override val hasPending: Boolean
        get() = isCurrentLookAndFeel() && (changes.values.any { it !is Change.Finished } || restoreActions.isNotEmpty())

    private sealed interface Change {
        // UI defaults are a native heterogeneous object table, not just colors.
        data class Written(
            val previous: Any?,
            val expected: Any?,
        ) : Change

        data object Relinquished : Change

        data class Finished(
            val state: AccentWriteState,
        ) : Change
    }

    fun put(
        key: String,
        value: Any?,
    ) {
        prepare(key, value)?.let(::apply)
    }

    /** Preparation observes raw state only; the returned handle must be acknowledged before apply. */
    fun prepare(
        key: String,
        value: Any?,
    ): UiDefaultWrite? {
        checkWritable()
        val change = changes[key]
        if (change == Change.Relinquished) return null
        val current = readRawUiDefault(defaults, key)
        if (change is Change.Written && current !== change.expected) {
            changes[key] = Change.Relinquished
            return null
        }
        val previous = if (change is Change.Written) change.previous else current
        return UiDefaultWrite(target, key, current, value, previous).also { prepared[it] = change }
    }

    /** Captures a no-write transition, including release of an already relinquished key. */
    fun observe(key: String): UiDefaultObservation {
        checkWritable()
        return UiDefaultObservation(target, key, readRawUiDefault(defaults, key)).also {
            prepared[it] = changes[key]
        }
    }

    /** Consumes an observation without claiming a native write or invoking a property listener. */
    fun confirm(observation: UiDefaultObservation): Boolean {
        checkWritable()
        if (!prepared.containsKey(observation)) return false
        val capturedChange = prepared.remove(observation)
        return confirmCurrent(observation, capturedChange)
    }

    private fun confirmCurrent(
        observation: UiDefaultObservation,
        capturedChange: Change?,
    ): Boolean {
        if (changes[observation.key] !== capturedChange) return false
        if (readRawUiDefault(defaults, observation.key) !== observation.before) {
            changes[observation.key] = Change.Relinquished
            return false
        }
        return true
    }

    /** Rejects an obsolete higher-level claim without observing or changing the native value. */
    fun discard(observation: UiDefaultObservation) {
        prepared.remove(observation)
    }

    /** Publishes in-memory provenance after admission, before the native setter can call listeners. */
    fun apply(
        write: UiDefaultWrite,
        onAdmitted: () -> Unit = {},
    ): Boolean {
        if (!confirm(write)) return false
        val capturedChange = changes[write.key]
        onAdmitted()
        checkWritable()
        if (!confirmCurrent(write, capturedChange)) return false
        // A property listener can throw after the table has already changed.
        changes[write.key] = Change.Written(write.baseline, write.planned)
        UIManager.put(write.key, write.planned)
        return isCurrentLookAndFeel() && readRawUiDefault(defaults, write.key) === write.planned
    }

    /** Current field outcome; reading it never evaluates lazy values or changes ownership. */
    fun stateOf(observation: UiDefaultObservation): AccentWriteState {
        check(SwingUtilities.isEventDispatchThread()) { "UI defaults observations must run on EDT" }
        require(observation.target === target) { "UI defaults observation belongs to another target" }
        if (!isCurrentLookAndFeel()) return AccentWriteState.RELINQUISHED
        if (prepared.containsKey(observation)) {
            val isCurrent =
                changes[observation.key] === prepared[observation] &&
                    readRawUiDefault(defaults, observation.key) === observation.before
            return if (isCurrent) AccentWriteState.PREPARED else AccentWriteState.RELINQUISHED
        }
        return when (val change = changes[observation.key]) {
            is Change.Written ->
                if (readRawUiDefault(defaults, observation.key) === change.expected) {
                    AccentWriteState.OWNED
                } else {
                    AccentWriteState.RELINQUISHED
                }
            Change.Relinquished -> AccentWriteState.RELINQUISHED
            is Change.Finished -> change.state
            null -> AccentWriteState.RESTORED
        }
    }

    private fun checkWritable() {
        check(SwingUtilities.isEventDispatchThread()) { "UI defaults writes must run on EDT" }
        AccentApplyScope.active?.takeUnless { it.isCommitted }?.checkWritable()
        check(isCurrentLookAndFeel()) { "Look and feel changed during accent application" }
    }

    /** Attempts every surviving write; failures remain retryable on this checkpoint. */
    override fun restore(blockedBy: List<AccentCheckpoint>) = restore(blockedBy, emptySet())

    /** Journal blockers need no live native handle; unrelated keys still get a restoration attempt. */
    fun restoreFields(blockedKeys: Set<String>) = restore(emptyList(), blockedKeys)

    private fun restore(
        blockedBy: List<AccentCheckpoint>,
        blockedKeys: Set<String>,
    ) {
        check(SwingUtilities.isEventDispatchThread()) { "UI defaults restoration must run on EDT" }
        prepared.clear()
        var failure: Throwable? = null
        for ((key, change) in changes.toList().asReversed()) {
            if (!isCurrentLookAndFeel()) break
            val recovery = restoreField(key, change, blockedBy, blockedKeys)
            if (recovery != null) failure = combineUiFailures(failure, recovery)
        }
        if (changes.values.all { it is Change.Finished }) {
            val recovery = restoreProvenance(blockedKeys)
            if (recovery != null) failure = combineUiFailures(failure, recovery)
        }
        failure?.let { throw it }
    }

    private fun restoreField(
        key: String,
        change: Change,
        blockedBy: List<AccentCheckpoint>,
        blockedKeys: Set<String>,
    ): Throwable? {
        if (changes[key] !== change || change is Change.Finished) return null
        if (key in blockedKeys || isBlocked(key, blockedBy)) {
            return IllegalStateException("UI restoration blocked by a newer write: $key")
        }
        if (change !is Change.Written || readRawUiDefault(defaults, key) !== change.expected) {
            changes[key] = Change.Finished(AccentWriteState.RELINQUISHED)
            return null
        }
        return restoreWrite(key, change)
    }

    private fun restoreProvenance(blockedKeys: Set<String>): Throwable? {
        if (blockedKeys.isNotEmpty() && restoreActions.isNotEmpty()) {
            return IllegalStateException("UI ownership restoration blocked by newer writes")
        }
        restoreActions.asReversed().forEach { action -> action() }
        restoreActions.clear()
        return null
    }

    private fun restoreWrite(
        key: String,
        change: Change.Written,
    ): Throwable? {
        val restoration = Change.Written(change.previous, change.previous)
        val recovery =
            runCatching {
                changes[key] = restoration
                UIManager.put(key, change.previous)
                if (changes[key] === restoration) {
                    val state =
                        if (readRawUiDefault(defaults, key) === change.previous) {
                            AccentWriteState.RESTORED
                        } else {
                            AccentWriteState.RELINQUISHED
                        }
                    changes[key] = Change.Finished(state)
                }
            }.exceptionOrNull()
        if (recovery is VirtualMachineError) throw recovery
        // A listener can throw after the table already holds the previous value.
        if (recovery != null && changes[key] === restoration && readRawUiDefault(defaults, key) === change.previous) {
            changes[key] = Change.Finished(AccentWriteState.RESTORED)
        }
        return recovery
    }

    /** Registers state that returns to its pre-apply value once every recorded write is restored or relinquished. */
    fun onRestore(action: () -> Unit) {
        restoreActions += action
    }

    /** Releases recovery records once the visual apply has completed. */
    override fun commit() {
        prepared.clear()
        changes.clear()
        restoreActions.clear()
    }

    override fun retain() {
        if (hasPending) UiDefaultsRecovery.retain(this)
    }

    private fun isCurrentLookAndFeel(): Boolean = target.isCurrent()

    /** Null means this segment has not touched the key; false permanently yields to a foreign writer. */
    fun permitsWrite(key: String): Boolean? {
        val change = changes[key] ?: return null
        if (change is Change.Finished) return null
        if (change !is Change.Written) return false
        if (!isCurrentLookAndFeel() || readRawUiDefault(defaults, key) !== change.expected) {
            changes[key] = Change.Relinquished
            return false
        }
        return true
    }

    private fun blocks(
        table: UIDefaults,
        key: String,
    ): Boolean = defaults === table && changes[key] is Change.Written

    private fun isBlocked(
        key: String,
        blockedBy: List<AccentCheckpoint>,
    ): Boolean = blockedBy.any { newer -> newer is UiDefaultsCheckpoint && newer.blocks(defaults, key) }
}

/** Every segment of one apply uses the same physical target captured at entry. */
internal class UiDefaultsTarget {
    val defaults: UIDefaults = UIManager.getDefaults()
    private val lookAndFeel = UIManager.getLookAndFeel()
    private val lookAndFeelDefaults = UIManager.getLookAndFeelDefaults()

    fun isCurrent(): Boolean =
        UIManager.getDefaults() === defaults &&
            UIManager.getLookAndFeel() === lookAndFeel &&
            UIManager.getLookAndFeelDefaults() === lookAndFeelDefaults
}

/**
 * Owns failed UI receipts until an existing apply/revert trigger retries them.
 * Recovery moves IDLE -> RESTORING -> IDLE; nested recovery fails before effects.
 */
internal object UiDefaultsRecovery {
    private enum class State { IDLE, RESTORING }

    private var state = State.IDLE
    private val pending = mutableListOf<UiDefaultsCheckpoint>()

    fun retain(checkpoint: UiDefaultsCheckpoint) {
        if (pending.none { it === checkpoint }) pending += checkpoint
    }

    fun restorePending() {
        check(state == State.IDLE) { "UI defaults recovery is already in progress" }
        state = State.RESTORING
        var failure: Throwable? = null
        val blocked = mutableListOf<AccentCheckpoint>()
        try {
            for (checkpoint in pending.toList().asReversed()) {
                val recovery = runCatching { checkpoint.restore(blocked) }.exceptionOrNull()
                if (recovery is VirtualMachineError) throw recovery
                if (checkpoint.hasPending) blocked += checkpoint else pending.remove(checkpoint)
                if (recovery != null) failure = combineUiFailures(failure, recovery)
            }
        } finally {
            state = State.IDLE
        }
        failure?.let { throw it }
        check(pending.isEmpty()) { "UI defaults recovery remains incomplete" }
    }
}

internal fun readRawUiDefault(
    defaults: UIDefaults,
    key: String,
): Any? {
    var result: Any? = null
    // The inherited Hashtable traversal reads developer entries without
    // resolving LAF fallbacks or evaluating UIDefaults lazy/active values.
    val capture =
        BiConsumer<Any, Any> { candidate, value ->
            if (candidate == key) result = value
        }
    defaults.forEach(capture)
    return result
}

internal fun combineUiFailures(
    previous: Throwable?,
    next: Throwable,
): Throwable {
    if (previous == null || previous === next) return next
    val previousCancelled = previous is RuntimeException && previous.isAccentCancellation()
    val nextCancelled = next is RuntimeException && next.isAccentCancellation()
    if (nextCancelled && !previousCancelled) {
        next.addSuppressed(previous)
        return next
    }
    previous.addSuppressed(next)
    return previous
}
