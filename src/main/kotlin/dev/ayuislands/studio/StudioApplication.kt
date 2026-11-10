package dev.ayuislands.studio

import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.StudioApplyUpdate
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.persistence.RecoveryPurpose
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.session.StudioSessionDispatchers
import dev.ayuislands.studio.session.StudioSessions
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Availability of the application owner, distinct from any individual Settings session. */
internal sealed interface StudioApplicationState {
    data object Loading : StudioApplicationState

    data class Ready(
        val sessions: StudioSessions,
    ) : StudioApplicationState

    data class Unavailable(
        val reason: StudioBootstrapResult,
    ) : StudioApplicationState

    data class InitializationBlocked(
        val result: AcceptanceResult,
    ) : StudioApplicationState

    data object Closed : StudioApplicationState
}

/** Opens one authority before producers and retains its coordinator across startup recovery. */
internal class StudioApplication(
    private val store: StudioAppearanceStore,
    private val bootstrap: StudioBootstrap,
    private val transactionMutex: Mutex,
    private val createSessions: (StudioDocument) -> StudioSessions,
    private val startRuntime: suspend (StudioSessions) -> AcceptanceResult,
    private val recoverMetadata: suspend (StudioSessions) -> Unit,
    private val dispatchers: StudioSessionDispatchers = StudioSessionDispatchers(),
) {
    private val lifecycle = Mutex()
    private var retained: StudioSessions? = null

    @Volatile
    var state: StudioApplicationState = StudioApplicationState.Loading
        private set

    /** Ordinary producers cannot obtain a binding while loading or resolving an uncertain decision. */
    val sessions: StudioSessions? get() = (state as? StudioApplicationState.Ready)?.sessions

    suspend fun start(): StudioApplicationState = open(isRetry = false)

    suspend fun retry(): StudioApplicationState = open(isRetry = true)

    private suspend fun open(isRetry: Boolean): StudioApplicationState =
        lifecycle.withLock {
            val previous = state
            if (previous == StudioApplicationState.Closed || previous is StudioApplicationState.Ready) {
                return@withLock previous
            }
            if (!isRetry &&
                (
                    previous is StudioApplicationState.Unavailable ||
                        previous is StudioApplicationState.InitializationBlocked
                )
            ) {
                return@withLock previous
            }
            publish(StudioApplicationState.Loading)
            val loaded = bootstrap.load()
            val isRuntimeRecovery =
                loaded is StudioBootstrapResult.Recovering &&
                    loaded.state.document != null &&
                    loaded.state.journal.recovery.purpose is RecoveryPurpose.Automatic
            val settled = if (loaded is StudioBootstrapResult.Recovering) recover(loaded) else loaded
            val next =
                when (settled) {
                    is StudioBootstrapResult.Ready -> {
                        initializeOwner(retainOwner(settled.document), isRuntimeRecovery)
                    }
                    else -> StudioApplicationState.Unavailable(settled)
                }
            publish(next)
            next
        }

    private suspend fun initializeOwner(
        sessionOwner: StudioSessions,
        isRuntimeRecovery: Boolean,
    ): StudioApplicationState {
        val initialization =
            if (isRuntimeRecovery) {
                AcceptanceResult.NoChanges
            } else {
                withContext(dispatchers.native) { startRuntime(sessionOwner) }
            }
        return if (initialization !is AcceptanceResult.Accepted && initialization != AcceptanceResult.NoChanges) {
            StudioApplicationState.InitializationBlocked(initialization)
        } else {
            confirmReady(sessionOwner)
        }
    }

    private suspend fun confirmReady(sessionOwner: StudioSessions): StudioApplicationState {
        val confirmed = bootstrap.load()
        return if (confirmed is StudioBootstrapResult.Ready) {
            retainOwner(confirmed.document)
            StudioApplicationState.Ready(sessionOwner)
        } else {
            StudioApplicationState.Unavailable(confirmed)
        }
    }

    private suspend fun recover(loaded: StudioBootstrapResult.Recovering): StudioBootstrapResult {
        // Initial migration has no accepted owner; Bootstrap alone may resolve its decision.
        val accepted = loaded.state.document ?: return loaded
        val sessionOwner = retainOwner(accepted)
        when (loaded.state.journal.recovery.purpose) {
            RecoveryPurpose.Metadata -> recoverMetadata(sessionOwner)
            is RecoveryPurpose.Automatic -> withContext(dispatchers.native) { startRuntime(sessionOwner) }
            RecoveryPurpose.UserAcceptance -> {
                sessionOwner.coordinator.retryRecovery(loaded.state.journal.token.transactionId)
            }
        }
        return bootstrap.load()
    }

    private suspend fun retainOwner(document: StudioDocument): StudioSessions =
        withContext(dispatchers.native) {
            val existing = retained
            if (existing == null) {
                createSessions(document).also { retained = it }
            } else {
                if (existing.document != document) {
                    existing.publish(StudioApplyUpdate.Committed(document.header.transactionId, document))
                }
                existing
            }
        }

    /** The caller first cancels and joins application producers; a held writer must settle before close. */
    suspend fun close() {
        withContext(NonCancellable) {
            lifecycle.withLock {
                if (state == StudioApplicationState.Closed) return@withLock
                publish(StudioApplicationState.Closed)
                transactionMutex.withLock {
                    withContext(dispatchers.io) { store.close() }
                }
            }
        }
    }

    private suspend fun publish(next: StudioApplicationState) {
        withContext(dispatchers.native) { state = next }
    }
}
