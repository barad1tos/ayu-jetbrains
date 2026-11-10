package dev.ayuislands.studio

import dev.ayuislands.studio.migration.LegacySnapshot
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.StoreIssue
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioFiles
import dev.ayuislands.studio.persistence.openStudioFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioBootstrapTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `initial import accepts captured values once without an appearance operation`() =
        runIo {
            val capture = StudioFixtures.load("mixed-user-values")
            var captures = 0
            StudioAppearanceStore(openStudioFiles(directory)).use { store ->
                val bootstrap =
                    bootstrap(store) {
                        captures++
                        capture
                    }
                val ready = assertIs<StudioBootstrapResult.Ready>(bootstrap.load())

                assertEquals(document(capture), ready.document)
                assertEquals(StoreLoad.Ready(ready.document), store.load())
                assertFalse(Files.exists(directory.resolve("pending.xml")))
                assertEquals(ready, bootstrap.load())
                assertEquals(1, captures)
            }
        }

    @Test
    fun `accepted Studio authority never reads changed legacy files again`() =
        runIo {
            StudioAppearanceStore(openStudioFiles(directory)).use { store ->
                val accepted = assertIs<StudioBootstrapResult.Ready>(bootstrap(store).load()).document
                val reopened = bootstrap(store) { error("Accepted authority must not capture legacy state") }

                assertEquals(StudioBootstrapResult.Ready(accepted), reopened.load())
            }
        }

    @Test
    fun `unreadable authority preserves bytes and never falls through to fresh import`() {
        val corrupt = "damaged accepted document".encodeToByteArray()
        Files.write(directory.resolve("state.xml"), corrupt)
        var captures = 0
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val result =
                runIo {
                    bootstrap(store) {
                        captures++
                        StudioFixtures.capture(fresh = true)
                    }.load()
                }

            assertIs<StudioBootstrapResult.ReadOnly>(result)
            assertEquals(0, captures)
            assertContentEquals(corrupt, Files.readAllBytes(directory.resolve("state.xml")))
            assertFalse(Files.exists(directory.resolve("source-backup")))
        }
    }

    @Test
    fun `another process owning the store prevents capture and mutation`() =
        runIo {
            openStudioFiles(directory).use {
                var captures = 0
                StudioAppearanceStore(openStudioFiles(directory)).use { contender ->
                    val result =
                        bootstrap(contender) {
                            captures++
                            StudioFixtures.capture(fresh = true)
                        }.load()

                    assertEquals(StoreIssue.LOCKED, assertIs<StudioBootstrapResult.ReadOnly>(result).state.issue)
                    assertEquals(0, captures)
                    assertFalse(Files.exists(directory.resolve("state.xml")))
                }
            }
        }

    @Test
    fun `failed source read is typed read only and never becomes absent defaults`() =
        runIo {
            StudioAppearanceStore(openStudioFiles(directory)).use { store ->
                val result = bootstrap(store) { throw IOException("Legacy source unavailable") }.load()

                assertEquals(StoreIssue.IO, assertIs<StudioBootstrapResult.ReadOnly>(result).state.issue)
                assertFalse(Files.exists(directory.resolve("state.xml")))
                assertFalse(Files.exists(directory.resolve("pending.xml")))
                assertFalse(Files.exists(directory.resolve("source-backup")))
            }
        }

    @Test
    fun `incomplete capture cannot initialize a partially populated authority`() =
        runIo {
            val capture = StudioFixtures.capture()
            val incomplete =
                capture.copy(fields = capture.fields.copy(records = StudioList(capture.fields.records.drop(1))))
            StudioAppearanceStore(openStudioFiles(directory)).use { store ->
                val result = bootstrap(store) { incomplete }.load()

                assertEquals(StoreIssue.INVALID_CANDIDATE, assertIs<StudioBootstrapResult.ReadOnly>(result).state.issue)
                assertFalse(Files.exists(directory.resolve("state.xml")))
                assertFalse(Files.exists(directory.resolve("pending.xml")))
            }
        }

    @Test
    fun `failed initial commit settles rollback and retry keeps the archived migration identity`() =
        runIo {
            val capture = StudioFixtures.load("unknown-and-ordered")
            val files = FaultingStudioFiles(openStudioFiles(directory))
            files.arm(IoEvent(IoOperation.REPLACE, "state.xml", IoBoundary.BEFORE))
            StudioAppearanceStore(files).use { store ->
                val failed = assertIs<StudioBootstrapResult.LegacyReadOnlyRecovery>(bootstrap(store) { capture }.load())

                assertEquals(document(capture), failed.document)
                assertEquals(StoreLoad.Empty, store.load())
                assertFalse(Files.exists(directory.resolve("pending.xml")))
                assertTrue(Files.exists(directory.resolve("source-backup/${capture.source.id}/manifest.xml")))
            }
            StudioAppearanceStore(openStudioFiles(directory)).use { reopened ->
                val retried = capture.copy(transactionId = "retried-import")
                val accepted = assertIs<StudioBootstrapResult.Ready>(bootstrap(reopened) { retried }.load()).document

                assertEquals(capture.source, accepted.header.migration)
                assertEquals(document(capture).catalog, accepted.catalog)
                assertEquals(document(capture).currents, accepted.currents)
            }
        }

    @Test
    fun `unknown initial decision admits nothing until durable readback proves acceptance`() =
        runIo {
            val files = UnreadableDecision(openStudioFiles(directory))
            val capture = StudioFixtures.load("mixed-user-values")
            var captures = 0
            StudioAppearanceStore(files).use { store ->
                val bootstrap =
                    bootstrap(store) {
                        captures++
                        capture
                    }
                assertIs<StudioBootstrapResult.Recovering>(bootstrap.load())
                assertTrue(Files.exists(directory.resolve("pending.xml")))
                files.isBlocked = false

                val ready = assertIs<StudioBootstrapResult.Ready>(bootstrap.load())

                assertEquals(document(capture), ready.document)
                assertEquals(1, captures)
                assertFalse(Files.exists(directory.resolve("pending.xml")))
            }
        }

    @Test
    fun `accepted cleanup failure preserves accepted intent and keeps producers gated`() =
        runIo {
            val files = FaultingStudioFiles(openStudioFiles(directory))
            files.arm(IoEvent(IoOperation.REMOVE, "pending.xml", IoBoundary.BEFORE))
            StudioAppearanceStore(files).use { store ->
                val bootstrap = bootstrap(store)
                assertIs<StudioBootstrapResult.Recovering>(bootstrap.load())
                val pending = assertIs<StoreLoad.Recovering>(store.load())

                assertEquals(pending.journal.candidate, pending.document)
                val ready = assertIs<StudioBootstrapResult.Ready>(bootstrap.load())
                assertEquals(pending.document, ready.document)
            }
        }

    @Test
    fun `existing native recovery stays with the coordinator and no source is recaptured`() {
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val accepted = assertIs<StudioBootstrapResult.Ready>(runIo { bootstrap(store).load() }).document
            val candidate =
                accepted.copy(
                    header = accepted.header.copy(revision = 1, transactionId = "native-pending"),
                )
            assertIs<PrepareResult.Prepared>(
                store.prepare(
                    0,
                    candidate,
                    RecoveryPlan(StudioTarget.Global, StudioList(listOf(EffectOwner.ACCENT))),
                ),
            )
            val bytes = Files.readAllBytes(directory.resolve("pending.xml"))

            val result = runIo { bootstrap(store) { error("Recovery must not import legacy state") }.load() }

            assertIs<StudioBootstrapResult.Recovering>(result)
            assertContentEquals(bytes, Files.readAllBytes(directory.resolve("pending.xml")))
            assertEquals(accepted, assertIs<StoreLoad.Recovering>(store.load()).document)
        }
    }

    @Test
    fun `empty owner journals after initial import are not mistaken for migration`() =
        runIo {
            StudioAppearanceStore(openStudioFiles(directory)).use { store ->
                val accepted = assertIs<StudioBootstrapResult.Ready>(bootstrap(store).load()).document
                val candidate =
                    accepted.copy(
                        header = accepted.header.copy(revision = 1, transactionId = "draft-pending"),
                    )
                val token =
                    assertIs<PrepareResult.Prepared>(
                        store.prepare(0, candidate, RecoveryPlan(StudioTarget.Global, StudioList(emptyList()))),
                    ).token

                assertIs<StudioBootstrapResult.Recovering>(bootstrap(store).load())
                assertEquals(JournalResult.Acknowledged, store.finishRollback(token))
            }
        }

    private fun <T> runIo(
        context: CoroutineContext = Dispatchers.IO,
        action: suspend CoroutineScope.() -> T,
    ): T = runBlocking(context, action)

    private fun bootstrap(
        store: StudioAppearanceStore,
        capture: suspend () -> LegacySnapshot = { StudioFixtures.load("mixed-user-values") },
    ) = StudioBootstrap(store, capture, Mutex(), EmptyCoroutineContext)

    private fun document(capture: LegacySnapshot): StudioDocument =
        assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(capture)).document

    private class UnreadableDecision(
        private val delegate: StudioFiles,
    ) : StudioFiles by delegate {
        var isBlocked = false
        private var isArmed = true

        override fun replace(
            source: String,
            destination: String,
        ) {
            delegate.replace(source, destination)
            if (destination == "state.xml" && isArmed) {
                isArmed = false
                isBlocked = true
                throw IOException("Lost accepted replacement acknowledgement")
            }
        }

        override fun read(name: String): ByteArray? {
            if (name == "state.xml" && isBlocked) throw IOException("Decision readback unavailable")
            return delegate.read(name)
        }
    }
}
