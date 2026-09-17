package dev.ayuislands.accent

import java.util.function.BiConsumer
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * An EDT-local checkpoint for explicitly recorded developer-default writes.
 * Restores raw overrides, including absence and lazy values, only while their
 * identity and look and feel still belong to this operation.
 */
internal class UiDefaultsCheckpoint {
    private val defaults = UIManager.getDefaults()
    private val lookAndFeel = UIManager.getLookAndFeel()
    private val lookAndFeelDefaults = UIManager.getLookAndFeelDefaults()
    private val changes = linkedMapOf<String, Change>()

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
        check(isCurrentLookAndFeel()) { "Look and feel changed during accent application" }
        val change = changes[key]
        if (change == Change.Relinquished) return
        val current = rawValue(key)
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
    fun restore() {
        check(SwingUtilities.isEventDispatchThread()) { "UI defaults restoration must run on EDT" }
        var failure: Throwable? = null
        for ((key, change) in changes.toList().asReversed()) {
            if (!isCurrentLookAndFeel()) break
            if (change !is Change.Written || rawValue(key) !== change.expected) {
                changes.remove(key)
                continue
            }
            val recovery =
                runCatching {
                    changes[key] = Change.Written(change.previous, change.previous)
                    UIManager.put(key, change.previous)
                    changes.remove(key)
                }.exceptionOrNull()
            if (recovery is VirtualMachineError) throw recovery
            if (recovery != null) {
                failure = combineFailures(failure, recovery)
            }
        }
        failure?.let { throw it }
    }

    /** Releases recovery records once the visual apply has completed. */
    fun commit() {
        changes.clear()
    }

    /** Preserves cancellation and both failure causes when cleanup itself fails. */
    fun restoreAfter(failure: RuntimeException): Nothing {
        val recovery = runCatching { restore() }.exceptionOrNull()
        throw when (recovery) {
            null -> failure
            is VirtualMachineError -> recovery
            else -> combineFailures(failure, recovery)
        }
    }

    private fun isCurrentLookAndFeel(): Boolean =
        UIManager.getDefaults() === defaults &&
            UIManager.getLookAndFeel() === lookAndFeel &&
            UIManager.getLookAndFeelDefaults() === lookAndFeelDefaults

    private fun rawValue(key: String): Any? {
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

    private fun combineFailures(
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
}
