package dev.ayuislands.studio

import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.CommitResolution
import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioFiles
import dev.ayuislands.studio.persistence.StudioStateCodec
import dev.ayuislands.studio.persistence.openStudioFiles
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioStoreFailureTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `read only recovery rejects archive writes and keeps corrupt authority intact`() {
        val corrupt = "not a valid state".encodeToByteArray()
        Files.write(directory.resolve("state.xml"), corrupt)
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            assertIs<StoreLoad.ReadOnly>(store.load())
            assertIs<JournalResult.Rejected>(store.preserveSource(document().header.migration))
            assertContentEquals(corrupt, Files.readAllBytes(directory.resolve("state.xml")))
            assertTrue(!Files.exists(directory.resolve("source-backup")))
        }
    }

    @Test
    fun `lost commit acknowledgement and failed read remain unknown until explicit resolution`() {
        val files = FaultingStudioFiles(openStudioFiles(directory))
        StudioAppearanceStore(files).use { store ->
            val candidate = document()
            store.load()
            store.preserveSource(candidate.header.migration)
            val token = assertIs<PrepareResult.Prepared>(store.prepare(-1, candidate, recovery())).token
            files.arm(
                IoEvent(IoOperation.REPLACE, "state.xml", IoBoundary.AFTER),
                IoEvent(IoOperation.READ, "state.xml", IoBoundary.BEFORE),
            )
            assertIs<CommitResult.OutcomeUnknown>(store.commit(token, candidate.runtime))
            assertEquals(0, files.pendingFailures)
            assertEquals(CommitResolution.Committed, store.resolveOutcome(token))
            assertIs<JournalResult.Rejected>(store.finishRollback(token))
            files.arm(IoEvent(IoOperation.REMOVE, "pending.xml", IoBoundary.BEFORE))
            assertIs<JournalResult.Rejected>(store.finishAccepted(token))
            assertEquals(candidate, assertIs<StoreLoad.Recovering>(store.load()).document)
            assertEquals(JournalResult.Acknowledged, store.finishAccepted(token))
            assertEquals(StoreLoad.Ready(candidate), store.load())
        }
    }

    @Test
    fun `failed discard acknowledgement can be retried without throwing or losing source`() {
        val files = FaultingStudioFiles(openStudioFiles(directory))
        StudioAppearanceStore(files).use { store ->
            val candidate = document()
            store.load()
            store.preserveSource(candidate.header.migration)
            val token = assertIs<PrepareResult.Prepared>(store.prepare(-1, candidate, recovery())).token
            files.arm(IoEvent(IoOperation.REMOVE, "pending.xml", IoBoundary.AFTER))
            assertIs<JournalResult.Rejected>(store.finishRollback(token))
            files.arm(IoEvent(IoOperation.READ, "pending.xml", IoBoundary.BEFORE))
            assertIs<JournalResult.Rejected>(store.finishRollback(token))
            assertEquals(JournalResult.Acknowledged, store.finishRollback(token))
            val retry = candidate.copy(header = candidate.header.copy(transactionId = "retried"))
            val prepared = assertIs<PrepareResult.Prepared>(store.prepare(-1, retry, recovery())).token
            assertEquals(JournalResult.Acknowledged, store.finishRollback(prepared))
        }
    }

    @Test
    fun `accepted cleanup retry makes the next request available without a manual reload`() {
        val files = FaultingStudioFiles(openStudioFiles(directory))
        StudioAppearanceStore(files).use { store ->
            val candidate = document()
            store.load()
            store.preserveSource(candidate.header.migration)
            val token = assertIs<PrepareResult.Prepared>(store.prepare(-1, candidate, recovery())).token
            assertIs<CommitResult.Committed>(store.commit(token, candidate.runtime))
            files.arm(IoEvent(IoOperation.REMOVE, "pending.xml", IoBoundary.AFTER))
            assertIs<JournalResult.Rejected>(store.finishAccepted(token))
            assertEquals(JournalResult.Acknowledged, store.finishAccepted(token))
            val next = candidate.copy(header = candidate.header.copy(revision = 1, transactionId = "next"))
            assertIs<PrepareResult.Prepared>(store.prepare(0, next, recovery()))
        }
    }

    @Test
    fun `final acknowledgement verifies payload digest as well as transaction and revision`() {
        val candidate = document()
        val foreignHeader = candidate.header.copy(migration = candidate.header.migration.copy(id = "foreign"))
        val foreign = StudioStateCodec.encode(candidate.copy(header = foreignHeader))
        val actual = openStudioFiles(directory)
        var acceptedReads = 0
        val changedReadback =
            object : StudioFiles by actual {
                override fun read(name: String): ByteArray? {
                    val bytes = actual.read(name)
                    if (name != "state.xml" || bytes == null) return bytes
                    acceptedReads++
                    return if (acceptedReads == 3) foreign else bytes
                }
            }
        StudioAppearanceStore(changedReadback).use { store ->
            store.load()
            store.preserveSource(candidate.header.migration)
            val token = assertIs<PrepareResult.Prepared>(store.prepare(-1, candidate, recovery())).token
            assertIs<CommitResult.OutcomeUnknown>(store.commit(token, candidate.runtime))
            assertEquals(CommitResolution.Committed, store.resolveOutcome(token))
        }
    }

    private fun document(): StudioDocument =
        assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(StudioFixtures.load("unknown-and-ordered"))).document

    private fun recovery(): RecoveryPlan = RecoveryPlan(StudioTarget.Global, StudioList(emptyList()))
}
