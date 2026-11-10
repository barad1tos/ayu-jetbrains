package dev.ayuislands.accent

import dev.ayuislands.integration.IntegrationOwnership
import dev.ayuislands.integration.SettingsCheckpoint
import dev.ayuislands.integration.captureFailure

internal enum class IntegrationAction {
    KEEP,
    APPLY,
    RESTORE,
    SUSPEND,
}

/** Shares only receipt lifetime and native mutation observation between the two adapters. */
internal abstract class IntegrationCheckpoint : AccentCheckpoint {
    private var done = false
    private var isRetained = false

    protected abstract val currentState: Boolean
    protected abstract val currentTarget: Boolean
    protected abstract val metadata: SettingsCheckpoint
    protected abstract val foreignNative: Boolean
    protected open val hasBlocked: Boolean = false

    val isAvailable: Boolean
        get() = currentState && currentTarget

    /** Whole-integration restart recovery cannot claim fields changed by another writer. */
    protected val recoveryOwnership: IntegrationOwnership
        get() =
            if (foreignNative) IntegrationOwnership.SUSPENDED else IntegrationOwnership.RECOVERY_PENDING

    override val hasPending: Boolean
        get() = !done

    fun captureMetadata() = metadata.capture()

    internal fun retainUntilAccepted() {
        isRetained = true
    }

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
        if (isRetained && !isAvailable) return
        if (currentState) restoreCurrent()
        if (!hasBlocked) commit()
    }

    private fun restoreCurrent() {
        if (!currentTarget) {
            metadata.suspendIfOwned()
            return
        }
        restoreNative()
        if (!currentState || hasBlocked) return
        if (currentTarget) metadata.restore(foreignNative) else metadata.suspendIfOwned()
    }

    final override fun commit() {
        done = true
    }
}

/** The caller owns every native receipt; no global pending slot or child apply scope is enrolled. */
internal object IntegrationReceipts {
    fun <R : IntegrationCheckpoint> prepare(create: () -> R): R =
        create().also(IntegrationCheckpoint::retainUntilAccepted)
}
