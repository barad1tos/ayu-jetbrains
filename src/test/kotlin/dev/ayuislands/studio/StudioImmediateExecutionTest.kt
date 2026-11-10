package dev.ayuislands.studio

import dev.ayuislands.settings.freshSettingsState
import dev.ayuislands.studio.model.AcceptanceBlock
import dev.ayuislands.studio.model.AcceptanceOrigin
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.ProInitialization
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.session.ImmediateExecutionResult
import dev.ayuislands.studio.session.StudioImmediateExecution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioImmediateExecutionTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `accepted quick command retires the latest accepted generation before releasing its outcome`() =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val request = fixture.request().copy(origin = AcceptanceOrigin.QUICK_SWITCHER)
                val quick = StudioImmediateExecution(fixture.coordinator, fixture::finishSession)

                val result = assertIs<ImmediateExecutionResult.Complete>(quick.submit(request))

                assertIs<AcceptanceResult.Accepted>(result.acceptance)
                val record = fixture.document().sessions.single { it.id == request.session.id }
                assertEquals(SessionAvailability.RETIRED, record.availability)
                assertEquals(request.session.generation + 2, record.generation)
                assertEquals(
                    fixture
                        .document()
                        .currents.global.appearance,
                    record.work.drafts
                        .single()
                        .appearance,
                )
                assertEquals(1, fixture.effect(EffectOwner.GLOW).writes)
                assertNull(fixture.coordinator.outcome(request.transactionId))
            }
        }

    @Test
    fun `Settings edit after quick flush leaves exact recoverable work instead of a fake live editor`() =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val request = fixture.request().copy(origin = AcceptanceOrigin.QUICK_SWITCHER)
                val other = requireNotNull(editedSession(fixture.initial).record).copy(id = "settings")
                fixture.beforeObserve = {
                    fixture.context =
                        sessionInputs(fixture.document(), sessionState(fixture.document())).context.copy(
                            liveSessions = StudioList(listOf(other)),
                        )
                }
                val quick = StudioImmediateExecution(fixture.coordinator, fixture::finishSession)

                val result = assertIs<ImmediateExecutionResult.Complete>(quick.submit(request))

                assertIs<AcceptanceResult.ReviewRequired>(result.acceptance)
                val saved = fixture.document().sessions.single()
                assertEquals(SessionAvailability.RECOVERED, saved.availability)
                assertEquals(request.session.work, saved.work)
                assertEquals(fixture.initial.currents, fixture.document().currents)
                assertEquals(0, fixture.effect(EffectOwner.GLOW).writes)
            }
        }

    @Test
    fun `restored failure preserves the requested work for explicit recovery`() =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                fixture.effect(EffectOwner.GLOW).failAfter = true
                val request = fixture.request().copy(origin = AcceptanceOrigin.QUICK_SWITCHER)
                val quick = StudioImmediateExecution(fixture.coordinator, fixture::finishSession)

                val result = assertIs<ImmediateExecutionResult.Complete>(quick.submit(request))

                assertIs<AcceptanceResult.NotAccepted>(result.acceptance)
                val saved = fixture.document().sessions.single()
                assertEquals(SessionAvailability.RECOVERED, saved.availability)
                assertEquals(request.session.work, saved.work)
                assertEquals(fixture.initial.currents, fixture.document().currents)
                assertEquals(0, fixture.effect(EffectOwner.GLOW).value)
            }
        }

    @Test
    fun `accepted cleanup retry retires work without executing the command twice`() =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val glow = fixture.effect(EffectOwner.GLOW)
                glow.failAccept = true
                val request = fixture.request().copy(origin = AcceptanceOrigin.QUICK_SWITCHER)
                val quick = StudioImmediateExecution(fixture.coordinator, fixture::finishSession)

                val pending = assertIs<ImmediateExecutionResult.Pending>(quick.submit(request))
                assertIs<AcceptanceResult.AcceptedCleanupPending>(pending.acceptance)
                assertEquals(
                    SessionAvailability.LIVE,
                    fixture
                        .document()
                        .sessions
                        .single()
                        .availability,
                )
                assertIs<AcceptanceResult.AcceptedCleanupPending>(fixture.coordinator.outcome(request.transactionId))

                glow.failAccept = false
                val complete = assertIs<ImmediateExecutionResult.Complete>(quick.retry(request.transactionId))

                assertIs<AcceptanceResult.Accepted>(complete.acceptance)
                assertEquals(1, glow.writes)
                assertEquals(1, glow.accepts)
                assertEquals(
                    SessionAvailability.RETIRED,
                    fixture
                        .document()
                        .sessions
                        .single()
                        .availability,
                )
                assertNull(fixture.coordinator.outcome(request.transactionId))
            }
        }

    @Test
    fun `failed rollback keeps ownership until the existing operation can restore`() =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val glow = fixture.effect(EffectOwner.GLOW)
                glow.failAfter = true
                glow.failRestore = true
                val request = fixture.request().copy(origin = AcceptanceOrigin.QUICK_SWITCHER)
                val quick = StudioImmediateExecution(fixture.coordinator, fixture::finishSession)

                assertIs<ImmediateExecutionResult.Pending>(quick.submit(request))
                assertEquals(
                    SessionAvailability.LIVE,
                    fixture
                        .document()
                        .sessions
                        .single()
                        .availability,
                )
                glow.failRestore = false

                val complete = assertIs<ImmediateExecutionResult.Complete>(quick.retry(request.transactionId))

                assertIs<AcceptanceResult.NotAccepted>(complete.acceptance)
                assertEquals(1, glow.writes)
                assertEquals(0, glow.value)
                assertEquals(
                    SessionAvailability.RECOVERED,
                    fixture
                        .document()
                        .sessions
                        .single()
                        .availability,
                )
            }
        }

    @Test
    fun `failed disposition is retryable and cannot consume an accepted outcome early`() =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                var canFinish = false
                val request = fixture.request().copy(origin = AcceptanceOrigin.QUICK_SWITCHER)
                val quick =
                    StudioImmediateExecution(fixture.coordinator) { session, availability ->
                        if (canFinish) {
                            fixture.finishSession(
                                session,
                                availability,
                            )
                        } else {
                            DraftWriteResult.Failed("unconfirmed")
                        }
                    }

                val pending = assertIs<ImmediateExecutionResult.Pending>(quick.submit(request))
                assertIs<AcceptanceResult.Accepted>(pending.acceptance)
                assertIs<AcceptanceResult.Accepted>(fixture.coordinator.outcome(request.transactionId))
                assertEquals(
                    SessionAvailability.LIVE,
                    fixture
                        .document()
                        .sessions
                        .single()
                        .availability,
                )
                canFinish = true

                assertIs<ImmediateExecutionResult.Complete>(quick.retry(request.transactionId))

                assertEquals(1, fixture.effect(EffectOwner.GLOW).writes)
                assertEquals(
                    SessionAvailability.RETIRED,
                    fixture
                        .document()
                        .sessions
                        .single()
                        .availability,
                )
                assertNull(fixture.coordinator.outcome(request.transactionId))
            }
        }

    @Test
    fun `another gesture cannot replace an unresolved command`() =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                fixture.effect(EffectOwner.GLOW).failAccept = true
                val request = fixture.request().copy(origin = AcceptanceOrigin.QUICK_SWITCHER)
                val quick = StudioImmediateExecution(fixture.coordinator, fixture::finishSession)
                val original = assertIs<ImmediateExecutionResult.Pending>(quick.submit(request))

                val blocked =
                    assertIs<ImmediateExecutionResult.Pending>(quick.submit(request.copy(transactionId = "other")))

                assertEquals(original, blocked)
                assertEquals(1, fixture.flushes)
                assertEquals(1, fixture.effect(EffectOwner.GLOW).writes)
            }
        }

    @Test
    fun `cancellation after a native write preserves unaccepted work after proven rollback`() =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val glow = fixture.effect(EffectOwner.GLOW)
                glow.afterApply = { throw CancellationException("cancel during effect") }
                val request = fixture.request().copy(origin = AcceptanceOrigin.QUICK_SWITCHER)
                val quick = StudioImmediateExecution(fixture.coordinator, fixture::finishSession)

                assertFailsWith<CancellationException> { quick.submit(request) }

                assertEquals(0, glow.value)
                assertEquals(fixture.initial.currents, fixture.document().currents)
                assertEquals(
                    SessionAvailability.RECOVERED,
                    fixture
                        .document()
                        .sessions
                        .single()
                        .availability,
                )
                assertEquals(
                    request.session.work,
                    fixture
                        .document()
                        .sessions
                        .single()
                        .work,
                )
                assertNull(fixture.coordinator.outcome(request.transactionId))
            }
        }

    @Test
    fun `unchanged command leaves no live record and writes no native effect`() =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val request =
                    fixture.request().copy(
                        origin = AcceptanceOrigin.QUICK_SWITCHER,
                        session = sessionRecord(fixture.initial),
                    )
                val quick = StudioImmediateExecution(fixture.coordinator, fixture::finishSession)

                val complete = assertIs<ImmediateExecutionResult.Complete>(quick.submit(request))

                assertEquals(AcceptanceResult.NoChanges, complete.acceptance)
                assertEquals(fixture.initial.currents, fixture.document().currents)
                assertEquals(
                    SessionAvailability.RETIRED,
                    fixture
                        .document()
                        .sessions
                        .single()
                        .availability,
                )
                assertEquals(0, fixture.effect(EffectOwner.GLOW).writes)
            }
        }

    @Test
    fun `explicit Pro initialization shares retained cleanup without replaying imported preferences`() =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val request =
                    fixture.request().copy(
                        origin = AcceptanceOrigin.INITIALIZATION,
                        session = sessionRecord(fixture.initial),
                        initialization =
                            ProInitialization(
                                fixture.initial,
                                StudioFixtures.fields(freshSettingsState()),
                            ),
                    )
                fixture.beforeObserve = {
                    fixture.files.arm(IoEvent(IoOperation.REMOVE, "pending.xml", IoBoundary.BEFORE))
                }
                val immediate = StudioImmediateExecution(fixture.coordinator, fixture::finishSession)

                assertIs<ImmediateExecutionResult.Pending>(immediate.submit(request))
                val complete = assertIs<ImmediateExecutionResult.Complete>(immediate.retryPending())
                assertNull(immediate.retryPending())

                assertIs<AcceptanceResult.Accepted>(complete.acceptance)
                val accepted = fixture.document()
                assertEquals(fixture.initial.currents, accepted.currents)
                assertEquals(fixture.initial.shared, accepted.shared)
                assertEquals(RawValue.BooleanValue(true), accepted.runtime.legacy.value("everBeenPro"))
                assertEquals(RawValue.BooleanValue(true), accepted.auxiliary.legacyMarkers.value("proDefaultsApplied"))
                assertEquals(
                    RawValue.BooleanValue(true),
                    accepted.auxiliary.legacyMarkers.value("workspaceDefaultsApplied"),
                )
                assertEquals(SessionAvailability.RETIRED, accepted.sessions.single().availability)
                assertTrue(fixture.effects.all { it.writes == 0 })
                assertNull(fixture.coordinator.outcome(request.transactionId))
            }
        }

    @Test
    fun `immediate owner rejects Settings missing initialization and dirty initialization requests`() =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val ordinary = fixture.request()
                val initialization = ProInitialization(fixture.initial, StudioFixtures.fields(freshSettingsState()))
                val invalid =
                    listOf(
                        ordinary,
                        ordinary.copy(
                            origin = AcceptanceOrigin.INITIALIZATION,
                            session = sessionRecord(fixture.initial),
                        ),
                        ordinary.copy(origin = AcceptanceOrigin.INITIALIZATION, initialization = initialization),
                        ordinary.copy(origin = AcceptanceOrigin.QUICK_SWITCHER, initialization = initialization),
                    )
                val immediate = StudioImmediateExecution(fixture.coordinator, fixture::finishSession)

                for (request in invalid) {
                    val complete = assertIs<ImmediateExecutionResult.Complete>(immediate.submit(request))
                    val blocked = assertIs<AcceptanceResult.Blocked>(complete.acceptance)
                    assertEquals(listOf(AcceptanceBlock.INVALID_REQUEST), blocked.reasons)
                }

                assertEquals(fixture.initial, fixture.document())
                assertEquals(0, fixture.flushes)
                assertTrue(fixture.effects.all { it.writes == 0 })
            }
        }
}
