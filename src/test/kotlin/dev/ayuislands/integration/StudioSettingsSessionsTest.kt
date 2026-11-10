package dev.ayuislands.integration

import com.intellij.openapi.application.EDT
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.studio.IoBoundary
import dev.ayuislands.studio.IoEvent
import dev.ayuislands.studio.IoOperation
import dev.ayuislands.studio.StudioApplyFixture
import dev.ayuislands.studio.editedSession
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.StudioApplyUpdate
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.TargetObservation
import dev.ayuislands.studio.model.TargetPresence
import dev.ayuislands.studio.session.StudioApplyCoordinator
import dev.ayuislands.studio.session.StudioApplyEnvironment
import dev.ayuislands.studio.session.StudioDraftChoice
import dev.ayuislands.studio.session.StudioSessionDispatchers
import dev.ayuislands.studio.session.StudioSessions
import dev.ayuislands.studio.session.StudioSettingsOpening
import dev.ayuislands.studio.session.StudioSettingsSessions
import dev.ayuislands.studio.sessionInputs
import dev.ayuislands.studio.sessionRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioSettingsSessionsTest {
    @get:Rule val applicationRule = ApplicationRule()

    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `new opening loads exact accepted appearance and captured generation without selecting an orphan`() =
        withSessions { fixture, sessions, opening ->
            val orphan = requireNotNull(editedSession(fixture.initial).record).copy(id = "orphan")
            assertIs<DraftWriteResult.Saved>(sessions.saveRecord(orphan))
            val before = sessions.document
            val observation = TargetObservation(StudioTarget.Global, 41, TargetPresence.ACTIVE)

            val binding = assertIs<StudioSettingsOpening.Ready>(opening.open(observation)).binding

            withContext(Dispatchers.EDT) {
                val state = binding.controller.state
                val current = before.currents.global
                val draft = requireNotNull(state.record).work.drafts.single()
                assertEquals(SessionPhase.Editing, state.phase)
                assertEquals(observation.target, state.identity.target)
                assertEquals(observation.generation, state.identity.targetGeneration)
                assertEquals(current, draft.base)
                assertEquals(current.appearance, draft.appearance)
                assertEquals(current.source, draft.source)
                assertEquals(before.catalog.singleOrNull { it.id == current.source?.id }?.name, draft.sourceName)
                assertSame(sessions.document, binding.document)
            }
            assertEquals(before, sessions.document)
            assertTrue(fixture.effects.all { it.writes == 0 })
        }

    @Test
    fun `explicit recovery loads the selected source only after atomic publication`() =
        withSessions { fixture, sessions, opening ->
            val first = requireNotNull(editedSession(fixture.initial).record).copy(id = "first")
            val chosen = first.copy(id = "chosen", generation = 7, availability = SessionAvailability.RECOVERED)
            assertIs<DraftWriteResult.Saved>(sessions.saveRecord(first))
            assertIs<DraftWriteResult.Saved>(sessions.saveRecord(chosen))
            val observation = TargetObservation(chosen.target, 17, TargetPresence.INACTIVE)

            val binding =
                assertIs<StudioSettingsOpening.Ready>(
                    opening.open(observation, restoreSource = chosen.id),
                ).binding

            withContext(Dispatchers.EDT) {
                val restored = requireNotNull(binding.controller.state.record)
                assertEquals(chosen.work, restored.work)
                assertEquals(0, restored.generation)
                assertEquals(SessionAvailability.LIVE, restored.availability)
                assertEquals(17, binding.controller.state.identity.targetGeneration)
                assertEquals(restored, sessions.document.sessions.last())
                assertEquals(listOf(first.id, chosen.id, restored.id), sessions.document.sessions.map { it.id })
                assertEquals(first, sessions.document.sessions.first())
                assertEquals(
                    chosen.copy(generation = 8, availability = SessionAvailability.RETIRED),
                    sessions.document.sessions[1],
                )
            }
            assertEquals(fixture.initial.currents, sessions.document.currents)
            assertTrue(fixture.effects.all { it.writes == 0 })
        }

    @Test
    fun `detaching the native view does not offer or steal its registered session`() =
        withSessions { _, sessions, opening ->
            val observation = TargetObservation(StudioTarget.Global, 3, TargetPresence.ACTIVE)
            val binding = assertIs<StudioSettingsOpening.Ready>(opening.open(observation)).binding
            val retained =
                withContext(Dispatchers.EDT) {
                    binding.controller.detach()
                    requireNotNull(binding.controller.state.record)
                }
            assertIs<DraftWriteResult.Saved>(sessions.saveRecord(retained))

            assertTrue(withContext(Dispatchers.EDT) { sessions.recoveryOffers() }.isEmpty())
            assertIs<StudioSettingsOpening.Unavailable>(opening.open(observation, restoreSource = retained.id))

            assertEquals(listOf(retained), sessions.document.sessions)
        }

    @Test
    fun `failed recovery leaves source recoverable and never publishes an opening candidate`() =
        withSessions { fixture, sessions, opening ->
            val source =
                requireNotNull(
                    editedSession(fixture.initial).record,
                ).copy(availability = SessionAvailability.RECOVERED)
            assertIs<DraftWriteResult.Saved>(sessions.saveRecord(source))
            val before = sessions.document
            fixture.files.arm(IoEvent(IoOperation.REPLACE, "state.xml", IoBoundary.BEFORE))

            assertIs<StudioSettingsOpening.Unavailable>(
                opening.open(TargetObservation(source.target, 3, TargetPresence.ACTIVE), restoreSource = source.id),
            )

            assertEquals(before, sessions.document)
            assertEquals(listOf(source), withContext(Dispatchers.EDT) { sessions.recoveryOffers() })
            assertTrue(fixture.effects.all { it.writes == 0 })
        }

    @Test
    fun `retry after unknown recovery proves the same transfer instead of creating a second opening`() =
        withSessions { fixture, sessions, opening ->
            val source = requireNotNull(editedSession(fixture.initial).record)
            assertIs<DraftWriteResult.Saved>(sessions.saveRecord(source))
            val before = sessions.document
            val target = TargetObservation(source.target, 5, TargetPresence.ACTIVE)
            fixture.files.arm(
                IoEvent(IoOperation.REPLACE, "state.xml", IoBoundary.AFTER),
                IoEvent(IoOperation.READ, "state.xml", IoBoundary.BEFORE),
            )
            assertIs<StudioSettingsOpening.Unavailable>(opening.open(target, restoreSource = source.id))
            assertEquals(before, sessions.document)

            val binding = assertIs<StudioSettingsOpening.Ready>(opening.open(target)).binding

            withContext(Dispatchers.EDT) {
                val restored = requireNotNull(binding.controller.state.record)
                assertEquals(source.work, restored.work)
                assertEquals(listOf(source.id, restored.id), sessions.document.sessions.map { it.id })
                assertEquals(before.header.revision + 1, sessions.document.header.revision)
            }
            assertTrue(fixture.effects.all { it.writes == 0 })
        }

    private fun withSessions(
        ioContext: CoroutineContext = Dispatchers.IO,
        action: suspend (StudioApplyFixture, StudioSessions, StudioSettingsSessions) -> Unit,
    ): Unit =
        runBlocking {
            val owner = SupervisorJob()
            val scope = CoroutineScope(owner + ioContext)
            val fixture = withContext(ioContext) { StudioApplyFixture(temporary.newFolder().toPath()) }
            val serial = AtomicInteger()
            val ids = { "opening-${serial.incrementAndGet()}" }
            val sessions =
                StudioSessions(
                    fixture.initial,
                    fixture.store,
                    scope,
                    fixture.mutex,
                    context = { state, document ->
                        sessionInputs(document, state.copy(record = state.record ?: sessionRecord(document))).context
                    },
                    coordinator = { session ->
                        val environment =
                            object : StudioApplyEnvironment by fixture {
                                override suspend fun flush(record: StudioSessionRecord) = session.flush(record)

                                override suspend fun publish(update: StudioApplyUpdate) = session.publish(update)

                                override suspend fun observe(
                                    request: AcceptanceRequest,
                                    document: StudioDocument,
                                ) = session.observe(request, document)
                            }
                        StudioApplyCoordinator(fixture.store, environment, fixture.effects, fixture.mutex)
                    },
                    ids = ids,
                    dispatchers = StudioSessionDispatchers(io = ioContext),
                )
            val opening =
                StudioSettingsSessions(
                    sessions,
                    resource = { _, _ -> error("No resource action requested") },
                    ids = ids,
                    chooseRecovery = { StudioDraftChoice.KeepForLater },
                )
            try {
                action(fixture, sessions, opening)
            } finally {
                owner.cancelAndJoin()
                withContext(ioContext) { fixture.close() }
            }
        }
}
