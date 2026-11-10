package dev.ayuislands.studio

import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.persistence.PendingJournal
import dev.ayuislands.studio.session.StudioAcceptancePreparation
import dev.ayuislands.studio.session.StudioApplyCoordinator
import dev.ayuislands.studio.session.StudioApplyEnvironment
import dev.ayuislands.studio.session.StudioRuntimeLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class StudioApplyConcurrencyTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `cancellation during lease dispatch retains acquired resources until cleanup succeeds`(): Unit =
        runBlocking {
            withTimeout(5.seconds) {
                for (failRelease in listOf(false, true)) {
                    StudioApplyFixture(directory.resolve(failRelease.toString())).use { fixture ->
                        fixture.releaseFailure = failRelease
                        lateinit var attempt: Deferred<AcceptanceResult>
                        val environment = DispatchedLeaseEnvironment(fixture) { attempt.cancel() }
                        val coordinator =
                            StudioApplyCoordinator(fixture.store, environment, fixture.effects, fixture.mutex)
                        attempt = async(start = CoroutineStart.LAZY) { coordinator.submit(fixture.request()) }
                        attempt.start()

                        assertFailsWith<CancellationException> { attempt.await() }

                        assertEquals(1, fixture.events.count { it == "release-old" })
                        assertTrue(fixture.effects.all { it.writes == 0 && it.accepts == 0 })
                        if (failRelease) {
                            assertIs<AcceptanceResult.RecoveryRequired>(coordinator.outcome("accept"))
                            fixture.releaseFailure = false
                            assertIs<AcceptanceResult.NotAccepted>(coordinator.retryRecovery("accept"))
                            assertEquals(2, fixture.events.count { it == "release-old" })
                        } else {
                            assertIs<AcceptanceResult.NotAccepted>(coordinator.outcome("accept"))
                        }
                    }
                }
            }
        }

    @Test
    fun `cancellation while resuming an interrupted operation retains its cleanup lease`(): Unit =
        runBlocking {
            withTimeout(5.seconds) {
                StudioApplyFixture(directory).use { fixture ->
                    val glow = fixture.effect(EffectOwner.GLOW)
                    glow.failAfter = true
                    glow.failRestore = true
                    assertIs<AcceptanceResult.RecoveryRequired>(fixture.coordinator.submit(fixture.request()))
                    glow.failRestore = false
                    fixture.releaseFailure = true
                    lateinit var attempt: Deferred<AcceptanceResult>
                    val environment = DispatchedLeaseEnvironment(fixture) { attempt.cancel() }
                    val coordinator = StudioApplyCoordinator(fixture.store, environment, fixture.effects, fixture.mutex)
                    attempt = async(start = CoroutineStart.LAZY) { coordinator.submit(fixture.request("next")) }
                    attempt.start()

                    assertFailsWith<CancellationException> { attempt.await() }

                    assertEquals(1, fixture.events.count { it == "release-old" })
                    assertIs<AcceptanceResult.RecoveryRequired>(coordinator.outcome("accept"))
                    fixture.releaseFailure = false
                    assertIs<AcceptanceResult.NotAccepted>(coordinator.retryRecovery("accept"))
                    assertEquals(2, fixture.events.count { it == "release-old" })
                    assertEquals(1, glow.writes)
                    assertEquals(0, glow.value)
                }
            }
        }

    @Test
    fun `duplicate caller cancellation cannot publish a false outcome for the admitted operation`(): Unit =
        runBlocking {
            withTimeout(5.seconds) {
                StudioApplyFixture(directory).use { fixture ->
                    val entered = CompletableDeferred<Unit>()
                    val proceed = CompletableDeferred<Unit>()
                    val environment =
                        object : StudioApplyEnvironment by fixture {
                            override suspend fun flush(record: StudioSessionRecord): DraftWriteResult {
                                entered.complete(Unit)
                                proceed.await()
                                return fixture.flush(record)
                            }
                        }
                    val coordinator = StudioApplyCoordinator(fixture.store, environment, fixture.effects, fixture.mutex)
                    val request = fixture.request()
                    val first = async { coordinator.submit(request) }
                    entered.await()
                    val cancelled = async(start = CoroutineStart.UNDISPATCHED) { coordinator.submit(request) }
                    cancelled.cancelAndJoin()
                    assertNull(coordinator.outcome(request.transactionId))
                    val duplicate = async { coordinator.submit(request) }
                    proceed.complete(Unit)
                    val accepted = assertIs<AcceptanceResult.Accepted>(first.await())
                    assertEquals(accepted, duplicate.await())
                    assertEquals(1, fixture.flushes)
                    assertEquals(1, fixture.effect(EffectOwner.GLOW).writes)
                }
            }
        }

    @Test
    fun `host acknowledgement releases terminal results but retains cleanup ownership`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val glow = fixture.effect(EffectOwner.GLOW)
                glow.failAccept = true
                assertIs<AcceptanceResult.AcceptedCleanupPending>(fixture.coordinator.submit(fixture.request()))
                assertFalse(fixture.coordinator.acknowledge("accept"))
                assertIs<AcceptanceResult.AcceptedCleanupPending>(fixture.coordinator.outcome("accept"))
                glow.failAccept = false
                assertIs<AcceptanceResult.Accepted>(fixture.coordinator.retryRecovery("accept"))
                assertTrue(fixture.coordinator.acknowledge("accept"))
                assertNull(fixture.coordinator.outcome("accept"))
                assertFalse(fixture.coordinator.acknowledge("accept"))
                assertEquals(1, glow.writes)
            }
        }
}

private class DispatchedLeaseEnvironment(
    private val delegate: StudioApplyEnvironment,
    private val nativeContext: CoroutineContext = Dispatchers.Default,
    private val afterLease: () -> Unit,
) : StudioApplyEnvironment by delegate {
    override suspend fun prepareAcceptance(
        request: AcceptanceRequest,
        plan: AcceptancePlan.Commit,
    ): StudioAcceptancePreparation =
        withContext(nativeContext) {
            delegate.prepareAcceptance(request, plan).also { afterLease() }
        }

    override suspend fun resume(journal: PendingJournal): StudioRuntimeLease =
        withContext(nativeContext) {
            delegate.resume(journal).also { afterLease() }
        }
}
