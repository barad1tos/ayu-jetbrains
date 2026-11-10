package dev.ayuislands.studio.persistence

import dev.ayuislands.studio.model.LegacyArchive
import dev.ayuislands.studio.model.MigrationSource
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioRuntimeState
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException

internal class StudioAppearanceStore(
    private val files: StudioFiles,
) : AutoCloseable {
    private val io = StudioStoreIo(files)
    private var phase: StoreLoad? = null
    private var accepted: StoredDocument? = null
    private var isClosed = false
    private val retiredRollbacks = mutableSetOf<PreparedToken>()

    @Synchronized
    fun load(): StoreLoad {
        if (isClosed) return StoreLoad.ReadOnly(StoreIssue.CLOSED)
        val result =
            try {
                val current = io.readState()
                accepted = current
                if (!files.hasWriteLock) {
                    StoreLoad.ReadOnly(StoreIssue.LOCKED, current?.document)
                } else {
                    readPhase(current)
                }
            } catch (failure: IOException) {
                StoreLoad.ReadOnly(issue(failure), accepted?.document)
            }
        phase = result
        return result
    }

    @Synchronized
    fun findSource(archives: StudioList<LegacyArchive>): MigrationSource? {
        if (isClosed) throw StoreDataException(StoreIssue.CLOSED)
        return io.findSource(archives)
    }

    @Synchronized
    fun preserveSource(source: MigrationSource): JournalResult {
        preparationProblem()?.let { return JournalResult.Rejected(it) }
        return try {
            io.preserveSource(source)
            io.verifySource(source)
            JournalResult.Acknowledged
        } catch (failure: IOException) {
            JournalResult.Rejected(issue(failure))
        }
    }

    @Synchronized
    fun prepare(
        expectedRevision: Long,
        candidate: StudioDocument,
        recoveryPlan: RecoveryPlan,
    ): PrepareResult {
        preparationProblem()?.let { return PrepareResult.Rejected(it) }
        return try {
            val current = io.readState()
            if (current == null && io.hasPrevious()) return PrepareResult.Rejected(StoreIssue.INVALID_DATA)
            if (current?.digest != accepted?.digest ||
                (current?.document?.header?.revision ?: -1) != expectedRevision
            ) {
                return PrepareResult.Rejected(StoreIssue.STALE)
            }
            if (io.readJournal() != null) return PrepareResult.Rejected(StoreIssue.BUSY)
            if (!validCandidate(current, candidate, expectedRevision)) {
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
            phase = StoreLoad.Recovering(current?.document, journal)
            PrepareResult.Prepared(token)
        } catch (failure: IOException) {
            phase = null
            PrepareResult.Rejected(issue(failure))
        }
    }

    @Synchronized
    fun recordRecovery(
        token: PreparedToken,
        receipts: List<PortableReceipt>,
    ): JournalResult {
        writeProblem()?.let { return JournalResult.Rejected(it) }
        return try {
            val journal = requireJournal(token)
            if (!retainsReceipts(journal.receipts, receipts)) return JournalResult.Rejected(StoreIssue.CONFLICT)
            val updated = journal.copy(receipts = StudioList(receipts))
            io.writeJournal(updated)
            phase = StoreLoad.Recovering(accepted?.document, updated)
            JournalResult.Acknowledged
        } catch (failure: IOException) {
            JournalResult.Rejected(issue(failure))
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
            when (resolveOutcome(token)) {
                CommitResolution.Committed -> return committedResult(token)
                CommitResolution.Unknown -> return CommitResult.OutcomeUnknown(StoreIssue.RECOVERY_REQUIRED)
                CommitResolution.NotCommitted -> Unit
            }
            val settled = journal.copy(candidate = journal.candidate.copy(runtime = settledRuntime))
            io.writeJournal(settled)
            io.writeState(settled)
            resolveResult(token, StoreIssue.IO)
        } catch (failure: IOException) {
            resolveResult(token, issue(failure))
        }
    }

    @Synchronized
    fun resolveOutcome(token: PreparedToken): CommitResolution =
        try {
            val journal = requireJournal(token)
            resolveCommit(token, candidateDigest(journal), io.readState()?.evidence() ?: CommitEvidence.Absent)
        } catch (_: IOException) {
            CommitResolution.Unknown
        }

    @Synchronized
    fun finishRollback(token: PreparedToken): JournalResult {
        writeProblem()?.let { return JournalResult.Rejected(it) }
        return try {
            if (token in retiredRollbacks && io.readJournal() == null) return reloadAfterCleanup()
            val journal = requireJournal(token)
            if (resolveOutcome(token) != CommitResolution.NotCommitted) {
                return JournalResult.Rejected(StoreIssue.RECOVERY_REQUIRED)
            }
            if (journal.receipts.any { !StudioReceiptPolicy.isTerminal(it) }) {
                return JournalResult.Rejected(StoreIssue.RECOVERY_REQUIRED)
            }
            retiredRollbacks.add(token)
            io.removeJournal()
            reloadAfterCleanup()
        } catch (failure: IOException) {
            JournalResult.Rejected(issue(failure))
        }
    }

    @Synchronized
    fun finishAccepted(token: PreparedToken): JournalResult {
        writeProblem()?.let { return JournalResult.Rejected(it) }
        return try {
            val journal = io.readJournal()
            if (journal == null) {
                val current = io.readState()
                if (current?.document?.header?.transactionId == token.transactionId &&
                    current.document.header.revision == token.targetRevision
                ) {
                    return reloadAfterCleanup()
                }
                return JournalResult.Rejected(StoreIssue.CONFLICT)
            }
            if (journal.token != token || resolveOutcome(token) != CommitResolution.Committed) {
                return JournalResult.Rejected(StoreIssue.RECOVERY_REQUIRED)
            }
            io.removeJournal()
            reloadAfterCleanup()
        } catch (failure: IOException) {
            JournalResult.Rejected(issue(failure))
        }
    }

    @Synchronized
    override fun close() {
        isClosed = true
        files.close()
    }

    private fun reloadAfterCleanup(): JournalResult =
        when (val loaded = load()) {
            is StoreLoad.ReadOnly -> JournalResult.Rejected(loaded.issue)
            is StoreLoad.Recovering -> JournalResult.Rejected(StoreIssue.RECOVERY_REQUIRED)
            StoreLoad.Empty, is StoreLoad.Ready -> JournalResult.Acknowledged
        }

    private fun readPhase(current: StoredDocument?): StoreLoad {
        val journal = io.readJournal()
        if (journal != null) return StoreLoad.Recovering(current?.document, journal)
        if (current != null) return StoreLoad.Ready(current.document)
        return if (io.hasPrevious()) StoreLoad.ReadOnly(StoreIssue.INVALID_DATA) else StoreLoad.Empty
    }

    private fun writeProblem(): StoreIssue? =
        when {
            isClosed -> StoreIssue.CLOSED
            !files.hasWriteLock -> StoreIssue.LOCKED
            else ->
                when (val current = phase) {
                    null -> StoreIssue.NOT_LOADED
                    is StoreLoad.ReadOnly -> current.issue
                    else -> null
                }
        }

    private fun preparationProblem(): StoreIssue? =
        writeProblem() ?: when (val current = phase) {
            null -> StoreIssue.NOT_LOADED
            is StoreLoad.ReadOnly -> current.issue
            is StoreLoad.Recovering -> StoreIssue.BUSY
            StoreLoad.Empty, is StoreLoad.Ready -> null
        }

    private fun validCandidate(
        current: StoredDocument?,
        candidate: StudioDocument,
        revision: Long,
    ): Boolean =
        revision < Long.MAX_VALUE &&
            candidate.header.revision == revision + 1 &&
            candidate.header.transactionId != current?.document?.header?.transactionId &&
            (current == null || candidate.header.migration == current.document.header.migration) &&
            StudioStateCodec.decode(StudioStateCodec.encode(candidate)) is DecodeResult.Ready

    private fun requireJournal(token: PreparedToken): PendingJournal {
        val journal = io.readJournal() ?: throw StoreDataException(StoreIssue.CONFLICT)
        if (journal.token != token) throw StoreDataException(StoreIssue.CONFLICT)
        return journal
    }

    private fun retainsReceipts(
        before: List<PortableReceipt>,
        after: List<PortableReceipt>,
    ): Boolean =
        after.size >= before.size &&
            before.indices.all { index -> after[index].copy(status = before[index].status) == before[index] }

    private fun resolveResult(
        token: PreparedToken,
        problem: StoreIssue,
    ): CommitResult =
        when (resolveOutcome(token)) {
            CommitResolution.Committed -> committedResult(token)
            CommitResolution.NotCommitted -> CommitResult.NotCommitted(problem)
            CommitResolution.Unknown -> CommitResult.OutcomeUnknown(problem)
        }

    private fun committedResult(token: PreparedToken): CommitResult =
        try {
            val current = io.readState() ?: throw StoreDataException(StoreIssue.CONFLICT)
            val journal = requireJournal(token)
            if (resolveCommit(token, candidateDigest(journal), current.evidence()) != CommitResolution.Committed) {
                CommitResult.OutcomeUnknown(StoreIssue.CONFLICT)
            } else {
                accepted = current
                phase = StoreLoad.Recovering(current.document, journal)
                CommitResult.Committed(current.document)
            }
        } catch (failure: IOException) {
            CommitResult.OutcomeUnknown(issue(failure))
        }

    private fun candidateDigest(journal: PendingJournal): String {
        val decoded = StudioStateCodec.decode(StudioStateCodec.encode(journal.candidate))
        check(decoded is DecodeResult.Ready)
        return decoded.digest
    }

    private fun issue(failure: IOException): StoreIssue =
        when (failure) {
            is StoreDataException -> failure.issue
            is AtomicMoveNotSupportedException -> StoreIssue.ATOMIC_MOVE_UNSUPPORTED
            else -> StoreIssue.IO
        }
}
