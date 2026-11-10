package dev.ayuislands.integration

import dev.ayuislands.studio.StoreCrashPoint
import dev.ayuislands.studio.StudioFixtures
import dev.ayuislands.studio.StudioStoreProbe
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.CommitResolution
import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioStateCodec
import dev.ayuislands.studio.persistence.openStudioFiles
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioPersistenceRestartTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `kill after prepare retains unaccepted candidate and supports explicit retry`() =
        qualify(StoreCrashPoint.PREPARED)

    @Test
    fun `kill at atomic replacement resolves accepted state without rollback`() = qualify(StoreCrashPoint.DECISION)

    @Test
    fun `kill during cleanup keeps accepted state after journal deletion`() = qualify(StoreCrashPoint.CLEANUP)

    private fun qualify(point: StoreCrashPoint) {
        val candidate =
            assertIs<MigrationResult.Ready>(
                StudioLegacyMigration.plan(StudioFixtures.load("unknown-and-ordered")),
            ).document
        val input = directory.resolve("candidate.xml")
        Files.write(input, StudioStateCodec.encode(candidate))
        val root = directory.resolve("store")
        killAtMarker(root, input, point)
        StudioAppearanceStore(openStudioFiles(root)).use { reopened ->
            when (point) {
                StoreCrashPoint.PREPARED -> {
                    val loaded = assertIs<StoreLoad.Recovering>(reopened.load())
                    assertNull(loaded.document)
                    assertEquals(candidate, loaded.journal.candidate)
                    assertEquals(CommitResolution.NotCommitted, reopened.resolveOutcome(loaded.journal.token))
                    assertEquals(JournalResult.Acknowledged, reopened.finishRollback(loaded.journal.token))
                    val recovery = RecoveryPlan(StudioTarget.Global, StudioList(emptyList()))
                    val retried = assertIs<PrepareResult.Prepared>(reopened.prepare(-1, candidate, recovery)).token
                    assertIs<CommitResult.Committed>(reopened.commit(retried, candidate.runtime))
                    assertEquals(JournalResult.Acknowledged, reopened.finishAccepted(retried))
                }
                StoreCrashPoint.DECISION -> {
                    val loaded = assertIs<StoreLoad.Recovering>(reopened.load())
                    assertEquals(candidate, loaded.document)
                    assertEquals(CommitResolution.Committed, reopened.resolveOutcome(loaded.journal.token))
                    assertIs<JournalResult.Rejected>(reopened.finishRollback(loaded.journal.token))
                    assertEquals(JournalResult.Acknowledged, reopened.finishAccepted(loaded.journal.token))
                }
                StoreCrashPoint.CLEANUP -> assertEquals(StoreLoad.Ready(candidate), reopened.load())
            }
            assertEquals(StoreLoad.Ready(candidate), reopened.load())
        }
    }

    private fun killAtMarker(
        root: Path,
        input: Path,
        point: StoreCrashPoint,
    ) {
        val runtime = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = requireNotNull(System.getProperty("java.class.path"))
        val errors = directory.resolve("child-errors.log")
        val process =
            ProcessBuilder(
                runtime,
                "-cp",
                classpath,
                StudioStoreProbe::class.java.name,
                root.toString(),
                input.toString(),
                point.name,
            ).redirectError(errors.toFile()).start()
        Executors.newSingleThreadExecutor().use { executor ->
            try {
                val marker = executor.submit<String?> { process.inputStream.bufferedReader().readLine() }
                assertEquals("READY:$point", marker.get(20, TimeUnit.SECONDS), Files.readString(errors))
                process.destroyForcibly()
                assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Child did not terminate")
            } finally {
                if (process.isAlive) process.destroyForcibly().waitFor()
            }
        }
    }
}
