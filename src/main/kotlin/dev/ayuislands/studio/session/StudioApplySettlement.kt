package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.persistence.CommitResolution
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.StoreIssue
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Retained by the caller until one journal settles on the serialized Studio queue. */
internal class StudioApplySettlement(
    private val store: StudioAppearanceStore,
    private val owners: StudioOwnerJournal,
    private val ioDispatcher: CoroutineDispatcher,
    private val work: StudioOwnerWork,
    private val release: suspend (StudioDocument?) -> Unit,
) {
    private var stage: Stage = Stage.Inspecting

    fun interrupted(problem: SettlementProblem): ApplySettlement =
        when (val current = stage) {
            Stage.Inspecting -> ApplySettlement.RecoveryRequired(problem)
            is Stage.Owners -> current.outcome.pending(problem)
            is Stage.Release -> current.outcome.pending(problem)
            is Stage.Cleanup -> current.outcome.pending(problem)
            is Stage.Done -> current.outcome
        }

    /** A failed release may be retried, so the adapter must tolerate an interrupted release. */
    suspend fun settle(): ApplySettlement {
        while (true) {
            when (val current = stage) {
                Stage.Inspecting -> {
                    val outcome = inspect() ?: return ApplySettlement.RecoveryRequired(SettlementProblem.Unconfirmed)
                    stage = Stage.Owners(outcome)
                }
                is Stage.Owners -> {
                    settleOwners(current.outcome)?.let { return current.outcome.pending(it) }
                    stage = Stage.Release(current.outcome)
                }
                is Stage.Release -> {
                    releaseScope(current.outcome)?.let { return current.outcome.pending(it) }
                    stage = Stage.Cleanup(current.outcome)
                }
                is Stage.Cleanup -> {
                    cleanup(current.outcome)?.let { return current.outcome.pending(it) }
                    stage = Stage.Done(current.outcome)
                }
                is Stage.Done -> return current.outcome
            }
        }
    }

    private suspend fun inspect(): ApplySettlement.Finished? =
        withContext(ioDispatcher) {
            val observed = store.load() as? StoreLoad.Recovering ?: return@withContext null
            val token = work.journal.token
            if (observed.journal.token != token) return@withContext null
            when (store.resolveOutcome(token)) {
                CommitResolution.Committed -> observed.document?.let(ApplySettlement::Accepted)
                CommitResolution.NotCommitted -> ApplySettlement.NotAccepted
                CommitResolution.Unknown -> null
            }
        }

    private suspend fun settleOwners(outcome: ApplySettlement.Finished): SettlementProblem? =
        when (outcome) {
            is ApplySettlement.Accepted ->
                when (val result = owners.accept(work)) {
                    OwnerAcceptance.Completed -> null
                    else -> SettlementProblem.AcceptOwners(result)
                }

            ApplySettlement.NotAccepted ->
                when (val result = owners.rollback(work)) {
                    OwnerRollback.Settled -> null
                    else -> SettlementProblem.RestoreOwners(result)
                }
        }

    private suspend fun releaseScope(outcome: ApplySettlement.Finished): SettlementProblem.Release? =
        try {
            currentCoroutineContext().ensureActive()
            release((outcome as? ApplySettlement.Accepted)?.document)
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            currentCoroutineContext().ensureActive()
            SettlementProblem.Release(failure)
        }

    private suspend fun cleanup(outcome: ApplySettlement.Finished): SettlementProblem.Journal? =
        withContext(ioDispatcher) {
            val observation = store.load()
            if (observation is StoreLoad.ReadOnly) return@withContext SettlementProblem.Journal(observation.issue)
            val token = work.journal.token
            val result =
                when (outcome) {
                    is ApplySettlement.Accepted -> store.finishAccepted(token)
                    ApplySettlement.NotAccepted -> store.finishRollback(token)
                }
            (result as? JournalResult.Rejected)?.let { SettlementProblem.Journal(it.issue) }
        }

    private sealed interface Stage {
        data object Inspecting : Stage

        data class Owners(
            val outcome: ApplySettlement.Finished,
        ) : Stage

        data class Release(
            val outcome: ApplySettlement.Finished,
        ) : Stage

        data class Cleanup(
            val outcome: ApplySettlement.Finished,
        ) : Stage

        data class Done(
            val outcome: ApplySettlement.Finished,
        ) : Stage
    }
}

internal sealed interface ApplySettlement {
    sealed interface Finished : ApplySettlement

    data class Accepted(
        val document: StudioDocument,
    ) : Finished

    data object NotAccepted : Finished

    data class AcceptedCleanupPending(
        val document: StudioDocument,
        val problem: SettlementProblem,
    ) : ApplySettlement

    data class RecoveryRequired(
        val problem: SettlementProblem,
    ) : ApplySettlement
}

internal sealed interface SettlementProblem {
    data object Unconfirmed : SettlementProblem

    data object TimedOut : SettlementProblem

    data class AcceptOwners(
        val result: OwnerAcceptance,
    ) : SettlementProblem

    data class RestoreOwners(
        val result: OwnerRollback,
    ) : SettlementProblem

    data class Release(
        val cause: RuntimeException,
    ) : SettlementProblem

    data class Journal(
        val issue: StoreIssue,
    ) : SettlementProblem
}

private fun ApplySettlement.Finished.pending(problem: SettlementProblem): ApplySettlement =
    when (this) {
        is ApplySettlement.Accepted -> ApplySettlement.AcceptedCleanupPending(document, problem)
        ApplySettlement.NotAccepted -> ApplySettlement.RecoveryRequired(problem)
    }
