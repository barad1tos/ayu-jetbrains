package dev.ayuislands.studio.persistence

import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.LegacyArchive
import dev.ayuislands.studio.model.MigrationSource
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioRuntimeState
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException

/** Serializes durable changes. Only verified state bytes can settle a pending transaction. */
internal class StudioAppearanceStore(
    private val files: StudioFiles,
) : AutoCloseable {
    private val io = StudioStoreIo(files)
    private var phase: StorePhase = StorePhase.Unloaded
    private var finished: FinishedTransaction? = null

    @Synchronized
    fun load(): StoreLoad {
        if (phase == StorePhase.Closed) return StoreLoad.ReadOnly(StoreIssue.CLOSED)
        var accepted: StoredDocument? = null
        val loaded =
            try {
                accepted = io.readState()
                observed(accepted)
            } catch (failure: IOException) {
                StoreLoad.ReadOnly(storeIssue(failure), accepted?.document)
            }
        phase = StorePhase.Loaded(loaded, accepted)
        return loaded
    }

    @Synchronized
    fun findSource(archives: StudioList<LegacyArchive>): MigrationSource? {
        if (phase == StorePhase.Closed) throw StoreDataException(StoreIssue.CLOSED)
        return io.findSource(archives)
    }

    @Synchronized
    fun preserveSource(source: MigrationSource): JournalResult {
        prepareProblem()?.let { return JournalResult.Rejected(it) }
        return journalChange { io.preserveSource(source) }
    }

    /** Journals the admitted plan before the coordinator can write any of its native effects. */
    fun prepare(plan: AcceptancePlan.Commit): PrepareResult =
        prepare(
            plan.lease.documentRevision,
            plan.document,
            RecoveryPlan(plan.target, plan.route.owners, plan.limitations),
        )

    @Synchronized
    fun prepare(
        expectedRevision: Long,
        candidate: StudioDocument,
        recoveryPlan: RecoveryPlan,
    ): PrepareResult {
        prepareProblem()?.let { return PrepareResult.Rejected(it) }
        val loaded = phase as StorePhase.Loaded
        return try {
            val current = io.readState()
            if (current == null && io.hasPrevious()) return PrepareResult.Rejected(StoreIssue.INVALID_DATA)
            if (current?.digest != loaded.accepted?.digest || current.revision() != expectedRevision) {
                return PrepareResult.Rejected(StoreIssue.STALE)
            }
            if (io.readJournal() != null) return PrepareResult.Rejected(StoreIssue.BUSY)
            if (!canPrepare(current, candidate, expectedRevision)) {
                return PrepareResult.Rejected(StoreIssue.INVALID_CANDIDATE)
            }
            io.verifySource(candidate.header.migration)
            val token =
                PreparedToken(
                    candidate.header.transactionId,
                    expectedRevision,
                    current?.digest,
                    candidate.header.revision,
                )
            val journal = PendingJournal(token, current?.bytes, candidate, recoveryPlan, StudioList(emptyList()))
            io.writeJournal(journal)
            phase = StorePhase.Loaded(StoreLoad.Recovering(current?.document, journal), current)
            finished = null
            PrepareResult.Prepared(journal)
        } catch (failure: IOException) {
            phase = StorePhase.Unloaded
            PrepareResult.Rejected(storeIssue(failure))
        }
    }

    @Synchronized
    fun recordRecovery(
        token: PreparedToken,
        receipts: List<PortableReceipt>,
    ): JournalResult {
        writeProblem()?.let { return JournalResult.Rejected(it) }
        return journalChange {
            val journal = requireJournal(token)
            if (!retainsReceipts(journal.receipts, receipts)) throw StoreDataException(StoreIssue.CONFLICT)
            val updated = journal.copy(receipts = StudioList(receipts))
            io.writeJournal(updated)
            val loaded = phase as StorePhase.Loaded
            phase = loaded.copy(status = StoreLoad.Recovering(loaded.accepted?.document, updated))
        }
    }

    @Synchronized
    fun commit(
        token: PreparedToken,
        settledRuntime: StudioRuntimeState,
    ): CommitResult {
        writeProblem()?.let { return CommitResult.OutcomeUnknown(it) }
        return try {
            val journal = requireJournal(token)
            val existing = observeDecision(journal)
            when (existing.resolution) {
                CommitResolution.Committed -> acknowledge(journal, existing)
                CommitResolution.Unknown -> CommitResult.OutcomeUnknown(StoreIssue.RECOVERY_REQUIRED)
                CommitResolution.NotCommitted -> {
                    val settled = journal.copy(candidate = journal.candidate.copy(runtime = settledRuntime))
                    io.writeJournal(settled)
                    io.writeState(settled)
                    resolveResult(token, StoreIssue.IO)
                }
            }
        } catch (failure: IOException) {
            resolveResult(token, storeIssue(failure))
        }
    }

    @Synchronized
    fun resolveOutcome(token: PreparedToken): CommitResolution {
        if (phase == StorePhase.Closed) return CommitResolution.Unknown
        return try {
            observeDecision(requireJournal(token)).resolution
        } catch (_: IOException) {
            CommitResolution.Unknown
        }
    }

    @Synchronized
    fun finishAccepted(token: PreparedToken): JournalResult = finish(token, CommitResolution.Committed)

    @Synchronized
    fun finishRollback(token: PreparedToken): JournalResult = finish(token, CommitResolution.NotCommitted)

    @Synchronized
    override fun close() {
        if (phase == StorePhase.Closed) return
        phase = StorePhase.Closed
        files.close()
    }

    private fun finish(
        token: PreparedToken,
        expected: CommitResolution,
    ): JournalResult {
        writeProblem()?.let { return JournalResult.Rejected(it) }
        return journalChange {
            val journal = io.readJournal()
            val accepted = io.readState()
            val evidence = evidenceOf(accepted)
            val completion = FinishedTransaction(token, expected, evidence)
            val problem = cleanupProblem(journal, completion)
            if (problem != null) throw StoreDataException(problem)
            if (journal != null) {
                finished = completion
                io.removeJournal()
            }
            refreshAfterCleanup(accepted)
        }
    }

    private fun cleanupProblem(
        journal: PendingJournal?,
        completion: FinishedTransaction,
    ): StoreIssue? =
        when {
            journal == null -> StoreIssue.CONFLICT.takeUnless { finished == completion }
            journal.token != completion.token -> StoreIssue.CONFLICT
            decision(journal, completion.evidence) != completion.resolution -> StoreIssue.RECOVERY_REQUIRED
            completion.resolution == CommitResolution.NotCommitted &&
                !journal.receipts.all(StudioReceiptPolicy::isTerminal) -> StoreIssue.RECOVERY_REQUIRED
            else -> null
        }

    private fun refreshAfterCleanup(accepted: StoredDocument?) {
        val loaded = observed(accepted)
        phase = StorePhase.Loaded(loaded, accepted)
        val problem =
            when (loaded) {
                is StoreLoad.ReadOnly -> loaded.issue
                is StoreLoad.Recovering -> StoreIssue.RECOVERY_REQUIRED
                StoreLoad.Empty, is StoreLoad.Ready -> null
            }
        if (problem != null) throw StoreDataException(problem)
    }

    private fun observed(current: StoredDocument?): StoreLoad {
        if (!files.hasWriteLock) return StoreLoad.ReadOnly(StoreIssue.LOCKED, current?.document)
        val journal = io.readJournal()
        if (journal != null) return StoreLoad.Recovering(current?.document, journal)
        if (current != null) return StoreLoad.Ready(current.document)
        return if (io.hasPrevious()) StoreLoad.ReadOnly(StoreIssue.INVALID_DATA) else StoreLoad.Empty
    }

    private fun writeProblem(): StoreIssue? =
        when (val current = phase) {
            StorePhase.Closed -> StoreIssue.CLOSED
            StorePhase.Unloaded -> if (files.hasWriteLock) StoreIssue.NOT_LOADED else StoreIssue.LOCKED
            is StorePhase.Loaded -> {
                if (files.hasWriteLock) (current.status as? StoreLoad.ReadOnly)?.issue else StoreIssue.LOCKED
            }
        }

    private fun prepareProblem(): StoreIssue? {
        val problem = writeProblem()
        if (problem != null) return problem
        return if ((phase as StorePhase.Loaded).status is StoreLoad.Recovering) StoreIssue.BUSY else null
    }

    private fun requireJournal(token: PreparedToken): PendingJournal {
        val journal = io.readJournal()
        if (journal?.token != token) throw StoreDataException(StoreIssue.CONFLICT)
        return journal
    }

    private fun observeDecision(journal: PendingJournal): StoreDecision {
        val current = io.readState()
        val resolution = decision(journal, evidenceOf(current))
        return StoreDecision(current, resolution)
    }

    private fun evidenceOf(current: StoredDocument?): CommitEvidence =
        current?.evidence() ?: if (io.hasPrevious()) CommitEvidence.Unreadable else CommitEvidence.Absent

    private fun resolveResult(
        token: PreparedToken,
        problem: StoreIssue,
    ): CommitResult =
        try {
            val journal = requireJournal(token)
            val observed = observeDecision(journal)
            when (observed.resolution) {
                CommitResolution.Committed -> acknowledge(journal, observed)
                CommitResolution.NotCommitted -> CommitResult.NotCommitted(problem)
                CommitResolution.Unknown -> CommitResult.OutcomeUnknown(problem)
            }
        } catch (failure: IOException) {
            CommitResult.OutcomeUnknown(storeIssue(failure))
        }

    private fun acknowledge(
        journal: PendingJournal,
        observed: StoreDecision,
    ): CommitResult {
        val accepted = checkNotNull(observed.current)
        phase = StorePhase.Loaded(StoreLoad.Recovering(accepted.document, journal), accepted)
        return CommitResult.Committed(accepted.document)
    }

    private inline fun journalChange(change: () -> Unit): JournalResult =
        try {
            change()
            JournalResult.Acknowledged
        } catch (failure: IOException) {
            JournalResult.Rejected(storeIssue(failure))
        }
}

private sealed interface StorePhase {
    data object Unloaded : StorePhase

    data object Closed : StorePhase

    data class Loaded(
        val status: StoreLoad,
        val accepted: StoredDocument?,
    ) : StorePhase
}

private data class StoreDecision(
    val current: StoredDocument?,
    val resolution: CommitResolution,
)

private data class FinishedTransaction(
    val token: PreparedToken,
    val resolution: CommitResolution,
    val evidence: CommitEvidence,
)

private fun StoredDocument?.revision(): Long = this?.document?.header?.revision ?: -1

private fun canPrepare(
    current: StoredDocument?,
    candidate: StudioDocument,
    revision: Long,
): Boolean =
    revision < Long.MAX_VALUE &&
        candidate.header.revision == revision + 1 &&
        transactionFileId.matches(candidate.header.transactionId) &&
        candidate.header.transactionId != current?.document?.header?.transactionId &&
        (current == null || candidate.header.migration == current.document.header.migration)

private fun retainsReceipts(
    before: List<PortableReceipt>,
    after: List<PortableReceipt>,
): Boolean =
    after.size >= before.size && before.indices.all { after[it].copy(status = before[it].status) == before[it] }

private fun decision(
    journal: PendingJournal,
    evidence: CommitEvidence,
): CommitResolution {
    val bytes = StudioStateCodec.encode(journal.candidate)
    val decoded = StudioStateCodec.decode(bytes)
    check(decoded is DecodeResult.Ready)
    return resolveCommit(journal.token, decoded.digest, evidence)
}

private fun storeIssue(failure: IOException): StoreIssue =
    when (failure) {
        is StoreDataException -> failure.issue
        is AtomicMoveNotSupportedException -> StoreIssue.ATOMIC_MOVE_UNSUPPORTED
        else -> StoreIssue.IO
    }
