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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Journals native effects and settles the retained owners on the serialized Studio queue. */
internal class StudioOwnerJournal(
    private val store: StudioAppearanceStore,
    private val ioDispatcher: CoroutineDispatcher,
) {
    /** Null from capture means no native change. */
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

    /** The caller serializes commit, rollback, and cleanup for this transaction. */
    suspend fun rollback(work: StudioOwnerWork): OwnerRollback {
        val decision = observeDecision(work, CommitResolution.NotCommitted)
        if (decision != CommitResolution.NotCommitted) return OwnerRollback.Decision(decision)
        return restoreOwners(work)
    }

    /** Release owner checkpoints only after the same transaction is durably committed. */
    suspend fun accept(work: StudioOwnerWork): OwnerAcceptance {
        val decision = observeDecision(work, CommitResolution.Committed)
        if (decision != CommitResolution.Committed) return OwnerAcceptance.Decision(decision)
        for (owner in work.journal.recovery.owners) {
            if (work.hasAccepted(owner) || work.journal.receipts.none { it.owner == owner }) continue
            currentCoroutineContext().ensureActive()
            try {
                val operation = work.handle(owner) ?: return OwnerAcceptance.HandleUnavailable(owner)
                operation.accept()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: RuntimeException) {
                currentCoroutineContext().ensureActive()
                return OwnerAcceptance.OwnerFailed(owner, failure)
            }
            work.markAccepted(owner)
        }
        return OwnerAcceptance.Completed
    }

    private suspend fun observeDecision(
        work: StudioOwnerWork,
        expected: CommitResolution,
    ): CommitResolution {
        val token = work.journal.token
        val (journal, decision) =
            withContext(ioDispatcher) {
                val observed = store.load() as? StoreLoad.Recovering ?: return@withContext null
                if (observed.journal.token != token) return@withContext null
                observed.journal to store.resolveOutcome(token)
            } ?: return CommitResolution.Unknown
        if (decision == expected) work.acknowledge(journal)
        return decision
    }

    private suspend fun restoreOwners(work: StudioOwnerWork): OwnerRollback {
        var blocked = StudioList<PortableReceipt>(emptyList())
        var firstFailure: OwnerRollback.OwnerFailed? = null
        var needsRetry = false
        val owners =
            work.journal.recovery.owners
                .asReversed()
        for (owner in owners) {
            if (work.journal.receipts.any { it.owner == owner }) {
                when (val result = restoreRetained(work, owner, blocked)) {
                    is OwnerRollback.ReceiptFailed -> return result
                    is OwnerRollback.OwnerFailed -> if (firstFailure == null) firstFailure = result
                    OwnerRollback.Pending -> needsRetry = true
                    else -> Unit
                }
            }
            val outstanding = work.journal.receipts.filter { it.owner == owner && !StudioReceiptPolicy.isTerminal(it) }
            if (outstanding.isNotEmpty()) blocked = StudioList(blocked + outstanding)
        }
        return firstFailure ?: if (needsRetry || blocked.isNotEmpty()) OwnerRollback.Pending else OwnerRollback.Settled
    }

    private suspend fun restoreRetained(
        work: StudioOwnerWork,
        owner: EffectOwner,
        blocked: StudioList<PortableReceipt>,
    ): OwnerRollback? {
        val operation =
            try {
                work.handle(owner)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: RuntimeException) {
                currentCoroutineContext().ensureActive()
                return OwnerRollback.OwnerFailed(owner, failure)
            } ?: return null
        return restoreOwner(work, owner, operation, blocked)
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
    ): OwnerRollback? =
        try {
            restoreSteps(work, owner, operation, blocked)
        } catch (cancelled: CancellationException) {
            try {
                val rejected = withContext(NonCancellable) { persistRestore(work, owner, operation) }
                if (rejected != null) {
                    cancelled.addSuppressed(
                        IllegalStateException("Could not save rollback evidence for $owner: ${rejected.issue}"),
                    )
                }
            } catch (failure: RuntimeException) {
                if (failure !== cancelled) cancelled.addSuppressed(failure)
            }
            throw cancelled
        }

    private suspend fun restoreSteps(
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
