package dev.ayuislands.studio

import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.LegacyArchive
import dev.ayuislands.studio.model.LegacyFile
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.MigrationSource
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.CommitEvidence
import dev.ayuislands.studio.persistence.DecodeResult
import dev.ayuislands.studio.persistence.LegacyCapture
import dev.ayuislands.studio.persistence.PendingJournal
import dev.ayuislands.studio.persistence.PreparedToken
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.StoreDataException
import dev.ayuislands.studio.persistence.StoreIssue
import dev.ayuislands.studio.persistence.StudioFiles
import dev.ayuislands.studio.persistence.StudioLegacyCapture
import dev.ayuislands.studio.persistence.StudioStateCodec
import dev.ayuislands.studio.persistence.StudioStoreIo
import dev.ayuislands.studio.persistence.openStudioFiles
import dev.ayuislands.studio.persistence.writeVerified
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioStoreIoTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `prepared journal and previous bytes survive reopen without becoming the accepted document`() {
        val first = firstJournal()
        val base = StudioStateCodec.encode(first.candidate)
        val next = nextJournal(first, base)
        openStudioFiles(directory).use { files ->
            val store = StudioStoreIo(files)
            assertNull(store.readState())
            assertNull(store.readJournal())
            assertFalse(store.hasPrevious())
            store.writeJournal(first)
            assertNull(store.readState())
            store.writeState(first)
            store.removeJournal()
            store.writeJournal(next)
        }
        openStudioFiles(directory).use { files ->
            val store = StudioStoreIo(files)
            assertEquals(next, store.readJournal())
            val accepted = checkNotNull(store.readState())
            assertEquals(first.candidate, accepted.document)
            val expected = CommitEvidence.Valid("initial", 0, next.token.baseDigest.orEmpty())
            assertEquals(expected, accepted.evidence())
            store.writeState(next)
            assertTrue(store.hasPrevious())
            assertContentEquals(base, files.read("state.previous.xml"))
            assertEquals(next.candidate, store.readState()?.document)
            store.removeJournal()
            assertNull(store.readJournal())
        }
    }

    @Test
    fun `invalid replacement evidence leaves the last verified journal untouched`() {
        val prepared = firstJournal()
        openStudioFiles(directory).use { files ->
            val store = StudioStoreIo(files)
            store.writeJournal(prepared)
            val initial = files.read("pending.xml")
            val badIdentity = prepared.copy(token = prepared.token.copy(transactionId = "../unsafe"))
            val mismatched = prepared.copy(token = prepared.token.copy(transactionId = "different"))
            for (invalid in listOf(badIdentity, mismatched)) {
                val rejected = assertFailsWith<StoreDataException> { store.writeJournal(invalid) }
                assertEquals(StoreIssue.INVALID_CANDIDATE, rejected.issue)
                assertContentEquals(initial, files.read("pending.xml"))
            }
            assertEquals(prepared, store.readJournal())
        }
    }

    @Test
    fun `a failed previous generation backup cannot replace accepted state or clear recovery`() {
        val first = firstJournal()
        val base = StudioStateCodec.encode(first.candidate)
        val next = nextJournal(first, base)
        openStudioFiles(directory).use { files ->
            val store = StudioStoreIo(files)
            store.writeState(first)
            store.writeJournal(next)
            val interrupted =
                object : StudioFiles by files {
                    override fun replace(
                        source: String,
                        destination: String,
                    ) {
                        if (destination == "state.previous.xml") throw IOException("Backup replacement failed")
                        files.replace(source, destination)
                    }
                }
            assertFailsWith<IOException> { StudioStoreIo(interrupted).writeState(next) }
            assertContentEquals(base, files.read("state.xml"))
            assertEquals(next, store.readJournal())
        }
    }

    @Test
    fun `unreadable invalid and future stores are never mistaken for missing files`() {
        openStudioFiles(directory).use { files ->
            val store = StudioStoreIo(files)
            val future = """<ayu-studio schema="2"/>""".encodeToByteArray()
            files.writeVerified("state.xml", future, "future")
            assertEquals(StoreIssue.FUTURE_SCHEMA, assertFailsWith<StoreDataException> { store.readState() }.issue)
            assertContentEquals(future, files.read("state.xml"))

            val damaged = "damaged".encodeToByteArray()
            files.writeVerified("pending.xml", damaged, "invalid")
            assertEquals(StoreIssue.INVALID_DATA, assertFailsWith<StoreDataException> { store.readJournal() }.issue)
            assertContentEquals(damaged, files.read("pending.xml"))

            val futureJournal = """<journal schema="3"/>""".encodeToByteArray()
            files.writeVerified("pending.xml", futureJournal, "future")
            val unsupported = assertFailsWith<StoreDataException> { store.readJournal() }
            assertEquals(StoreIssue.FUTURE_SCHEMA, unsupported.issue)
            assertContentEquals(futureJournal, files.read("pending.xml"))

            val readFailure =
                object : StudioFiles by files {
                    override fun read(name: String): Nothing = throw IOException("Read path is inaccessible")
                }
            assertFailsWith<IOException> { StudioStoreIo(readFailure).readState() }
            assertFailsWith<IOException> { StudioStoreIo(readFailure).readJournal() }
        }
    }

    @Test
    fun `migration retry reuses only a verified manifest and keeps changed source as a new archive`() {
        val source = source("first", byteArrayOf(0, -1, 10))
        val changed = source("second", byteArrayOf(0, -1, 13))
        openStudioFiles(directory).use { files ->
            val store = StudioStoreIo(files)
            files.writeVerified("source-backup/abandoned/settings.xml", byteArrayOf(0, -1, 10), "abandoned")
            assertNull(store.findSource(source.archives))
            store.preserveSource(source)
            assertEquals(source, store.findSource(source.archives))
            assertNull(store.findSource(changed.archives))
            store.preserveSource(changed)
            store.preserveSource(source)
            store.verifySource(source)
            assertEquals(changed, store.findSource(changed.archives))
            assertFalse(Files.exists(directory.resolve("source-backup/first/syntax.xml")))
        }
        openStudioFiles(directory).use { files ->
            val store = StudioStoreIo(files)
            val sameId = changed.copy(id = source.id)
            val conflict = assertFailsWith<StoreDataException> { store.preserveSource(sameId) }
            assertEquals(StoreIssue.CONFLICT, conflict.issue)
            assertEquals(source, store.findSource(source.archives))
            files.writeVerified("source-backup/first/settings.xml", byteArrayOf(5), "conflict")
            assertEquals(StoreIssue.CONFLICT, assertFailsWith<StoreDataException> { store.verifySource(source) }.issue)
            assertContentEquals(byteArrayOf(5), files.read("source-backup/first/settings.xml"))
        }
    }

    @Test
    fun `a damaged older archive cannot stop reuse of an intact exact match`() {
        val old = source("a-old", byteArrayOf(6))
        val intact = source("b-intact", byteArrayOf(7))
        val damaged = byteArrayOf(8)
        openStudioFiles(directory).use { files ->
            val store = StudioStoreIo(files)
            store.preserveSource(old)
            store.preserveSource(intact)
            files.writeVerified("source-backup/a-old/settings.xml", damaged, "damage")

            assertEquals(intact, store.findSource(intact.archives))
            val unresolved = assertFailsWith<StoreDataException> { store.findSource(old.archives) }
            assertEquals(StoreIssue.CONFLICT, unresolved.issue)
            assertContentEquals(damaged, files.read("source-backup/a-old/settings.xml"))
        }
    }

    private fun nextJournal(
        first: PendingJournal,
        base: ByteArray,
    ): PendingJournal {
        val digest = assertIs<DecodeResult.Ready>(StudioStateCodec.decode(base)).digest
        val header = first.candidate.header.copy(revision = 1, transactionId = "next")
        return first.copy(
            token = PreparedToken("next", 0, digest, 1),
            base = StudioBytes(base),
            candidate = first.candidate.copy(header = header),
        )
    }

    private fun source(
        id: String,
        settings: ByteArray? = null,
    ): MigrationSource =
        MigrationSource(
            id,
            StudioList(
                LegacyStore.entries.map { store ->
                    val file =
                        if (store == LegacyStore.SETTINGS && settings != null) {
                            LegacyFile.Present(StudioBytes(settings))
                        } else {
                            LegacyFile.Absent
                        }
                    LegacyArchive(store, file)
                },
            ),
        )

    private fun firstJournal(): PendingJournal {
        val capture = assertIs<LegacyCapture.Ready>(StudioLegacyCapture.read(source("first"), "initial", "/work"))
        val first = assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(capture.snapshot)).document
        return PendingJournal(
            PreparedToken("initial", -1, null, 0),
            null,
            first,
            RecoveryPlan(StudioTarget.Global, StudioList(emptyList())),
            StudioList(emptyList()),
        )
    }
}
