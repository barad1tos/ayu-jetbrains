package dev.ayuislands.studio

import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioDraftStore
import dev.ayuislands.studio.persistence.openStudioFiles
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class StudioDraftFinishTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `finishing uses the latest accepted generation while preserving full work and session order`() {
        withStore { store, writer, initial, _ ->
            val first = sessionRecord(initial).copy(id = "first")
            val latest = requireNotNull(editedSession(initial).record).copy(id = "quick", generation = 9)
            assertIs<DraftWriteResult.Saved>(writer.save(first, "first-save"))
            assertIs<DraftWriteResult.Saved>(writer.save(latest, "quick-save"))
            val before = assertIs<StoreLoad.Ready>(store.load()).document

            assertIs<DraftWriteResult.Saved>(writer.finish("quick", SessionAvailability.RETIRED, "finish"))

            val actual = assertIs<StoreLoad.Ready>(store.load()).document
            assertEquals(
                listOf(first, latest.copy(generation = 10, availability = SessionAvailability.RETIRED)),
                actual.sessions,
            )
            assertEquals(before, actual.copy(header = before.header, sessions = before.sessions))
        }
    }

    @Test
    fun `failed initial save leaves no orphan record for disposition`() {
        withStore { store, writer, initial, files ->
            val record = requireNotNull(editedSession(initial).record)
            files.arm(IoEvent(IoOperation.REPLACE, "state.xml", IoBoundary.BEFORE))
            assertIs<DraftWriteResult.Failed>(writer.save(record, "failed-save"))

            assertEquals(
                DraftWriteResult.Saved(initial.header.revision),
                writer.finish(record.id, SessionAvailability.RECOVERED, "finish"),
            )

            assertEquals(StoreLoad.Ready(initial), store.load())
            assertFalse(Files.exists(directory.resolve("pending.xml")))
        }
    }

    @Test
    fun `unknown initial save is settled by its same writer before preserving recoverable work`() {
        withStore { store, writer, initial, files ->
            val record = requireNotNull(editedSession(initial).record)
            files.arm(
                IoEvent(IoOperation.REPLACE, "state.xml", IoBoundary.AFTER),
                IoEvent(IoOperation.READ, "state.xml", IoBoundary.BEFORE),
            )
            assertEquals(DraftWriteResult.Failed("save-unconfirmed"), writer.save(record, "unknown-save"))

            assertIs<DraftWriteResult.Saved>(writer.finish(record.id, SessionAvailability.RECOVERED, "finish"))

            val actual = assertIs<StoreLoad.Ready>(store.load()).document
            assertEquals(
                record.copy(generation = 2, availability = SessionAvailability.RECOVERED),
                actual.sessions.single(),
            )
            assertEquals(initial.currents, actual.currents)
            assertFalse(Files.exists(directory.resolve("pending.xml")))
        }
    }

    @Test
    fun `unknown finish retry proves existing terminal record without incrementing generation twice`() {
        withStore { store, writer, initial, files ->
            val record = requireNotNull(editedSession(initial).record)
            assertIs<DraftWriteResult.Saved>(writer.save(record, "save"))
            files.arm(
                IoEvent(IoOperation.REPLACE, "state.xml", IoBoundary.AFTER),
                IoEvent(IoOperation.READ, "state.xml", IoBoundary.BEFORE),
            )
            assertEquals(
                DraftWriteResult.Failed("save-unconfirmed"),
                writer.finish(record.id, SessionAvailability.RETIRED, "uncertain-finish"),
            )

            assertIs<DraftWriteResult.Saved>(writer.finish(record.id, SessionAvailability.RETIRED, "retry-finish"))

            val actual = assertIs<StoreLoad.Ready>(store.load()).document
            assertEquals(
                record.copy(generation = 2, availability = SessionAvailability.RETIRED),
                actual.sessions.single(),
            )
            assertEquals("uncertain-finish", actual.header.transactionId)
        }
    }

    @Test
    fun `disposition cannot settle another owners pending journal even without native fields`() {
        withStore { store, writer, initial, _ ->
            val record = sessionRecord(initial)
            assertIs<DraftWriteResult.Saved>(writer.save(record, "save"))
            val before = assertIs<StoreLoad.Ready>(store.load()).document
            val candidate =
                before.copy(
                    header = before.header.copy(revision = before.header.revision + 1, transactionId = "other"),
                )
            assertIs<PrepareResult.Prepared>(
                store.prepare(
                    before.header.revision,
                    candidate,
                    RecoveryPlan(StudioTarget.Global, StudioList(emptyList())),
                ),
            )
            val pending = Files.readAllBytes(directory.resolve("pending.xml"))

            assertEquals(
                DraftWriteResult.Failed("recovery-required"),
                writer.finish(record.id, SessionAvailability.RETIRED, "finish"),
            )

            assertContentEquals(pending, Files.readAllBytes(directory.resolve("pending.xml")))
            assertEquals(before, assertIs<StoreLoad.Recovering>(store.load()).document)
        }
    }

    @Test
    fun `native recovery journal remains owned by the acceptance coordinator`() {
        withStore { store, writer, initial, _ ->
            val candidate = initial.copy(header = initial.header.copy(revision = 1, transactionId = "native"))
            assertIs<PrepareResult.Prepared>(
                store.prepare(0, candidate, RecoveryPlan(StudioTarget.Global, StudioList(listOf(EffectOwner.GLOW)))),
            )
            val pending = Files.readAllBytes(directory.resolve("pending.xml"))

            assertEquals(
                DraftWriteResult.Failed("recovery-required"),
                writer.finish("absent", SessionAvailability.RETIRED, "finish"),
            )

            assertContentEquals(pending, Files.readAllBytes(directory.resolve("pending.xml")))
            assertEquals(initial, assertIs<StoreLoad.Recovering>(store.load()).document)
        }
    }

    private fun withStore(
        action: (StudioAppearanceStore, StudioDraftStore, StudioDocument, FaultingStudioFiles) -> Unit,
    ) {
        val files = FaultingStudioFiles(openStudioFiles(directory))
        StudioAppearanceStore(files).use { store ->
            val initial =
                runBlocking {
                    assertIs<StudioBootstrapResult.Ready>(
                        StudioBootstrap(store, { StudioFixtures.capture() }, Mutex()).load(),
                    ).document
                }
            action(store, StudioDraftStore(store), initial, files)
        }
    }
}
