package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.ApplyFailure
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RecoveryNotice
import dev.ayuislands.studio.model.SessionEffect
import dev.ayuislands.studio.model.SessionJournalEvent
import dev.ayuislands.studio.model.StudioApplyUpdate
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.CommitResolution
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.PendingJournal
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioReceiptPolicy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Live owner handles supplement the durable journal; they never replace its commit decision. */
internal class StudioApplyWork(
    var journal: PendingJournal,
    val lease: StudioRuntimeLease,
) {
    val operations = linkedMapOf<EffectOwner, StudioEffectOperation>()
    val acceptedOwners = mutableSetOf<EffectOwner>()
    var decision: CommitResolution = CommitResolution.Unknown
    var isPublished = false
    var restored = false
    var released = false
    var failure = ApplyFailure("interrupted")
}

internal class StudioApplyRecovery(
    private val store: StudioAppearanceStore,
    private val participants: List<StudioEffectParticipant>,
    private val environment: StudioApplyEnvironment,
    private val ioContext: CoroutineContext,
) {
    suspend fun settle(work: StudioApplyWork): AcceptanceResult {
        if (work.decision == CommitResolution.Unknown) {
            work.decision = withContext(ioContext) { store.resolveOutcome(work.journal.token) }
        }
        return when (work.decision) {
            CommitResolution.Unknown -> {
                environment.publish(
                    StudioApplyUpdate.Journal(
                        work.journal.token.transactionId,
                        "unknown",
                        SessionJournalEvent.OutcomeUnknown,
                    ),
                )
                recovery(work, "commit-unconfirmed")
            }

            CommitResolution.Committed -> {
                if (!work.isPublished) {
                    environment.publish(
                        StudioApplyUpdate.Committed(work.journal.token.transactionId, work.journal.candidate),
                    )
                    work.isPublished = true
                }
                finishAccepted(work)
            }

            CommitResolution.NotCommitted -> {
                environment.publish(
                    StudioApplyUpdate.Journal(
                        work.journal.token.transactionId,
                        "not-committed",
                        SessionJournalEvent.NotCommitted(work.failure.code),
                    ),
                )
                finishRollback(work)
            }
        }
    }

    suspend fun record(work: StudioApplyWork): JournalResult {
        val supplied =
            work.operations.flatMap { (owner, operation) ->
                operation.receipts.also { receipts -> require(receipts.all { it.owner == owner }) }
            }
        require(supplied.map { it.id }.distinct().size == supplied.size) { "Duplicate native receipt identity" }
        val updated = supplied.associateBy { it.id }
        val existingIds =
            work.journal.receipts
                .map { it.id }
                .toSet()
        val receipts = work.journal.receipts.map { updated[it.id] ?: it } + supplied.filter { it.id !in existingIds }
        val result = withContext(ioContext) { store.recordRecovery(work.journal.token, receipts) }
        if (result == JournalResult.Acknowledged) work.journal = work.journal.copy(receipts = StudioList(receipts))
        return result
    }

    private suspend fun reacquire(work: StudioApplyWork) {
        val input = StudioEffectInput(work.journal.candidate, work.journal.recovery.target, StudioList(emptyList()))
        for (owner in work.journal.recovery.owners) {
            val receipts = work.journal.receipts.filter { it.owner == owner }
            if (receipts.isEmpty() || owner in work.operations) continue
            val participant = participants.single { it.owner == owner }
            work.operations[owner] = participant.recover(input, StudioList(receipts), work.lease)
        }
    }

    private suspend fun finishAccepted(work: StudioApplyWork): AcceptanceResult =
        try {
            reacquire(work)
            for ((owner, operation) in work.operations) {
                if (owner !in work.acceptedOwners) {
                    operation.accept()
                    work.acceptedOwners.add(owner)
                }
            }
            if (!work.released) {
                work.lease.release(work.journal.candidate)
                work.released = true
            }
            val cleanup = withContext(ioContext) { store.finishAccepted(work.journal.token) }
            if (cleanup == JournalResult.Acknowledged) {
                environment.publish(
                    StudioApplyUpdate.Journal(
                        work.journal.token.transactionId,
                        "cleanup-saved",
                        SessionJournalEvent.CleanupSaved,
                    ),
                )
                environment.publish(
                    StudioApplyUpdate.Effect(
                        work.journal.token.transactionId,
                        "cleanup-settled",
                        SessionEffect.CleanupSettled,
                    ),
                )
                AcceptanceResult.Accepted(work.journal.token.targetRevision, work.journal.recovery.limitations)
            } else {
                cleanupPending(work, "journal-cleanup")
            }
        } catch (_: RuntimeException) {
            currentCoroutineContext().ensureActive()
            cleanupPending(work, "owner-cleanup")
        }

    private suspend fun finishRollback(work: StudioApplyWork): AcceptanceResult =
        try {
            reacquire(work)
            if (!work.restored && !restoreOwners(work)) {
                recovery(work, "restore-incomplete")
            } else {
                work.restored = true
                if (!work.released) {
                    work.lease.release(null)
                    work.released = true
                }
                val cleanup = withContext(ioContext) { store.finishRollback(work.journal.token) }
                if (cleanup == JournalResult.Acknowledged) {
                    environment.publish(
                        StudioApplyUpdate.Effect(
                            work.journal.token.transactionId,
                            "rollback-settled",
                            SessionEffect.RollbackSettled,
                        ),
                    )
                    AcceptanceResult.NotAccepted(
                        work.failure,
                        StudioList(
                            if (work.journal.receipts.any { it.status == RestoreStatus.KEPT_AFTER_RESTART }) {
                                listOf(RecoveryNotice.PANEL_GEOMETRY_KEPT)
                            } else {
                                emptyList()
                            },
                        ),
                    )
                } else {
                    recovery(work, "journal-cleanup")
                }
            }
        } catch (_: RuntimeException) {
            currentCoroutineContext().ensureActive()
            recovery(work, "restore-incomplete")
        }

    private suspend fun restoreOwners(work: StudioApplyWork): Boolean {
        var complete = true
        val blocked = mutableListOf<PortableReceipt>()
        for (operation in work.operations.values.reversed()) {
            try {
                if (!restoreOwner(work, operation, StudioList(blocked))) return false
            } catch (_: RuntimeException) {
                currentCoroutineContext().ensureActive()
                complete = false
                if (record(work) != JournalResult.Acknowledged) return false
            }
            blocked.addAll(
                operation.receipts.filter {
                    !StudioReceiptPolicy.isTerminal(it)
                },
            )
        }
        return complete &&
            work.journal.receipts.all {
                StudioReceiptPolicy.isTerminal(it)
            }
    }

    private suspend fun restoreOwner(
        work: StudioApplyWork,
        operation: StudioEffectOperation,
        blocked: StudioList<PortableReceipt>,
    ): Boolean {
        repeat(2) {
            operation.prepareRestore(blocked)
            if (record(work) != JournalResult.Acknowledged) return false
            val continuation = operation.restore(blocked)
            if (record(work) != JournalResult.Acknowledged) return false
            if (continuation == StudioRestoreContinuation.NONE) return true
        }
        error("A Studio owner requested more than one restoration continuation")
    }

    private suspend fun recovery(
        work: StudioApplyWork,
        code: String,
    ): AcceptanceResult.RecoveryRequired {
        environment.publish(
            StudioApplyUpdate.Effect(work.journal.token.transactionId, "recovery-$code", SessionEffect.Failed(code)),
        )
        return AcceptanceResult.RecoveryRequired(work.journal.token.transactionId, ApplyFailure(code))
    }

    private suspend fun cleanupPending(
        work: StudioApplyWork,
        code: String,
    ): AcceptanceResult.AcceptedCleanupPending {
        environment.publish(
            StudioApplyUpdate.Effect(work.journal.token.transactionId, "cleanup-$code", SessionEffect.Failed(code)),
        )
        return AcceptanceResult.AcceptedCleanupPending(
            work.journal.token.targetRevision,
            work.journal.token.transactionId,
            ApplyFailure(code),
        )
    }
}
