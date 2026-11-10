package dev.ayuislands.studio

import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.EffectLimitation
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.LimitationReason
import dev.ayuislands.studio.model.SessionJournalEvent
import dev.ayuislands.studio.model.StudioApplyUpdate
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.openStudioFiles
import dev.ayuislands.studio.session.StudioApplyCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioApplyBoundaryTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `lease invalidation after the receipt acknowledgement prevents its setter`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                fixture.beforePublish = { update ->
                    if (update is StudioApplyUpdate.Journal && update.result == SessionJournalEvent.ReceiptSaved) {
                        fixture.isLeaseValid = false
                    }
                }

                assertIs<AcceptanceResult.NotAccepted>(fixture.coordinator.submit(fixture.request()))

                assertEquals(0, fixture.effect(EffectOwner.GLOW).writes)
                assertEquals(fixture.initial.currents, fixture.document().currents)
                assertIs<StoreLoad.Ready>(fixture.store.load())
            }
        }

    @Test
    fun `cancellation after the receipt acknowledgement prevents its setter`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                supervisorScope {
                    lateinit var job: Deferred<AcceptanceResult>
                    fixture.beforePublish = { update ->
                        if (update is StudioApplyUpdate.Journal && update.result == SessionJournalEvent.ReceiptSaved) {
                            job.cancel()
                        }
                    }
                    job = async { fixture.coordinator.submit(fixture.request()) }

                    assertFailsWith<CancellationException> { job.await() }

                    assertIs<AcceptanceResult.NotAccepted>(fixture.coordinator.outcome("accept"))
                    assertEquals(0, fixture.effect(EffectOwner.GLOW).writes)
                    assertEquals(fixture.initial.currents, fixture.document().currents)
                    assertIs<StoreLoad.Ready>(fixture.store.load())
                }
            }
        }

    @Test
    fun `failed commit notification retains accepted outcome and cleanup ownership`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                fixture.beforePublish = { update ->
                    if (update is StudioApplyUpdate.Committed) throw CancellationException("Closed notification host")
                }

                val result = fixture.coordinator.submit(fixture.request())

                assertIs<AcceptanceResult.AcceptedCleanupPending>(result)
                assertEquals(result, fixture.coordinator.outcome("accept"))
                assertEquals(1, fixture.effect(EffectOwner.GLOW).writes)
                assertTrue(fixture.events.none { it.startsWith("restore-") || it.startsWith("release-") })
                assertIs<StoreLoad.Recovering>(fixture.store.load())
                fixture.beforePublish = null
                assertIs<AcceptanceResult.Accepted>(fixture.coordinator.retryRecovery("accept"))
                assertEquals(1, fixture.effect(EffectOwner.GLOW).writes)
                assertEquals(1, fixture.effect(EffectOwner.GLOW).accepts)
                assertIs<StoreLoad.Ready>(fixture.store.load())
            }
        }

    @Test
    fun `invalid acquired lease is retained for cleanup without preparing a journal`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                fixture.beforeAcquire = { fixture.isLeaseValid = false }
                fixture.releaseFailure = true

                assertIs<AcceptanceResult.RecoveryRequired>(fixture.coordinator.submit(fixture.request()))

                assertIs<StoreLoad.Ready>(fixture.store.load())
                assertEquals(1, fixture.events.count { it == "release-old" })
                assertTrue(fixture.effects.all { it.writes == 0 && it.accepts == 0 })
                fixture.releaseFailure = false
                assertIs<AcceptanceResult.NotAccepted>(fixture.coordinator.retryRecovery("accept"))
                assertEquals(2, fixture.events.count { it == "release-old" })
                assertIs<StoreLoad.Ready>(fixture.store.load())
            }
        }

    @Test
    fun `failed preparation retains its runtime lease until release succeeds`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                fixture.beforeAcquire = {
                    fixture.files.arm(IoEvent(IoOperation.REPLACE, "pending.xml", IoBoundary.BEFORE))
                }
                fixture.releaseFailure = true
                assertIs<AcceptanceResult.RecoveryRequired>(fixture.coordinator.submit(fixture.request()))
                assertEquals(1, fixture.events.count { it == "release-old" })
                assertTrue(fixture.effects.all { it.writes == 0 })
                fixture.releaseFailure = false
                assertIs<AcceptanceResult.NotAccepted>(fixture.coordinator.retryRecovery("accept"))
                assertEquals(2, fixture.events.count { it == "release-old" })
                assertIs<StoreLoad.Ready>(fixture.store.load())
            }
        }

    @Test
    fun `unexpected preparation errors return typed failure without touching native surfaces`(): Unit =
        runBlocking {
            for (boundary in listOf("flush", "observe", "acquire")) {
                StudioApplyFixture(directory.resolve(boundary)).use { fixture ->
                    val fail: () -> Unit = { error("Injected $boundary failure") }
                    when (boundary) {
                        "flush" -> fixture.beforeFlush = fail
                        "observe" -> fixture.beforeObserve = fail
                        "acquire" -> fixture.beforeAcquire = fail
                    }
                    assertIs<AcceptanceResult.NotAccepted>(fixture.coordinator.submit(fixture.request()))
                    assertTrue(fixture.effects.all { it.writes == 0 && it.accepts == 0 })
                    assertEquals(fixture.initial.currents, fixture.document().currents)
                    assertIs<StoreLoad.Ready>(fixture.store.load())
                }
            }
        }

    @Test
    fun `runtime observation cannot commit after its lease becomes invalid`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                fixture.beforeRuntime = { fixture.isLeaseValid = false }
                assertIs<AcceptanceResult.NotAccepted>(fixture.coordinator.submit(fixture.request()))
                assertEquals(fixture.initial.currents, fixture.document().currents)
                assertEquals(0, fixture.effect(EffectOwner.GLOW).value)
                assertIs<StoreLoad.Ready>(fixture.store.load())
            }
        }

    @Test
    fun `accepted limitations survive journal reopen and cleanup retry`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val limit = EffectLimitation(EffectOwner.GLOW, LimitationReason.PRO_REQUIRED)
                val observed = sessionInputs(fixture.initial, sessionState(fixture.initial)).context
                fixture.context =
                    observed.copy(capabilities = observed.capabilities.copy(limitations = StudioList(listOf(limit))))
                fixture.releaseFailure = true
                assertIs<AcceptanceResult.AcceptedCleanupPending>(fixture.coordinator.submit(fixture.request()))
                fixture.store.close()
                fixture.releaseFailure = false
                StudioAppearanceStore(openStudioFiles(directory)).use { reopened ->
                    val coordinator = StudioApplyCoordinator(reopened, fixture, fixture.effects, fixture.mutex)
                    val result = assertIs<AcceptanceResult.Accepted>(coordinator.retryRecovery("accept"))
                    assertEquals(listOf(limit), result.limitations)
                    assertEquals(0, fixture.effect(EffectOwner.GLOW).writes)
                    assertIs<StoreLoad.Ready>(reopened.load())
                }
            }
        }

    @Test
    fun `cancellation propagates only after owned effects settle and typed outcome is available`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val glow = fixture.effect(EffectOwner.GLOW)
                glow.afterApply = { throw CancellationException("user cancelled") }
                assertFailsWith<CancellationException> { fixture.coordinator.submit(fixture.request()) }
                assertIs<AcceptanceResult.NotAccepted>(fixture.coordinator.outcome("accept"))
                assertEquals(0, glow.value)
                assertEquals(fixture.initial.currents, fixture.document().currents)
                assertIs<StoreLoad.Ready>(fixture.store.load())
            }
        }

    @Test
    fun `job cancellation after the durable decision cannot revert accepted intent`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                supervisorScope {
                    lateinit var job: Deferred<AcceptanceResult>
                    fixture.effect(EffectOwner.GLOW).afterAccept = { job.cancel() }
                    job = async { fixture.coordinator.submit(fixture.request()) }
                    assertFailsWith<CancellationException> { job.await() }
                    assertIs<AcceptanceResult.Accepted>(fixture.coordinator.outcome("accept"))
                    assertEquals(1, fixture.effect(EffectOwner.GLOW).value)
                    assertTrue(fixture.events.none { it.startsWith("restore-") })
                    assertIs<StoreLoad.Ready>(fixture.store.load())
                }
            }
        }

    @Test
    fun `failed prepare acknowledgement is settled before reporting NotAccepted`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                fixture.beforeFlush = {
                    fixture.files.armAt(IoEvent(IoOperation.REPLACE, "pending.xml", IoBoundary.AFTER), 3)
                }
                assertIs<AcceptanceResult.NotAccepted>(fixture.coordinator.submit(fixture.request()))
                assertEquals(0, fixture.effect(EffectOwner.GLOW).writes)
                assertIs<StoreLoad.Ready>(fixture.store.load())
                assertEquals(0, fixture.files.pendingFailures)
            }
        }

    @Test
    fun `unconfirmed commit never restores and retry resolves the exact accepted generation`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val glow = fixture.effect(EffectOwner.GLOW)
                glow.afterApply = {
                    fixture.files.arm(
                        IoEvent(IoOperation.REPLACE, "state.xml", IoBoundary.AFTER),
                        IoEvent(IoOperation.READ, "state.xml", IoBoundary.BEFORE),
                        IoEvent(IoOperation.READ, "state.xml", IoBoundary.BEFORE),
                    )
                }
                assertIs<AcceptanceResult.RecoveryRequired>(fixture.coordinator.submit(fixture.request()))
                assertTrue(fixture.events.none { it.startsWith("restore-") })
                assertEquals(1, glow.value)
                assertIs<AcceptanceResult.Accepted>(fixture.coordinator.retryRecovery("accept"))
                assertEquals(1, glow.writes)
                assertEquals(1, glow.accepts)
            }
        }

    @Test
    fun `failed write-ahead receipt prevents its setter`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val glow = fixture.effect(EffectOwner.GLOW)
                glow.beforePrepare = {
                    fixture.files.arm(IoEvent(IoOperation.REPLACE, "pending.xml", IoBoundary.BEFORE))
                }
                assertIs<AcceptanceResult.NotAccepted>(fixture.coordinator.submit(fixture.request()))
                assertEquals(0, glow.writes)
                assertEquals(0, glow.value)
                assertIs<StoreLoad.Ready>(fixture.store.load())
            }
        }

    @Test
    fun `rejected runtime lease preserves requested draft without preparing effects`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                fixture.canAcquire = false
                val request = fixture.request()
                assertIs<AcceptanceResult.NotAccepted>(fixture.coordinator.submit(request))
                assertEquals(request.session, fixture.document().sessions.single())
                assertEquals(fixture.initial.currents, fixture.document().currents)
                assertTrue(fixture.events.none { it.startsWith("prepare-") })
            }
        }
}
