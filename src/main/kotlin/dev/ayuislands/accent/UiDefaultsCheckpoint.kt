package dev.ayuislands.accent

import java.util.function.BiConsumer
import javax.swing.SwingUtilities
import javax.swing.UIDefaults
import javax.swing.UIManager

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
    private val restoreActions = mutableListOf<() -> Unit>()

    override val hasPending: Boolean
        get() = isCurrentLookAndFeel() && (changes.isNotEmpty() || restoreActions.isNotEmpty())

    private sealed interface Change {
        // UI defaults are a native heterogeneous object table, not just colors.
        data class Written(
            val previous: Any?,
            val expected: Any?,
        ) : Change

        data object Relinquished : Change
    }

    fun put(
        key: String,
        value: Any?,
    ) {
        check(SwingUtilities.isEventDispatchThread()) { "UI defaults writes must run on EDT" }
        AccentApplyScope.active?.takeUnless { it.isCommitted }?.checkWritable()
        check(isCurrentLookAndFeel()) { "Look and feel changed during accent application" }
        val change = changes[key]
        if (change == Change.Relinquished) return
        val current = readRawUiDefault(defaults, key)
        if (change is Change.Written && current !== change.expected) {
            changes[key] = Change.Relinquished
            return
        }
        val previous = if (change is Change.Written) change.previous else current
        // A property listener can throw after the table has already changed.
        changes[key] = Change.Written(previous, value)
        UIManager.put(key, value)
    }

    /** Attempts every surviving write; failures remain retryable on this checkpoint. */
    override fun restore(blockedBy: List<AccentCheckpoint>) {
        check(SwingUtilities.isEventDispatchThread()) { "UI defaults restoration must run on EDT" }
        var failure: Throwable? = null
        for ((key, change) in changes.toList().asReversed()) {
            if (!isCurrentLookAndFeel()) break
            if (isBlocked(key, blockedBy)) {
                failure =
                    combineUiFailures(failure, IllegalStateException("UI restoration blocked by a newer write: $key"))
                continue
            }
            if (change !is Change.Written || readRawUiDefault(defaults, key) !== change.expected) {
                changes.remove(key)
                continue
            }
            val recovery = restoreWrite(key, change)
            if (recovery != null) failure = combineUiFailures(failure, recovery)
        }
        if (changes.isEmpty()) {
            restoreActions.asReversed().forEach { action -> action() }
            restoreActions.clear()
        }
        failure?.let { throw it }
    }

    private fun restoreWrite(
        key: String,
        change: Change.Written,
    ): Throwable? {
        val recovery =
            runCatching {
                changes[key] = Change.Written(change.previous, change.previous)
                UIManager.put(key, change.previous)
                changes.remove(key)
            }.exceptionOrNull()
        if (recovery is VirtualMachineError) throw recovery
        // A listener can throw after the table already holds the previous value.
        if (recovery != null && readRawUiDefault(defaults, key) === change.previous) changes.remove(key)
        return recovery
    }

    /** Registers state that returns to its pre-apply value once every recorded write is restored or relinquished. */
    fun onRestore(action: () -> Unit) {
        restoreActions += action
    }

    /** Releases recovery records once the visual apply has completed. */
    override fun commit() {
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
