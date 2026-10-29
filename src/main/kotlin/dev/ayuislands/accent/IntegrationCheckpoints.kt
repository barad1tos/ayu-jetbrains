package dev.ayuislands.accent

import dev.ayuislands.integration.IntegrationOutcome
import dev.ayuislands.integration.IntegrationOwnership
import dev.ayuislands.integration.SettingsCheckpoint
import dev.ayuislands.integration.captureFailure
import javax.swing.SwingUtilities

/** Shares only receipt lifetime and native mutation observation between the two adapters. */
internal abstract class IntegrationCheckpoint : AccentCheckpoint {
    private var done = false

    protected abstract val currentState: Boolean
    protected abstract val currentTarget: Boolean
    protected abstract val metadata: SettingsCheckpoint
    protected abstract val foreignNative: Boolean

    /** Whole-integration restart recovery cannot claim fields changed by another writer. */
    protected val recoveryOwnership: IntegrationOwnership
        get() = if (foreignNative) IntegrationOwnership.SUSPENDED else IntegrationOwnership.RECOVERY_PENDING

    override val hasPending: Boolean
        get() = !done

    fun captureMetadata() = metadata.capture()

    protected abstract fun restoreNative()

    protected fun mutate(
        write: () -> Unit,
        observe: () -> Unit,
    ) {
        val failure = captureFailure(write)
        val observation = captureFailure(observe)
        val combined = observation?.let { combineUiFailures(failure, it) } ?: failure
        combined?.let { throw it }
    }

    final override fun restore(blockedBy: List<AccentCheckpoint>) {
        if (done) return
        if (currentState) restoreCurrent()
        commit()
    }

    private fun restoreCurrent() {
        if (!currentTarget) {
            metadata.suspendIfOwned()
            return
        }
        restoreNative()
        if (!currentState) return
        if (currentTarget) metadata.restore(foreignNative) else metadata.suspendIfOwned()
    }

    final override fun commit() {
        done = true
    }
}

/** One bounded pending receipt, retried only by its adapter's existing entry points. */
internal class IntegrationReceipts<R : IntegrationCheckpoint> {
    private var boundScope: AccentApplyScope? = null
    private var bound: R? = null
    private var pending: R? = null

    val current: R?
        get() = if (currentScope() === boundScope) bound else null

    fun run(
        create: () -> R,
        onFailure: (Throwable) -> IntegrationOutcome.Failed,
        action: () -> IntegrationOutcome,
    ): IntegrationOutcome {
        val scope = currentScope()
        pending?.let { receipt ->
            val failure =
                captureFailure {
                    if (scope == null) receipt.restore() else scope.recover { receipt.restore() }
                }
            if (failure != null) return onFailure(failure)
            if (!receipt.hasPending) pending = null
        }
        if (scope == null) return action()
        scope.checkWritable()
        if (boundScope !== scope) {
            boundScope = scope
            bound = create().also(scope::record)
            scope.onClose {
                bound = null
                boundScope = null
            }
        }
        return try {
            action()
        } finally {
            bound?.captureMetadata()
        }
    }

    fun retain(receipt: R) {
        pending = receipt
    }
}

private fun currentScope(): AccentApplyScope? =
    if (SwingUtilities.isEventDispatchThread()) AccentApplyScope.active?.takeUnless { it.isCommitted } else null
