package dev.ayuislands.studio

import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.StoreIssue
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.openStudioFiles
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StudioAppearanceStoreTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `prepare is not acceptance and committed state survives reopen and delayed cleanup`() {
        val candidate = document()
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            assertEquals(StoreLoad.Empty, store.load())
            assertEquals(JournalResult.Acknowledged, store.preserveSource(candidate.header.migration))
            val token = assertIs<PrepareResult.Prepared>(store.prepare(-1, candidate, recovery())).token
            assertIs<StoreLoad.Recovering>(store.load())
            val committed = assertIs<CommitResult.Committed>(store.commit(token, candidate.runtime))
            assertEquals(candidate, committed.document)
        }
        StudioAppearanceStore(openStudioFiles(directory)).use { reopened ->
            val recovering = assertIs<StoreLoad.Recovering>(reopened.load())
            assertEquals(candidate, recovering.document)
            assertEquals(JournalResult.Acknowledged, reopened.finishAccepted(recovering.journal.token))
            assertEquals(StoreLoad.Ready(candidate), reopened.load())
        }
    }

    @Test
    fun `exclusive owner and stale revision never replace accepted state`() {
        val candidate = document()
        StudioAppearanceStore(openStudioFiles(directory)).use { owner ->
            owner.load()
            owner.preserveSource(candidate.header.migration)
            val token = assertIs<PrepareResult.Prepared>(owner.prepare(-1, candidate, recovery())).token
            assertIs<CommitResult.Committed>(owner.commit(token, candidate.runtime))
            owner.finishAccepted(token)
            val bytes = Files.readAllBytes(directory.resolve("state.xml"))
            StudioAppearanceStore(openStudioFiles(directory)).use { contender ->
                assertEquals(StoreIssue.LOCKED, assertIs<StoreLoad.ReadOnly>(contender.load()).issue)
                assertIs<PrepareResult.Rejected>(contender.prepare(0, next(candidate), recovery()))
            }
            assertEquals(PrepareResult.Rejected(StoreIssue.STALE), owner.prepare(-1, next(candidate), recovery()))
            assertContentEquals(bytes, Files.readAllBytes(directory.resolve("state.xml")))
        }
    }

    private fun document(): StudioDocument =
        assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(StudioFixtures.load("unknown-and-ordered"))).document

    private fun next(document: StudioDocument): StudioDocument =
        document.copy(
            header = document.header.copy(revision = document.header.revision + 1, transactionId = "next-transaction"),
        )

    private fun recovery(): RecoveryPlan = RecoveryPlan(StudioTarget.Global, StudioList(emptyList()))
}
