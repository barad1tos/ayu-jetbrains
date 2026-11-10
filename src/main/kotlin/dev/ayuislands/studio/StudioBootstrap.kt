package dev.ayuislands.studio

import dev.ayuislands.studio.migration.LegacySnapshot
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.ApplyFailure
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.CommitResolution
import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.PendingJournal
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.RecoveryPurpose
import dev.ayuislands.studio.persistence.StoreDataException
import dev.ayuislands.studio.persistence.StoreIssue
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.CoroutineContext

/** Only Ready permits the application owner to publish authority and admit normal producers. */
internal sealed interface StudioBootstrapResult {
    data class Ready(
        val document: StudioDocument,
    ) : StudioBootstrapResult

    data class Recovering(
        val state: StoreLoad.Recovering,
    ) : StudioBootstrapResult

    data class ReadOnly(
        val state: StoreLoad.ReadOnly,
    ) : StudioBootstrapResult

    /** The candidate remains unaccepted; it is retained only for inspection and explicit Retry. */
    data class LegacyReadOnlyRecovery(
        val document: StudioDocument,
        val failure: ApplyFailure,
    ) : StudioBootstrapResult
}

/** Imports once through the existing atomic store; native recovery and publication stay with the application. */
internal class StudioBootstrap(
    private val store: StudioAppearanceStore,
    private val capture: suspend () -> LegacySnapshot,
    private val transactionMutex: Mutex,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) {
    suspend fun load(): StudioBootstrapResult =
        transactionMutex.withLock {
            withContext(ioContext) {
                when (val loaded = store.load()) {
                    StoreLoad.Empty -> migrate()
                    is StoreLoad.Ready -> StudioBootstrapResult.Ready(loaded.document)
                    is StoreLoad.Recovering -> recoverInitial(loaded)
                    is StoreLoad.ReadOnly -> StudioBootstrapResult.ReadOnly(loaded)
                }
            }
        }

    private suspend fun migrate(): StudioBootstrapResult {
        val captured =
            try {
                capture()
            } catch (failure: IOException) {
                val issue = (failure as? StoreDataException)?.issue ?: StoreIssue.IO
                return readOnly(issue)
            }
        val archived = store.preserveSource(captured.source)
        if (archived is JournalResult.Rejected) return readOnly(archived.issue)
        return when (val planned = StudioLegacyMigration.plan(captured)) {
            is MigrationResult.Conflict -> readOnly(StoreIssue.INVALID_CANDIDATE)
            is MigrationResult.Ready -> importCandidate(planned.document)
        }
    }

    private fun importCandidate(candidate: StudioDocument): StudioBootstrapResult {
        val plan = RecoveryPlan(StudioTarget.Global, StudioList(emptyList()))
        return when (val prepared = store.prepare(-1, candidate, plan)) {
            is PrepareResult.Rejected -> failedPreparation(candidate, prepared.issue)
            is PrepareResult.Prepared -> {
                val acknowledged =
                    StoreLoad.Recovering(
                        null,
                        PendingJournal(prepared.token, null, candidate, plan, StudioList(emptyList())),
                    )
                when (val committed = store.commit(prepared.token, candidate.runtime)) {
                    is CommitResult.Committed -> finishAccepted(acknowledged.copy(document = committed.document))
                    is CommitResult.NotCommitted -> {
                        finishRollback(acknowledged, ApplyFailure("migration-${committed.issue.name.lowercase()}"))
                    }

                    is CommitResult.OutcomeUnknown -> StudioBootstrapResult.Recovering(acknowledged)
                }
            }
        }
    }

    private fun failedPreparation(
        candidate: StudioDocument,
        issue: StoreIssue,
    ): StudioBootstrapResult =
        when (val loaded = store.load()) {
            StoreLoad.Empty -> {
                StudioBootstrapResult.LegacyReadOnlyRecovery(
                    candidate,
                    ApplyFailure("migration-${issue.name.lowercase()}"),
                )
            }

            is StoreLoad.Recovering -> recoverInitial(loaded)
            is StoreLoad.ReadOnly -> StudioBootstrapResult.ReadOnly(loaded)
            is StoreLoad.Ready -> readOnly(StoreIssue.CONFLICT, loaded.document)
        }

    private fun recoverInitial(state: StoreLoad.Recovering): StudioBootstrapResult {
        if (!isInitialImport(state.journal)) return StudioBootstrapResult.Recovering(state)
        return when (store.resolveOutcome(state.journal.token)) {
            CommitResolution.Committed -> finishAccepted(state.copy(document = state.journal.candidate))
            CommitResolution.NotCommitted -> finishRollback(state, ApplyFailure("migration-not-committed"))
            CommitResolution.Unknown -> StudioBootstrapResult.Recovering(state)
        }
    }

    private fun finishAccepted(state: StoreLoad.Recovering): StudioBootstrapResult {
        if (store.finishAccepted(state.journal.token) != JournalResult.Acknowledged) {
            return StudioBootstrapResult.Recovering(state)
        }
        return when (val loaded = store.load()) {
            is StoreLoad.Ready -> {
                if (loaded.document == state.journal.candidate) {
                    StudioBootstrapResult.Ready(loaded.document)
                } else {
                    readOnly(StoreIssue.CONFLICT, loaded.document)
                }
            }

            is StoreLoad.Recovering -> StudioBootstrapResult.Recovering(loaded)
            is StoreLoad.ReadOnly -> StudioBootstrapResult.ReadOnly(loaded)
            StoreLoad.Empty -> readOnly(StoreIssue.INVALID_DATA)
        }
    }

    private fun finishRollback(
        state: StoreLoad.Recovering,
        failure: ApplyFailure,
    ): StudioBootstrapResult =
        if (store.finishRollback(state.journal.token) == JournalResult.Acknowledged) {
            StudioBootstrapResult.LegacyReadOnlyRecovery(state.journal.candidate, failure)
        } else {
            StudioBootstrapResult.Recovering(state)
        }

    private fun isInitialImport(journal: PendingJournal): Boolean {
        if (journal.base != null || journal.token.baseRevision != -1L) return false
        if (journal.recovery.owners.isNotEmpty() || journal.receipts.isNotEmpty()) return false
        return journal.recovery.purpose == RecoveryPurpose.UserAcceptance
    }

    private fun readOnly(
        issue: StoreIssue,
        document: StudioDocument? = null,
    ): StudioBootstrapResult.ReadOnly = StudioBootstrapResult.ReadOnly(StoreLoad.ReadOnly(issue, document))
}
