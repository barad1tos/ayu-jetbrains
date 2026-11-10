package dev.ayuislands.studio.persistence

import dev.ayuislands.studio.model.LegacyArchive
import dev.ayuislands.studio.model.LegacyFile
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.MigrationSource
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import java.io.IOException
import javax.xml.stream.XMLStreamException

private const val PENDING_FILE = "pending.xml"
private const val SOURCE_BACKUP = "source-backup"

internal data class StoredDocument(
    val document: StudioDocument,
    val digest: String,
    val bytes: StudioBytes,
) {
    fun evidence(): CommitEvidence.Valid =
        CommitEvidence.Valid(
            document.header.transactionId,
            document.header.revision,
            digest,
        )
}

internal class StoreDataException(
    val issue: StoreIssue,
) : IOException(issue.name)

/** File order and durable acknowledgements live here; appearance policy never enters this boundary. */
internal class StudioStoreIo(
    private val files: StudioFiles,
) {
    fun readState(): StoredDocument? = files.read("state.xml")?.let(::decodeState)

    fun readJournal(): PendingJournal? = files.read(PENDING_FILE)?.let(::decodeJournal)

    fun hasPrevious(): Boolean = files.read("state.previous.xml") != null

    /** Only complete, verified manifests may supply a retry's original migration identity. */
    fun findSource(archives: StudioList<LegacyArchive>): MigrationSource? {
        val sources = files.directories(SOURCE_BACKUP).sorted().mapNotNull(::readSource)
        return sources.firstOrNull { it.archives == archives }
    }

    fun writeJournal(journal: PendingJournal) {
        val bytes = StudioJournalCodec.encode(journal)
        if (!StudioJournalCodec.isValid(bytes)) throw StoreDataException(StoreIssue.INVALID_DATA)
        writeVerified(PENDING_FILE, bytes, journal.token.transactionId)
    }

    fun writeState(journal: PendingJournal) {
        val bytes = StudioStateCodec.encode(journal.candidate)
        decodeState(bytes)
        journal.base?.let { base ->
            val previous = base.toByteArray()
            decodeState(previous)
            writeVerified("state.previous.xml", previous, journal.token.transactionId)
        }
        writeVerified("state.xml", bytes, journal.token.transactionId)
    }

    fun removeJournal() {
        files.remove(PENDING_FILE)
        if (files.read(PENDING_FILE) != null) throw StoreDataException(StoreIssue.IO)
    }

    fun preserveSource(source: MigrationSource) {
        val prefix = "$SOURCE_BACKUP/${safeId(source.id)}"
        val manifest = sourceManifest(source)
        val previous = files.read("$prefix/manifest.xml")
        if (previous != null && !previous.contentEquals(manifest)) throw StoreDataException(StoreIssue.CONFLICT)
        for ((store, file) in source.archives) {
            if (file is LegacyFile.Present) {
                createVerified("$prefix/${store.name.lowercase()}.xml", file.bytes.toByteArray())
            }
        }
        createVerified("$prefix/manifest.xml", manifest)
    }

    fun verifySource(source: MigrationSource) {
        val prefix = "$SOURCE_BACKUP/${safeId(source.id)}"
        val manifest = files.read("$prefix/manifest.xml")
        if (manifest == null || !manifest.contentEquals(sourceManifest(source))) {
            throw StoreDataException(StoreIssue.INVALID_CANDIDATE)
        }
        for ((store, original) in source.archives) {
            val archived = files.read("$prefix/${store.name.lowercase()}.xml")
            val matches =
                when (original) {
                    LegacyFile.Absent -> archived == null
                    is LegacyFile.Present ->
                        archived != null && archived.contentEquals(original.bytes.toByteArray())
                }
            if (!matches) throw StoreDataException(StoreIssue.CONFLICT)
        }
    }

    private fun createVerified(
        name: String,
        bytes: ByteArray,
    ) {
        val existing = files.read(name)
        if (existing == null) {
            files.create(name).use { staged ->
                staged.write(bytes)
                staged.force()
            }
        } else if (!existing.contentEquals(bytes)) {
            throw StoreDataException(StoreIssue.CONFLICT)
        }
        val actual = files.read(name)
        if (actual == null || !actual.contentEquals(bytes)) throw StoreDataException(StoreIssue.IO)
    }

    private fun readSource(id: String): MigrationSource? {
        val prefix = "$SOURCE_BACKUP/${safeId(id)}"
        val bytes = files.read("$prefix/manifest.xml") ?: return null
        val source =
            try {
                val manifest = StudioXml.parse(bytes).requireShape(setOf("migration"), setOf("archive"))
                require(manifest.name == SOURCE_BACKUP && StudioXml.decodeText(manifest.attribute("migration")) == id)
                val archives = manifest.children.map { entry -> readArchive(prefix, entry) }
                require(archives.size == LegacyStore.entries.size)
                require(archives.map { it.store }.toSet() == LegacyStore.entries.toSet())
                MigrationSource(id, StudioList(archives))
            } catch (_: IllegalArgumentException) {
                throw StoreDataException(StoreIssue.INVALID_DATA)
            } catch (_: XMLStreamException) {
                throw StoreDataException(StoreIssue.INVALID_DATA)
            }
        verifySource(source)
        return source
    }

    private fun readArchive(
        prefix: String,
        entry: StudioXmlNode,
    ): LegacyArchive {
        val store = LegacyStore.valueOf(entry.attribute("store"))
        require(entry.attribute("filename") == legacyFileNames.getValue(store))
        val presence = entry.attribute("presence")
        val attributes = setOf("store", "filename", "presence")
        val bytes = files.read("$prefix/${store.name.lowercase()}.xml")
        val matches =
            when (presence) {
                "absent" -> {
                    entry.requireShape(attributes)
                    bytes == null
                }
                "value" -> {
                    entry.requireShape(attributes + "sha256")
                    bytes != null && StudioXml.hashBytes(bytes) == entry.attribute("sha256")
                }
                else -> throw StoreDataException(StoreIssue.INVALID_DATA)
            }
        if (!matches) throw StoreDataException(StoreIssue.CONFLICT)
        val original = bytes?.let { LegacyFile.Present(StudioBytes(it)) } ?: LegacyFile.Absent
        return LegacyArchive(store, original)
    }

    /** Callers validate once; both readbacks must match those exact validated bytes. */
    private fun writeVerified(
        name: String,
        bytes: ByteArray,
        transactionId: String,
    ) {
        val temporary = "$name.tmp.${safeId(transactionId)}"
        files.stage(temporary).use { staged ->
            staged.write(bytes)
            staged.force()
        }
        readExact(temporary, bytes)
        files.replace(temporary, name)
        readExact(name, bytes)
    }

    private fun readExact(
        name: String,
        expected: ByteArray,
    ): ByteArray {
        val actual = files.read(name) ?: throw StoreDataException(StoreIssue.IO)
        if (!actual.contentEquals(expected)) throw StoreDataException(StoreIssue.IO)
        return actual
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

    private fun safeId(value: String): String {
        if (!value.matches(Regex("[A-Za-z0-9_-]{1,128}"))) throw StoreDataException(StoreIssue.INVALID_CANDIDATE)
        return value
    }

    private fun sourceManifest(source: MigrationSource): ByteArray =
        StudioXml.write(
            StudioXmlNode(
                SOURCE_BACKUP,
                mapOf("migration" to StudioXml.encodeText(source.id)),
                source.archives.map { archive ->
                    val original = archive.file
                    val attributes =
                        mapOf(
                            "store" to archive.store.name,
                            "filename" to legacyFileNames.getValue(archive.store),
                            "presence" to if (original is LegacyFile.Present) "value" else "absent",
                        )
                    StudioXmlNode(
                        "archive",
                        if (original is LegacyFile.Present) {
                            attributes + ("sha256" to StudioXml.hashBytes(original.bytes.toByteArray()))
                        } else {
                            attributes
                        },
                    )
                },
            ),
        )
}
