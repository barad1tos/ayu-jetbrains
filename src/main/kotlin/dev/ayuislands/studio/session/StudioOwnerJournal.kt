package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Runs forward owners under an acquired lease; null from capture means no native change. */
internal class StudioOwnerJournal(
    private val store: StudioAppearanceStore,
    private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun apply(
        work: StudioOwnerWork,
        qualifies: suspend () -> Boolean,
        capture: suspend (EffectOwner) -> StudioOwnerOperation?,
    ): OwnerApply {
        check(work.operations.isEmpty() && work.journal.receipts.isEmpty())
        for (owner in work.journal.recovery.owners) {
            val failure = applyOwner(work, owner, qualifies, capture)
            if (failure != null) return failure
        }
        return OwnerApply.Completed
    }

    private suspend fun applyOwner(
        work: StudioOwnerWork,
        owner: EffectOwner,
        qualifies: suspend () -> Boolean,
        capture: suspend (EffectOwner) -> StudioOwnerOperation?,
    ): OwnerApply? {
        if (!isQualified(qualifies)) return OwnerApply.LeaseLost
        val operation = capture(owner) ?: return null
        val prepared = operation.receipts.toList()
        require(prepared.isNotEmpty() && prepared.all { it.owner == owner })
        work.retain(owner, operation)
        val previous = work.journal.receipts
        persist(work, owner, previous, prepared)?.let { return it }
        if (!isQualified(qualifies)) return OwnerApply.LeaseLost
        try {
            operation.apply()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            currentCoroutineContext().ensureActive()
            return OwnerApply.OwnerFailed(owner, failure)
        }
        return persist(work, owner, previous, operation.receipts)
    }

    private suspend fun persist(
        work: StudioOwnerWork,
        owner: EffectOwner,
        previous: StudioList<PortableReceipt>,
        receipts: List<PortableReceipt>,
    ): OwnerApply.ReceiptFailed? {
        require(receipts.all { it.owner == owner })
        val journal = work.journal.copy(receipts = StudioList(previous + receipts))
        if (journal == work.journal) return null
        return when (val result = withContext(ioDispatcher) { store.recordRecovery(journal.token, journal.receipts) }) {
            is JournalResult.Rejected -> OwnerApply.ReceiptFailed(owner, result.issue)
            JournalResult.Acknowledged -> {
                work.acknowledge(journal)
                null
            }
        }
    }

    private suspend fun isQualified(qualifies: suspend () -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val valid = qualifies()
        currentCoroutineContext().ensureActive()
        return valid
    }
}
