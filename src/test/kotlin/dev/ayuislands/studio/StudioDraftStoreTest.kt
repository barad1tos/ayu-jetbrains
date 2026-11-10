package dev.ayuislands.studio

import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioDraftStore
import dev.ayuislands.studio.persistence.openStudioFiles
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StudioDraftStoreTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `real store saves and retires only its session while preserving accepted data across reopen`() {
        val document = sessionDocument()
        val record = requireNotNull(editedSession(document).record)
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
        val document = sessionDocument()
        val record = requireNotNull(editedSession(document).record)
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
        val document = sessionDocument()
        val record = requireNotNull(editedSession(document).record)
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
    fun `autosave cannot settle another operation with native effect owners`() {
        val document = sessionDocument()
        val record = requireNotNull(editedSession(document).record)
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
        val document = sessionDocument()
        val record = requireNotNull(editedSession(document).record)
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
