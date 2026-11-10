package dev.ayuislands.studio

import dev.ayuislands.studio.model.AcceptanceBlock
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.AuthorityStatus
import dev.ayuislands.studio.model.CloseRequest
import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.OperationStage
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionCommand
import dev.ayuislands.studio.model.SessionEvent
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SessionNotice
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.StudioApplyUpdate
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.session.StudioApplyCoordinator
import dev.ayuislands.studio.session.StudioApplyEnvironment
import dev.ayuislands.studio.session.StudioSessionController
import dev.ayuislands.studio.session.StudioSessionProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioSessionApplyTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `rejected native lease leaves the draft editable rather than stuck preparing`(): Unit =
        runBlocking {
            SessionApplyFixture(directory, this).use { fixture ->
                fixture.native.canAcquire = false
                val draft = fixture.controller.state.record
                assertIs<AcceptanceResult.NotAccepted>(fixture.apply("first"))
                assertEquals(SessionPhase.Editing, fixture.controller.state.phase)
                assertEquals(draft, fixture.controller.state.record)
                assertTrue(fixture.notices.none { it is SessionNotice.Invariant })
            }
        }

    @Test
    fun `cleanup retry preserves a newer draft and completes before its next Apply`(): Unit =
        runBlocking {
            SessionApplyFixture(directory, this).use { fixture ->
                val glow = fixture.native.effect(EffectOwner.GLOW)
                glow.failAccept = true
                assertIs<AcceptanceResult.AcceptedCleanupPending>(fixture.apply("first"))
                assertEquals(
                    OperationStage.ACCEPTED_CLEANUP,
                    assertIs<SessionPhase.Processing>(fixture.controller.state.phase).stage,
                )
                fixture.editGlow()
                val draft = fixture.controller.state.record
                glow.failAccept = false
                assertIs<AcceptanceResult.Accepted>(fixture.coordinator.retryRecovery("first"))
                assertEquals(draft, fixture.controller.state.record)
                assertEquals(SessionPhase.Editing, fixture.controller.state.phase)
                assertEquals(1, glow.writes)
                assertIs<AcceptanceResult.Accepted>(fixture.apply("second"))
                assertEquals(2, glow.writes)
                assertTrue(fixture.notices.none { it is SessionNotice.Invariant })
            }
        }

    @Test
    fun `changed final preflight preserves structured blocker and performs no native writes`(): Unit =
        runBlocking {
            SessionApplyFixture(directory, this).use { fixture ->
                fixture.native.beforeObserve = {
                    val observed = sessionInputs(fixture.native.document(), fixture.controller.state).context
                    fixture.native.context = observed.copy(authority = AuthorityStatus.READ_ONLY)
                }
                val result = assertIs<AcceptanceResult.Blocked>(fixture.apply("first"))
                assertEquals(listOf(AcceptanceBlock.READ_ONLY), result.reasons)
                assertEquals(SessionPhase.Editing, fixture.controller.state.phase)
                assertEquals(SessionNotice.Blocked(result.reasons), fixture.controller.state.notice)
                assertTrue(fixture.native.effects.all { it.writes == 0 })
            }
        }

    @Test
    fun `Cancel after commit retires only the draft against the newly accepted baseline`(): Unit =
        runBlocking {
            SessionApplyFixture(directory, this).use { fixture ->
                fixture.native.effect(EffectOwner.GLOW).afterAccept = {
                    fixture.controller.dispatch(SessionEvent.HostClose(CloseRequest.CANCEL))
                }
                assertIs<AcceptanceResult.Accepted>(fixture.apply("first"))
                fixture.awaitWrites()
                assertEquals(SessionPhase.Retired, fixture.controller.state.phase)
                val record = requireNotNull(fixture.controller.state.record)
                assertEquals(
                    fixture.native
                        .document()
                        .currents.global,
                    record.work.drafts
                        .single()
                        .base,
                )
                assertEquals(1, fixture.native.effect(EffectOwner.GLOW).value)
                assertTrue(fixture.native.events.none { it.startsWith("restore-") })
            }
        }

    @Test
    fun `accepted appearance reaches the same controller baseline after durable commit`(): Unit =
        runBlocking {
            SessionApplyFixture(directory, this).use { fixture ->
                assertIs<AcceptanceResult.Accepted>(fixture.apply("first"))
                assertEquals(SessionPhase.Editing, fixture.controller.state.phase)
                assertEquals(
                    fixture.native
                        .document()
                        .sessions
                        .single(),
                    fixture.controller.state.record,
                )
                assertTrue(fixture.notices.none { it is SessionNotice.Invariant })
            }
        }

    @Test
    fun `failed native effect restores surfaces and returns its unchanged draft to editing`(): Unit =
        runBlocking {
            SessionApplyFixture(directory, this).use { fixture ->
                val draft = fixture.controller.state.record
                fixture.native.effect(EffectOwner.GLOW).failAfter = true
                assertIs<AcceptanceResult.NotAccepted>(fixture.apply("first"))
                assertEquals(SessionPhase.Editing, fixture.controller.state.phase)
                assertEquals(draft, fixture.controller.state.record)
                assertEquals(0, fixture.native.effect(EffectOwner.GLOW).value)
                assertIs<SessionNotice.Failure>(fixture.controller.state.notice)
                assertTrue(fixture.notices.none { it is SessionNotice.Invariant })
            }
        }
}

internal class SessionApplyFixture(
    directory: Path,
    scope: CoroutineScope,
) : AutoCloseable {
    val native = StudioApplyFixture(directory)
    private var latest = native.initial
    private val commands = ArrayDeque<SessionCommand>()
    private val writes = mutableListOf<Job>()
    val notices = mutableListOf<SessionNotice?>()
    val controller: StudioSessionController
    val coordinator: StudioApplyCoordinator

    init {
        controller =
            StudioSessionController(
                editedSession(latest),
                { sessionInputs(latest, controller.state) },
                ManualSessionScheduler(),
                { record, completion ->
                    writes.add(
                        scope.launch {
                            val result = native.flush(record)
                            latest = native.document()
                            completion(result)
                        },
                    )
                },
                commands::addLast,
            )
        controller.attach { notices.add(it.notice) }
        val progress = StudioSessionProgress(controller) { latest = it }
        val environment =
            object : StudioApplyEnvironment by native {
                override suspend fun flush(record: StudioSessionRecord): DraftWriteResult =
                    suspendCancellableCoroutine { continuation -> controller.flush(record, continuation::resume) }

                override suspend fun publish(update: StudioApplyUpdate) {
                    native.publish(update)
                    progress.receive(update)
                }
            }
        coordinator = StudioApplyCoordinator(native.store, environment, native.effects, native.mutex)
    }

    suspend fun apply(operationId: String): AcceptanceResult {
        controller.dispatch(SessionEvent.AcceptRequested(operationId))
        val command = assertIs<SessionCommand.Submit>(commands.removeFirst())
        return coordinator.submit(command.request)
    }

    fun editGlow() {
        val record = requireNotNull(controller.state.record)
        val draft = record.work.drafts.single { it.id == record.work.selected }
        val value = draft.appearance.glow.value("glowEnabled") as RawValue.BooleanValue
        controller.dispatch(
            SessionEvent.Intent(
                SessionIntent.EditField(
                    draft.id,
                    FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "glowEnabled")),
                    FieldValue.Present(RawValue.BooleanValue(!value.value)),
                ),
            ),
        )
    }

    suspend fun awaitWrites() {
        while (writes.any { !it.isCompleted }) writes.toList().joinAll()
    }

    override fun close() = native.close()
}
