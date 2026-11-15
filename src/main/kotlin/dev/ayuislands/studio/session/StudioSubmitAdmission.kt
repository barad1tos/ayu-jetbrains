package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.AcceptanceBlock
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.ApplyRequest
import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** Keeps the accepted document stable through the caller's admitted transaction on the shared queue. */
internal class StudioSubmitAdmission(
    private val store: StudioAppearanceStore,
    private val queue: Mutex,
    private val flush: suspend (StudioSessionRecord) -> DraftWriteResult,
    private val observe: suspend (ApplyRequest, StudioDocument) -> ApplyInputs,
) {
    suspend fun <T> submit(
        request: ApplyRequest,
        operation: UUID,
        consume: suspend (AcceptancePlan.Commit) -> T,
    ): Submission<T> {
        val pending = queue.withLock { blocked(load()) }
        if (pending != null) return pending

        // The autosave writer may need the same queue to flush this exact record.
        val flushed = flush(request.session)

        return queue.withLock {
            when (val loaded = load()) {
                is StoreLoad.Ready ->
                    when (flushed) {
                        is DraftWriteResult.Failed -> Submission.FlushFailed(flushed.code)
                        is DraftWriteResult.Saved -> admit(request, operation, loaded.document, consume)
                    }
                else -> checkNotNull(blocked(loaded))
            }
        }
    }

    private suspend fun <T> admit(
        request: ApplyRequest,
        operation: UUID,
        latest: StudioDocument,
        consume: suspend (AcceptancePlan.Commit) -> T,
    ): Submission<T> {
        val saved =
            StudioJournalPolicy.acknowledgedDraft(latest, request.session)
                ?: return Submission.Blocked(AcceptanceBlock.STALE_SESSION)
        val confirmed = request.copy(session = saved)
        val inputs = observe(confirmed, latest)
        return when (
            val plan =
                StudioAcceptancePlanner.plan(
                    confirmed,
                    latest,
                    inputs.context,
                    inputs.effects,
                    operation,
                )
        ) {
            AcceptancePlan.NoChanges -> Submission.NoChanges
            is AcceptancePlan.Blocked -> Submission.Blocked(plan.reason)
            is AcceptancePlan.Review -> Submission.Review(plan.items)
            is AcceptancePlan.Commit -> {
                currentCoroutineContext().ensureActive()
                Submission.Admitted(consume(plan))
            }
        }
    }

    private suspend fun load(): StoreLoad = withContext(Dispatchers.IO) { store.load() }

    private fun blocked(loaded: StoreLoad): Submission<Nothing>? =
        when (loaded) {
            is StoreLoad.Ready -> null
            is StoreLoad.Recovering -> Submission.Recovery(loaded.journal.token.transactionId)
            is StoreLoad.ReadOnly -> Submission.StoreUnavailable(loaded.issue)
            StoreLoad.Empty -> Submission.Blocked(AcceptanceBlock.READ_ONLY)
        }
}
