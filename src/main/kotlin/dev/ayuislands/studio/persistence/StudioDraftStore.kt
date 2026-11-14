package dev.ayuislands.studio.persistence

import dev.ayuislands.studio.model.DraftRestore
import dev.ayuislands.studio.model.DraftWritePlan
import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.session.StudioDraftRecovery
import dev.ayuislands.studio.session.StudioJournalPolicy

private const val RECOVERY_REQUIRED = "recovery-required"
private const val STORE_NOT_INITIALIZED = "store-not-initialized"

/** Invoked as a whole operation on the application's serialized transaction queue. */
internal class StudioDraftStore(
    private val store: StudioAppearanceStore,
) {
    private data class DraftWrite(
        val candidate: StudioDocument,
        val recovery: RecoveryPlan,
        val token: PreparedToken? = null,
        val restore: DraftRestore? = null,
    ) {
        fun matches(journal: PendingJournal): Boolean =
            journal.candidate == candidate && journal.recovery == recovery && (token == null || journal.token == token)
    }

    private var pendingWrite: DraftWrite? = null

    fun save(
        record: StudioSessionRecord,
        transactionId: String,
    ): DraftWriteResult {
        val loaded = store.load()
        if (loaded is StoreLoad.Recovering) {
            settleOwnWrite(loaded, record)?.let { return it }
        }
        val ready = if (loaded is StoreLoad.Recovering) store.load() else loaded
        return when (ready) {
            is StoreLoad.Ready -> {
                pendingWrite = null
                when (val plan = StudioJournalPolicy.save(ready.document, record, transactionId)) {
                    is DraftWritePlan.Write -> write(ready.document, plan.candidate, record)
                    is DraftWritePlan.Unchanged -> DraftWriteResult.Saved(plan.revision)
                    is DraftWritePlan.Rejected -> DraftWriteResult.Failed("draft-${plan.issue.name.lowercase()}")
                }
            }
            is StoreLoad.ReadOnly -> DraftWriteResult.Failed("store-${ready.issue.name.lowercase()}")
            is StoreLoad.Recovering -> DraftWriteResult.Failed(RECOVERY_REQUIRED)
            StoreLoad.Empty -> DraftWriteResult.Failed(STORE_NOT_INITIALIZED)
        }
    }

    /** Finishes the latest durable record only after this writer's pending decision has settled. */
    fun finish(
        sessionId: String,
        availability: SessionAvailability,
        transactionId: String,
    ): DraftWriteResult {
        require(availability != SessionAvailability.LIVE)
        val loaded = store.load()
        if (loaded is StoreLoad.Recovering) {
            settleOwnWrite(loaded, null)?.let { return it }
        }
        val ready = if (loaded is StoreLoad.Recovering) store.load() else loaded
        return when (ready) {
            is StoreLoad.Ready -> {
                pendingWrite = null
                val record = ready.document.sessions.singleOrNull { it.id == sessionId }
                if (record == null || record.availability == availability) {
                    DraftWriteResult.Saved(ready.document.header.revision)
                } else {
                    save(record.copy(generation = record.generation + 1, availability = availability), transactionId)
                }
            }
            is StoreLoad.ReadOnly -> DraftWriteResult.Failed("store-${ready.issue.name.lowercase()}")
            is StoreLoad.Recovering -> DraftWriteResult.Failed(RECOVERY_REQUIRED)
            StoreLoad.Empty -> DraftWriteResult.Failed(STORE_NOT_INITIALIZED)
        }
    }

    /** Transfers one explicitly selected source and retires it in the same accepted document. */
    fun restore(
        request: DraftRestore,
        attached: Set<String>,
    ): DraftWriteResult {
        val loaded = store.load()
        val attempted = pendingWrite?.takeIf { it.restore == request }
        if (loaded is StoreLoad.Recovering) {
            settleOwnWrite(loaded, null)?.let { return it }
        }
        val ready = if (loaded is StoreLoad.Recovering) store.load() else loaded
        return when (ready) {
            is StoreLoad.Ready -> {
                pendingWrite = null
                val document = ready.document
                if (attempted?.candidate == document) {
                    DraftWriteResult.Saved(document.header.revision)
                } else {
                    when (val plan = StudioDraftRecovery.restore(document, request, attached)) {
                        is DraftWritePlan.Write ->
                            write(
                                document,
                                plan.candidate,
                                plan.candidate.sessions.single {
                                    it.id ==
                                        request.session
                                },
                                restore = request,
                            )
                        is DraftWritePlan.Unchanged -> DraftWriteResult.Saved(plan.revision)
                        is DraftWritePlan.Rejected -> DraftWriteResult.Failed("draft-${plan.issue.name.lowercase()}")
                    }
                }
            }
            is StoreLoad.ReadOnly -> DraftWriteResult.Failed("store-${ready.issue.name.lowercase()}")
            is StoreLoad.Recovering -> DraftWriteResult.Failed(RECOVERY_REQUIRED)
            StoreLoad.Empty -> DraftWriteResult.Failed(STORE_NOT_INITIALIZED)
        }
    }

    /** Retains the exact work as a retirement watermark; registered bindings cannot be discarded here. */
    fun discard(
        sessionId: String,
        transactionId: String,
        attached: Set<String>,
    ): DraftWriteResult {
        val loaded = store.load()
        if (loaded is StoreLoad.Recovering) {
            settleOwnWrite(loaded, null)?.let { return it }
        }
        val ready = if (loaded is StoreLoad.Recovering) store.load() else loaded
        return when (ready) {
            is StoreLoad.Ready -> {
                pendingWrite = null
                when (val plan = StudioDraftRecovery.discard(ready.document, sessionId, transactionId, attached)) {
                    is DraftWritePlan.Write ->
                        write(
                            ready.document,
                            plan.candidate,
                            plan.candidate.sessions.single {
                                it.id ==
                                    sessionId
                            },
                        )
                    is DraftWritePlan.Unchanged -> DraftWriteResult.Saved(plan.revision)
                    is DraftWritePlan.Rejected -> DraftWriteResult.Failed("draft-${plan.issue.name.lowercase()}")
                }
            }
            is StoreLoad.ReadOnly -> DraftWriteResult.Failed("store-${ready.issue.name.lowercase()}")
            is StoreLoad.Recovering -> DraftWriteResult.Failed(RECOVERY_REQUIRED)
            StoreLoad.Empty -> DraftWriteResult.Failed(STORE_NOT_INITIALIZED)
        }
    }

    private fun write(
        latest: StudioDocument,
        candidate: StudioDocument,
        record: StudioSessionRecord,
        restore: DraftRestore? = null,
    ): DraftWriteResult {
        val recovery = RecoveryPlan(record.target, StudioList(emptyList()))
        val attempt = DraftWrite(candidate, recovery, restore = restore)
        pendingWrite = attempt
        val prepared = store.prepare(latest.header.revision, candidate, recovery)
        if (prepared is PrepareResult.Rejected) {
            return DraftWriteResult.Failed(
                "prepare-${prepared.issue.name.lowercase()}",
            )
        }
        val token = (prepared as PrepareResult.Prepared).token
        pendingWrite = attempt.copy(token = token)
        return when (val result = store.commit(token, candidate.runtime)) {
            is CommitResult.Committed -> {
                if (store.finishAccepted(token) == JournalResult.Acknowledged) pendingWrite = null
                DraftWriteResult.Saved(result.document.header.revision)
            }
            is CommitResult.NotCommitted -> {
                if (store.finishRollback(token) == JournalResult.Acknowledged) pendingWrite = null
                DraftWriteResult.Failed("commit-${result.issue.name.lowercase()}")
            }
            is CommitResult.OutcomeUnknown -> DraftWriteResult.Failed("save-unconfirmed")
        }
    }

    /** Null means cleanup settled; keep write evidence until the caller reads a Ready document. */
    private fun settleOwnWrite(
        pending: StoreLoad.Recovering,
        requested: StudioSessionRecord?,
    ): DraftWriteResult? {
        val journal = pending.journal
        if (pendingWrite?.matches(journal) != true || journal.receipts.isNotEmpty()) {
            return DraftWriteResult.Failed(RECOVERY_REQUIRED)
        }
        val resolution = store.resolveOutcome(journal.token)
        val cleanup =
            when (resolution) {
                CommitResolution.Committed -> store.finishAccepted(journal.token)
                CommitResolution.NotCommitted -> store.finishRollback(journal.token)
                CommitResolution.Unknown -> return DraftWriteResult.Failed("save-unconfirmed")
            }
        if (cleanup == JournalResult.Acknowledged) return null
        val current = pending.document
        if (resolution == CommitResolution.Committed &&
            requested != null &&
            current?.sessions?.singleOrNull { it.id == requested.id } == requested
        ) {
            return DraftWriteResult.Saved(current.header.revision)
        }
        return DraftWriteResult.Failed("cleanup-required")
    }
}
