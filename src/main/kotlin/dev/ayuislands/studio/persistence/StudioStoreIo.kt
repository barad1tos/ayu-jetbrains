package dev.ayuislands.studio.persistence

import dev.ayuislands.studio.model.LegacyArchive
import dev.ayuislands.studio.model.MigrationSource
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import java.io.IOException

internal enum class StoreIssue { IO, INVALID_DATA, FUTURE_SCHEMA, INVALID_CANDIDATE, CONFLICT }

internal class StoreDataException(
    val issue: StoreIssue,
    cause: Throwable? = null,
) : IOException(issue.name, cause)

internal data class StoredDocument(
    val document: StudioDocument,
    val digest: String,
    val bytes: StudioBytes,
) {
    fun evidence(): CommitEvidence.Valid =
        CommitEvidence.Valid(document.header.transactionId, document.header.revision, digest)
}

/** Publications acknowledge exact on-disk bytes; acceptance policy belongs to the store above this adapter. */
internal class StudioStoreIo(
    private val files: StudioFiles,
) {
    private val migrationArchive = StudioMigrationArchive(files)

    fun readState(): StoredDocument? = files.read(STATE)?.let(::decodeState)

    fun readJournal(): PendingJournal? = files.read(JOURNAL)?.let(::decodeJournal)

    fun hasPrevious(): Boolean = files.read(PREVIOUS) != null

    fun findSource(archives: StudioList<LegacyArchive>): MigrationSource? = migrationArchive.findSource(archives)

    fun preserveSource(source: MigrationSource) = migrationArchive.preserve(source)

    fun verifySource(source: MigrationSource) = migrationArchive.verify(source)

    fun writeJournal(journal: PendingJournal) {
        val id = requireStoreFileId(journal.token.transactionId)
        val bytes = encodeCandidate { StudioJournalCodec.encode(journal) }
        if (!StudioJournalCodec.isValid(bytes)) throw StoreDataException(StoreIssue.INVALID_CANDIDATE)
        files.writeVerified(JOURNAL, bytes, id)
    }

    fun writeState(journal: PendingJournal) {
        val id = requireStoreFileId(journal.token.transactionId)
        val previous = journal.base?.toByteArray()?.also(::decodeState)
        val bytes = encodeCandidate { StudioStateCodec.encode(journal.candidate) }
        decodeState(bytes)
        files.whileWritable {
            if (previous != null) files.writeVerified(PREVIOUS, previous, id)
            files.writeVerified(STATE, bytes, id)
        }
    }

    fun removeJournal() =
        files.whileWritable {
            files.remove(JOURNAL)
            if (files.read(JOURNAL) != null) throw StoreDataException(StoreIssue.IO)
        }

    private fun decodeState(bytes: ByteArray): StoredDocument =
        when (val decoded = StudioStateCodec.decode(bytes)) {
            is DecodeResult.Ready -> StoredDocument(decoded.document, decoded.digest, StudioBytes(bytes))
            is DecodeResult.FutureSchema -> throw StoreDataException(StoreIssue.FUTURE_SCHEMA)
            is DecodeResult.Invalid -> throw StoreDataException(StoreIssue.INVALID_DATA)
        }

    private fun decodeJournal(bytes: ByteArray): PendingJournal =
        when (val decoded = StudioJournalCodec.decode(bytes)) {
            is JournalDecodeResult.Ready -> decoded.journal
            is JournalDecodeResult.FutureSchema -> throw StoreDataException(StoreIssue.FUTURE_SCHEMA)
            is JournalDecodeResult.Invalid -> throw StoreDataException(StoreIssue.INVALID_DATA)
        }

    private inline fun encodeCandidate(encode: () -> ByteArray): ByteArray =
        try {
            encode()
        } catch (failure: IllegalArgumentException) {
            throw StoreDataException(StoreIssue.INVALID_CANDIDATE, failure)
        }

    private companion object {
        const val STATE = "state.xml"
        const val PREVIOUS = "state.previous.xml"
        const val JOURNAL = "pending.xml"
    }
}

internal fun requireStoreFileId(id: String): String {
    if (!transactionFileId.matches(id)) throw StoreDataException(StoreIssue.INVALID_CANDIDATE)
    return id
}
