package dev.ayuislands.studio

import dev.ayuislands.studio.model.DraftRestore
import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.TargetObservation
import dev.ayuislands.studio.model.TargetPresence
import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioDraftStore
import dev.ayuislands.studio.persistence.StudioFiles
import dev.ayuislands.studio.persistence.openStudioFiles
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StudioDraftStoreTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `real store saves and retires only its session while preserving accepted data across reopen`() {
        val document = catalogTestDocument()
        val record = editedJournalRecord(document)
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            initialize(store, document)
            val journal = StudioDraftStore(store)
            assertIs<DraftWriteResult.Saved>(journal.save(record, "draft-1"))
            val retired = record.copy(generation = 2, availability = SessionAvailability.RETIRED)
            assertIs<DraftWriteResult.Saved>(journal.save(retired, "retire-1"))
            assertIs<DraftWriteResult.Failed>(journal.save(record, "late-save"))
        }
        StudioAppearanceStore(openStudioFiles(directory)).use { reopened ->
            val latest = assertIs<StoreLoad.Ready>(reopened.load()).document
            assertEquals(SessionAvailability.RETIRED, latest.sessions.single().availability)
            assertEquals(record.work, latest.sessions.single().work)
            assertEquals(document, latest.copy(header = document.header, sessions = document.sessions))
        }
    }

    @Test
    fun `committed draft is acknowledged despite cleanup failure and next write settles the old journal first`() {
        val document = catalogTestDocument()
        val record = editedJournalRecord(document)
        val files = FaultingStudioFiles(openStudioFiles(directory))
        StudioAppearanceStore(files).use { store ->
            initialize(store, document)
            files.arm(IoEvent(IoOperation.REMOVE, "pending.xml", IoBoundary.BEFORE))
            val journal = StudioDraftStore(store)
            assertIs<DraftWriteResult.Saved>(journal.save(record, "first-draft"))
            val pending = assertIs<StoreLoad.Recovering>(store.load())
            assertEquals(record, pending.document?.sessions?.single())
            val newer = record.copy(generation = 2)
            assertIs<DraftWriteResult.Saved>(journal.save(newer, "second-draft"))
            assertEquals(newer, assertIs<StoreLoad.Ready>(store.load()).document.sessions.single())
        }
    }

    @Test
    fun `unconfirmed write retains the pending record until retry resolves its durable outcome`() {
        val document = catalogTestDocument()
        val record = editedJournalRecord(document)
        val files = FaultingStudioFiles(openStudioFiles(directory))
        StudioAppearanceStore(files).use { store ->
            initialize(store, document)
            files.arm(
                IoEvent(IoOperation.REPLACE, "state.xml", IoBoundary.AFTER),
                IoEvent(IoOperation.READ, "state.xml", IoBoundary.BEFORE),
            )
            val journal = StudioDraftStore(store)
            assertIs<DraftWriteResult.Failed>(journal.save(record, "uncertain"))
            assertIs<DraftWriteResult.Saved>(journal.save(record, "retry"))
            val latest = assertIs<StoreLoad.Ready>(store.load()).document
            assertEquals(record, latest.sessions.single())
            assertEquals(document.currents, latest.currents)
            assertEquals(document.runtime, latest.runtime)
        }
    }

    @Test
    fun `retry of an unconfirmed Restore finds the single durable restored draft`() {
        val document = catalogTestDocument()
        val orphan = editedJournalRecord(document)
        val files = FaultingStudioFiles(openStudioFiles(directory))
        StudioAppearanceStore(files).use { store ->
            initialize(store, document)
            val journal = StudioDraftStore(store)
            assertIs<DraftWriteResult.Saved>(journal.save(orphan, "orphan"))
            val request =
                DraftRestore(
                    orphan.id,
                    "restored",
                    "restore",
                    TargetObservation(orphan.target, 1, TargetPresence.ACTIVE),
                )
            files.arm(
                IoEvent(IoOperation.REPLACE, "state.xml", IoBoundary.AFTER),
                IoEvent(IoOperation.READ, "state.xml", IoBoundary.BEFORE),
            )
            assertEquals(DraftWriteResult.Failed("save-unconfirmed"), journal.restore(request, emptySet()))
            val restored = assertIs<DraftWriteResult.Saved>(journal.restore(request, emptySet()))
            val latest = assertIs<StoreLoad.Ready>(store.load()).document
            assertEquals(restored.revision, latest.header.revision)
            assertEquals(
                listOf(SessionAvailability.RETIRED, SessionAvailability.LIVE),
                latest.sessions.map { it.availability },
            )
            assertEquals(listOf(orphan.id, request.session), latest.sessions.map { it.id })
            assertEquals(orphan.work, latest.sessions.last().work)
        }
    }

    @Test
    fun `autosave cannot settle another operation with native effect owners`() {
        val document = catalogTestDocument()
        val record = editedJournalRecord(document)
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            initialize(store, document)
            val candidate = document.copy(header = document.header.copy(revision = 1, transactionId = "outer"))
            val recovery = RecoveryPlan(StudioTarget.Global, StudioList(listOf(EffectOwner.GLOW)))
            val prepared = assertIs<PrepareResult.Prepared>(store.prepare(0, candidate, recovery))
            assertIs<DraftWriteResult.Failed>(StudioDraftStore(store).save(record, "draft"))
            assertEquals(prepared.token, assertIs<StoreLoad.Recovering>(store.load()).journal.token)
        }
    }

    @Test
    fun `unknown metadata operation stays with its application owner even with no native surfaces`() {
        val document = catalogTestDocument()
        val record = editedJournalRecord(document)
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            initialize(store, document)
            val candidate = document.copy(header = document.header.copy(revision = 1, transactionId = "catalog"))
            val recovery = RecoveryPlan(StudioTarget.Global, StudioList(emptyList()))
            val prepared = assertIs<PrepareResult.Prepared>(store.prepare(0, candidate, recovery))
            assertIs<CommitResult.Committed>(store.commit(prepared.token, candidate.runtime))
            assertIs<DraftWriteResult.Failed>(StudioDraftStore(store).save(record, "draft"))
            assertEquals(prepared.token, assertIs<StoreLoad.Recovering>(store.load()).journal.token)
        }
    }

    @Test
    fun `a failed prepare cannot claim different work with the same transaction ID`() {
        val document = catalogTestDocument()
        val record = editedJournalRecord(document)
        val files = FaultingStudioFiles(openStudioFiles(directory))
        StudioAppearanceStore(files).use { store ->
            initialize(store, document)
            files.arm(IoEvent(IoOperation.REPLACE, "pending.xml", IoBoundary.BEFORE))
            val journal = StudioDraftStore(store)
            assertEquals(DraftWriteResult.Failed("prepare-io"), journal.save(record, "reused"))
            assertEquals(StoreLoad.Ready(document), store.load())
            val metadata = document.copy(header = document.header.copy(revision = 1, transactionId = "reused"))
            val recovery = RecoveryPlan(StudioTarget.Global, StudioList(emptyList()))
            val prepared = assertIs<PrepareResult.Prepared>(store.prepare(0, metadata, recovery))

            assertEquals(DraftWriteResult.Failed("recovery-required"), journal.save(record, "retry"))
            assertEquals(prepared.token, assertIs<StoreLoad.Recovering>(store.load()).journal.token)
        }
    }

    @Test
    fun `a prepare published before its acknowledgement can be retried by its writer`() {
        val document = catalogTestDocument()
        val record = editedJournalRecord(document)
        val files = FaultingStudioFiles(openStudioFiles(directory))
        StudioAppearanceStore(files).use { store ->
            initialize(store, document)
            files.arm(IoEvent(IoOperation.REPLACE, "pending.xml", IoBoundary.AFTER))
            val journal = StudioDraftStore(store)
            assertEquals(DraftWriteResult.Failed("prepare-io"), journal.save(record, "pending"))
            assertIs<StoreLoad.Recovering>(store.load())

            assertIs<DraftWriteResult.Saved>(journal.save(record, "retry"))
            assertEquals(record, assertIs<StoreLoad.Ready>(store.load()).document.sessions.single())
        }
    }

    private fun initialize(
        store: StudioAppearanceStore,
        document: StudioDocument,
    ) {
        assertEquals(StoreLoad.Empty, store.load())
        assertEquals(JournalResult.Acknowledged, store.preserveSource(document.header.migration))
        val token =
            assertIs<PrepareResult.Prepared>(
                store.prepare(-1, document, RecoveryPlan(StudioTarget.Global, StudioList(emptyList()))),
            ).token
        assertIs<CommitResult.Committed>(store.commit(token, document.runtime))
        assertEquals(JournalResult.Acknowledged, store.finishAccepted(token))
    }
}

private enum class IoOperation { READ, REPLACE, REMOVE }

private enum class IoBoundary { BEFORE, AFTER }

private data class IoEvent(
    val operation: IoOperation,
    val name: String,
    val boundary: IoBoundary,
)

/** Wrap real temporary files and fail only the specified operation, in order. */
private class FaultingStudioFiles(
    private val actual: StudioFiles,
) : StudioFiles by actual {
    private val failures = ArrayDeque<IoEvent>()

    fun arm(vararg events: IoEvent) {
        check(failures.isEmpty())
        failures.addAll(events)
    }

    override fun read(name: String): ByteArray? = around(IoOperation.READ, name) { actual.read(name) }

    override fun replace(
        source: String,
        destination: String,
    ) = around(IoOperation.REPLACE, destination) { actual.replace(source, destination) }

    override fun remove(name: String) = around(IoOperation.REMOVE, name) { actual.remove(name) }

    private fun <T> around(
        operation: IoOperation,
        name: String,
        action: () -> T,
    ): T {
        failIfArmed(IoEvent(operation, name, IoBoundary.BEFORE))
        val result = action()
        failIfArmed(IoEvent(operation, name, IoBoundary.AFTER))
        return result
    }

    private fun failIfArmed(event: IoEvent) {
        if (event == failures.firstOrNull()) {
            failures.removeFirst()
            throw IOException("Injected ${event.operation} failure for ${event.name}")
        }
    }
}
