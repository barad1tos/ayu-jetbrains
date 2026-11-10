package dev.ayuislands.studio.persistence

import dev.ayuislands.studio.fields.StudioMetadataPolicy
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.ApplyFailure
import dev.ayuislands.studio.model.StudioApplyUpdate
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioMetadataCommand
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.session.StudioSessionIo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Uses the application's existing transaction lane and accepted publication; owns no second document cache. */
internal class StudioMetadataStore(
    private val store: StudioAppearanceStore,
    private val session: StudioSessionIo,
    private val transactionMutex: Mutex,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) {
    suspend fun submit(
        command: StudioMetadataCommand,
        transactionId: String,
    ): AcceptanceResult =
        transactionMutex.withLock {
            withContext(ioContext) {
                prepare(command, transactionId)
            }
        }

    /** Settles only this writer's durable journal; no command, native observation, or new candidate is needed. */
    suspend fun recoverPending(): AcceptanceResult? =
        transactionMutex.withLock {
            withContext(ioContext) {
                when (val loaded = store.load()) {
                    is StoreLoad.Recovering -> {
                        currentCoroutineContext().ensureActive()
                        withContext(NonCancellable) { settleMetadata(loaded.journal) }
                    }
                    is StoreLoad.Ready -> null
                    is StoreLoad.ReadOnly -> rejected("store-${loaded.issue.name.lowercase()}")
                    StoreLoad.Empty -> rejected("store-not-initialized")
                }
            }
        }

    private suspend fun prepare(
        command: StudioMetadataCommand,
        transactionId: String,
    ): AcceptanceResult {
        var loaded = store.load()
        if (loaded is StoreLoad.Recovering) {
            val pending = loaded
            currentCoroutineContext().ensureActive()
            val unresolved = withContext(NonCancellable) { settleMetadata(pending.journal) }
            if (unresolved != null) return unresolved
            loaded = store.load()
        }
        val latest =
            when (loaded) {
                is StoreLoad.Ready -> loaded.document
                is StoreLoad.Recovering -> return recovery(loaded.journal.token.transactionId, "recovery-required")
                is StoreLoad.ReadOnly -> return rejected("store-${loaded.issue.name.lowercase()}")
                StoreLoad.Empty -> return rejected("store-not-initialized")
            }
        val planned =
            try {
                StudioMetadataPolicy.apply(latest, command)
            } catch (_: IllegalArgumentException) {
                return rejected("metadata-invalid")
            } ?: return rejected("metadata-conflict")
        if (planned == latest) return AcceptanceResult.NoChanges
        val candidate =
            planned.copy(
                header = planned.header.copy(revision = latest.header.revision + 1, transactionId = transactionId),
            )
        currentCoroutineContext().ensureActive()
        // Once journal preparation starts, cancellation cannot strand a partially acknowledged decision.
        return withContext(NonCancellable) {
            val target =
                (command as? StudioMetadataCommand.InitializeProject)?.let { StudioTarget.Project(it.key) }
                    ?: StudioTarget.Global
            val plan = RecoveryPlan(target, StudioList(emptyList()), purpose = RecoveryPurpose.Metadata)
            when (val prepared = store.prepare(latest.header.revision, candidate, plan)) {
                is PrepareResult.Rejected -> failedPreparation(transactionId, prepared.issue)
                is PrepareResult.Prepared -> commit(prepared.token, candidate)
            }
        }
    }

    /** A settled journal has no result to return: admission resumes from its freshly loaded accepted document. */
    private suspend fun settleMetadata(journal: PendingJournal): AcceptanceResult? {
        val token = journal.token
        if (journal.recovery.purpose != RecoveryPurpose.Metadata) {
            return recovery(token.transactionId, "recovery-required")
        }
        return when (store.resolveOutcome(token)) {
            CommitResolution.Unknown -> recovery(token.transactionId, "commit-unconfirmed")
            CommitResolution.Committed ->
                when (val result = publish(token, journal.candidate)) {
                    is AcceptanceResult.Accepted -> null
                    else -> result
                }
            CommitResolution.NotCommitted -> {
                if (store.finishRollback(token) == JournalResult.Acknowledged) {
                    null
                } else {
                    recovery(token.transactionId, "rollback-cleanup")
                }
            }
        }
    }

    private fun failedPreparation(
        transactionId: String,
        issue: StoreIssue,
    ): AcceptanceResult =
        when (val loaded = store.load()) {
            is StoreLoad.Ready -> rejected("prepare-${issue.name.lowercase()}")
            is StoreLoad.Recovering -> recovery(loaded.journal.token.transactionId, "prepare-recovery")
            is StoreLoad.ReadOnly, StoreLoad.Empty -> recovery(transactionId, "prepare-unconfirmed")
        }

    private suspend fun commit(
        token: PreparedToken,
        candidate: StudioDocument,
    ): AcceptanceResult =
        when (val result = store.commit(token, candidate.runtime)) {
            is CommitResult.Committed -> publish(token, result.document)
            is CommitResult.NotCommitted -> {
                val cleanup = store.finishRollback(token)
                if (cleanup == JournalResult.Acknowledged) {
                    rejected("commit-${result.issue.name.lowercase()}")
                } else {
                    recovery(token.transactionId, "rollback-cleanup")
                }
            }
            is CommitResult.OutcomeUnknown -> recovery(token.transactionId, "commit-unconfirmed")
        }

    private suspend fun publish(
        token: PreparedToken,
        document: StudioDocument,
    ): AcceptanceResult {
        try {
            session.publish(StudioApplyUpdate.Committed(token.transactionId, document))
        } catch (cancelled: CancellationException) {
            // The accepted journal remains for recovery if the publication receiver cancels independently.
            throw cancelled
        } catch (_: RuntimeException) {
            return acceptedPending(token, document, "metadata-publication")
        }
        return when (val cleanup = store.finishAccepted(token)) {
            JournalResult.Acknowledged -> AcceptanceResult.Accepted(document.header.revision, StudioList(emptyList()))
            is JournalResult.Rejected -> acceptedPending(token, document, "cleanup-${cleanup.issue.name.lowercase()}")
        }
    }

    private fun acceptedPending(
        token: PreparedToken,
        document: StudioDocument,
        code: String,
    ): AcceptanceResult =
        AcceptanceResult.AcceptedCleanupPending(document.header.revision, token.transactionId, ApplyFailure(code))

    private fun rejected(code: String): AcceptanceResult = AcceptanceResult.NotAccepted(ApplyFailure(code))

    private fun recovery(
        transactionId: String,
        code: String,
    ): AcceptanceResult = AcceptanceResult.RecoveryRequired(transactionId, ApplyFailure(code))
}
