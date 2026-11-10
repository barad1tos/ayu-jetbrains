package dev.ayuislands.studio

import dev.ayuislands.studio.model.DraftRestore
import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.TargetObservation
import dev.ayuislands.studio.model.TargetPresence
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class StudioDraftRestoreStoreTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `restore persists exact recovered work and its source retirement in one revision`() {
        withStore { store, writer, initial, _ ->
            val source =
                requireNotNull(
                    editedSession(initial).record,
                ).copy(availability = SessionAvailability.RECOVERED)
            assertIs<DraftWriteResult.Saved>(writer.save(source, "save-source"))
            val before = assertIs<StoreLoad.Ready>(store.load()).document
            val request =
                DraftRestore(
                    source.id,
                    "new-settings",
                    "restore",
                    TargetObservation(source.target, 19, TargetPresence.ACTIVE),
                )

            assertEquals(DraftWriteResult.Saved(before.header.revision + 1), writer.restore(request, emptySet()))

            val actual = assertIs<StoreLoad.Ready>(store.load()).document
            assertEquals(
                listOf(
                    source.copy(generation = source.generation + 1, availability = SessionAvailability.RETIRED),
                    source.copy(id = request.session, generation = 0, availability = SessionAvailability.LIVE),
                ),
                actual.sessions,
            )
            assertEquals(before, actual.copy(header = before.header, sessions = before.sessions))
            assertFalse(Files.exists(directory.resolve("pending.xml")))
        }
    }

    @Test
    fun `restore refuses an attached source and unavailable captured target without changing inventory`() {
        withStore { store, writer, initial, _ ->
            val source = requireNotNull(editedSession(initial).record)
            assertIs<DraftWriteResult.Saved>(writer.save(source, "save-source"))
            val before = store.load()
            val request =
                DraftRestore(
                    source.id,
                    "new-settings",
                    "restore",
                    TargetObservation(source.target, 3, TargetPresence.ACTIVE),
                )

            assertEquals(DraftWriteResult.Failed("draft-conflict"), writer.restore(request, setOf(source.id)))
            assertEquals(
                DraftWriteResult.Failed("draft-target_unavailable"),
                writer.restore(
                    request.copy(target = request.target.copy(presence = TargetPresence.UNAVAILABLE)),
                    emptySet(),
                ),
            )

            assertEquals(before, store.load())
        }
    }

    @Test
    fun `failed restore does not retire its source or create a replacement`() {
        withStore { store, writer, initial, files ->
            val source = requireNotNull(editedSession(initial).record)
            assertIs<DraftWriteResult.Saved>(writer.save(source, "save-source"))
            val before = store.load()
            val request =
                DraftRestore(
                    source.id,
                    "new-settings",
                    "restore",
                    TargetObservation(source.target, 3, TargetPresence.ACTIVE),
                )
            files.arm(IoEvent(IoOperation.REPLACE, "state.xml", IoBoundary.BEFORE))

            assertIs<DraftWriteResult.Failed>(writer.restore(request, emptySet()))

            assertEquals(before, store.load())
            assertFalse(Files.exists(directory.resolve("pending.xml")))
        }
    }

    @Test
    fun `unknown restore retries its original transfer without a second session or revision`() {
        withStore { store, writer, initial, files ->
            val source = requireNotNull(editedSession(initial).record)
            assertIs<DraftWriteResult.Saved>(writer.save(source, "save-source"))
            val before = assertIs<StoreLoad.Ready>(store.load()).document
            val request =
                DraftRestore(
                    source.id,
                    "new-settings",
                    "restore",
                    TargetObservation(source.target, 3, TargetPresence.ACTIVE),
                )
            files.arm(
                IoEvent(IoOperation.REPLACE, "state.xml", IoBoundary.AFTER),
                IoEvent(IoOperation.READ, "state.xml", IoBoundary.BEFORE),
            )
            assertEquals(DraftWriteResult.Failed("save-unconfirmed"), writer.restore(request, emptySet()))

            assertEquals(DraftWriteResult.Saved(before.header.revision + 1), writer.restore(request, emptySet()))

            val actual = assertIs<StoreLoad.Ready>(store.load()).document
            assertEquals(listOf(source.id, request.session), actual.sessions.map { it.id })
            assertEquals(source.generation + 1, actual.sessions.first().generation)
            assertEquals(source.work, actual.sessions.last().work)
            assertEquals(request.transaction, actual.header.transactionId)
            assertFalse(Files.exists(directory.resolve("pending.xml")))
        }
    }

    @Test
    fun `explicit discard retains exact work and order as a retirement watermark`() {
        withStore { store, writer, initial, _ ->
            val source = requireNotNull(editedSession(initial).record)
            val other = source.copy(id = "other")
            assertIs<DraftWriteResult.Saved>(writer.save(source, "save-source"))
            assertIs<DraftWriteResult.Saved>(writer.save(other, "save-other"))
            val before = assertIs<StoreLoad.Ready>(store.load()).document

            assertIs<DraftWriteResult.Saved>(writer.discard(source.id, "discard", emptySet()))

            val actual = assertIs<StoreLoad.Ready>(store.load()).document
            assertEquals(
                listOf(
                    source.copy(generation = source.generation + 1, availability = SessionAvailability.RETIRED),
                    other,
                ),
                actual.sessions,
            )
            assertEquals(before, actual.copy(header = before.header, sessions = before.sessions))
            assertEquals(DraftWriteResult.Saved(actual.header.revision), writer.discard(source.id, "retry", emptySet()))
            assertEquals(actual, assertIs<StoreLoad.Ready>(store.load()).document)
        }
    }

    @Test
    fun `discard cannot retire the current record of an attached or detached registered binding`() {
        withStore { store, writer, initial, _ ->
            val source = requireNotNull(editedSession(initial).record)
            assertIs<DraftWriteResult.Saved>(writer.save(source, "save-source"))
            val before = store.load()

            assertEquals(
                DraftWriteResult.Failed("draft-conflict"),
                writer.discard(source.id, "discard", setOf(source.id)),
            )

            assertEquals(before, store.load())
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
