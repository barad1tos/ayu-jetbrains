package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.CommitResolution
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.persistence.StoreIssue
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioReceiptPolicy
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

    /** Restore only after observing a durable NotCommitted decision; the caller still owns cleanup. */
    suspend fun rollback(work: StudioOwnerWork): OwnerRollback {
        val observed = withContext(ioDispatcher) { store.load() }
        val token = work.journal.token
        if (observed !is StoreLoad.Recovering || observed.journal.token != token) {
            return OwnerRollback.Decision(CommitResolution.Unknown)
        }
        val decision = withContext(ioDispatcher) { store.resolveOutcome(token) }
        if (decision != CommitResolution.NotCommitted) return OwnerRollback.Decision(decision)
        work.acknowledge(observed.journal)
        return restoreOwners(work)
    }

    private suspend fun restoreOwners(work: StudioOwnerWork): OwnerRollback {
        val operations = work.operations
        val blocked = mutableListOf<PortableReceipt>()
        var firstFailure: OwnerRollback.OwnerFailed? = null
        var needsRetry = false
        val owners =
            work.journal.recovery.owners
                .asReversed()
        for (owner in owners) {
            val operation = operations[owner]
            if (operation != null && work.journal.receipts.any { it.owner == owner }) {
                when (val result = restoreOwner(work, owner, operation, StudioList(blocked))) {
                    is OwnerRollback.ReceiptFailed -> return result
                    is OwnerRollback.OwnerFailed -> if (firstFailure == null) firstFailure = result
                    OwnerRollback.Pending -> needsRetry = true
                    else -> Unit
                }
            }
            blocked.addAll(work.journal.receipts.filter { it.owner == owner && !StudioReceiptPolicy.isTerminal(it) })
        }
        return firstFailure ?: if (needsRetry || blocked.isNotEmpty()) OwnerRollback.Pending else OwnerRollback.Settled
    }

    private suspend fun applyOwner(
        work: StudioOwnerWork,
        owner: EffectOwner,
        qualifies: suspend () -> Boolean,
        capture: suspend (EffectOwner) -> StudioOwnerOperation?,
    ): OwnerApply? {
        if (!isQualified(qualifies)) return OwnerApply.LeaseLost
        val operation = capture(owner) ?: return null
        val prepared = StudioList(operation.receipts)
        require(prepared.isNotEmpty() && prepared.all { it.owner == owner && it.status == RestoreStatus.PENDING })
        work.retain(owner, operation)
        val previous = work.journal.receipts
        persist(work, previous + prepared)?.let { return OwnerApply.ReceiptFailed(owner, it) }
        val applied =
            try {
                operation.apply { dispatcher, write ->
                    withContext(dispatcher) {
                        if (!isQualified(qualifies)) return@withContext false
                        write()
                        true
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: RuntimeException) {
                currentCoroutineContext().ensureActive()
                return OwnerApply.OwnerFailed(owner, failure)
            }
        if (!applied) return OwnerApply.LeaseLost
        val observed = StudioList(operation.receipts)
        if (observed.size != prepared.size) return OwnerApply.ReceiptFailed(owner, StoreIssue.CONFLICT)
        require(observed.all { it.owner == owner })
        return persist(work, previous + observed)?.let { OwnerApply.ReceiptFailed(owner, it) }
    }

    private suspend fun restoreOwner(
        work: StudioOwnerWork,
        owner: EffectOwner,
        operation: StudioOwnerOperation,
        blocked: List<PortableReceipt>,
    ): OwnerRollback? {
        repeat(MAX_RESTORE_STEPS) {
            try {
                operation.prepareRestore(blocked)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: RuntimeException) {
                currentCoroutineContext().ensureActive()
                return persistRestore(work, owner, operation) ?: OwnerRollback.OwnerFailed(owner, failure)
            }
            persistRestore(work, owner, operation)?.let { return it }
            val continuation =
                try {
                    operation.restore(blocked)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: RuntimeException) {
                    currentCoroutineContext().ensureActive()
                    return persistRestore(work, owner, operation) ?: OwnerRollback.OwnerFailed(owner, failure)
                }
            persistRestore(work, owner, operation)?.let { return it }
            if (continuation == RestoreContinuation.COMPLETE) return null
        }
        return OwnerRollback.Pending
    }

    private suspend fun persistRestore(
        work: StudioOwnerWork,
        owner: EffectOwner,
        operation: StudioOwnerOperation,
    ): OwnerRollback.ReceiptFailed? {
        val receipts = StudioList(operation.receipts)
        require(receipts.all { it.owner == owner })
        val updated = receipts.associateBy { it.id }
        if (updated.size != receipts.size) return OwnerRollback.ReceiptFailed(owner, StoreIssue.CONFLICT)
        val previous = work.journal.receipts
        val known = previous.filter { it.owner == owner }.map { it.id }.toSet()
        val merged = previous.map { if (it.owner == owner) updated[it.id] ?: it else it }
        return persist(work, merged + updated.values.filterNot { it.id in known })?.let {
            OwnerRollback.ReceiptFailed(owner, it)
        }
    }

    private suspend fun persist(
        work: StudioOwnerWork,
        receipts: List<PortableReceipt>,
    ): StoreIssue? {
        if (receipts.distinctBy { it.id }.size != receipts.size) return StoreIssue.CONFLICT
        val journal = work.journal.copy(receipts = StudioList(receipts))
        if (journal == work.journal) return null
        return when (val result = withContext(ioDispatcher) { store.recordRecovery(journal.token, journal.receipts) }) {
            is JournalResult.Rejected -> result.issue
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

private const val MAX_RESTORE_STEPS = 2
