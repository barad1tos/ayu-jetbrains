package dev.ayuislands.studio

import dev.ayuislands.studio.model.AcceptanceBlock
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.persistence.StoreLoad
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class StudioApplyCoordinatorTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `flush enters the shared queue without deadlock and children accept only after durable commit`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val request = fixture.request()
                val result = withTimeout(5.seconds) { fixture.coordinator.submit(request) }
                val accepted = assertIs<AcceptanceResult.Accepted>(result)
                assertEquals(
                    request.session.work.drafts
                        .single()
                        .appearance,
                    fixture
                        .document()
                        .currents.global.appearance,
                )
                assertEquals(accepted.revision, fixture.document().header.revision)
                assertEquals(1, fixture.effect(EffectOwner.GLOW).value)
                assertEquals(1, fixture.effect(EffectOwner.GLOW).accepts)
                assertEquals(
                    listOf("flush", "acquire", "prepare-GLOW", "apply-GLOW", "accept-GLOW", "release-accepted"),
                    fixture.events,
                )
                assertIs<StoreLoad.Ready>(fixture.store.load())
            }
        }

    @Test
    fun `mutation then exception restores owned state and retains the original draft`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                fixture.effect(EffectOwner.GLOW).failAfter = true
                val request = fixture.request()
                assertIs<AcceptanceResult.NotAccepted>(fixture.coordinator.submit(request))
                assertEquals(0, fixture.effect(EffectOwner.GLOW).value)
                assertEquals(fixture.initial.currents, fixture.document().currents)
                assertEquals(request.session, fixture.document().sessions.single())
                assertEquals(0, fixture.effect(EffectOwner.GLOW).accepts)
                assertIs<StoreLoad.Ready>(fixture.store.load())
            }
        }

    @Test
    fun `foreign write survives failed apply and the receipt is settled without reclaiming it`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val glow = fixture.effect(EffectOwner.GLOW)
                glow.afterApply = { glow.value = 99 }
                glow.failAfter = true
                assertIs<AcceptanceResult.NotAccepted>(fixture.coordinator.submit(fixture.request()))
                assertEquals(99, glow.value)
                assertEquals(fixture.initial.currents, fixture.document().currents)
                assertIs<StoreLoad.Ready>(fixture.store.load())
            }
        }

    @Test
    fun `failed restoration blocks new flush until explicit recovery settles the old operation`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val glow = fixture.effect(EffectOwner.GLOW)
                glow.failAfter = true
                glow.failRestore = true
                assertIs<AcceptanceResult.RecoveryRequired>(fixture.coordinator.submit(fixture.request()))
                assertIs<AcceptanceResult.Blocked>(fixture.coordinator.submit(fixture.request("second")))
                assertEquals(1, fixture.flushes)
                glow.failRestore = false
                assertIs<AcceptanceResult.NotAccepted>(fixture.coordinator.retryRecovery("accept"))
                assertEquals(0, glow.value)
                assertEquals(fixture.initial.currents, fixture.document().currents)
            }
        }

    @Test
    fun `accepted cleanup failure retains acceptance and retry performs no setters`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val glow = fixture.effect(EffectOwner.GLOW)
                glow.failAccept = true
                val request = fixture.request()
                val pending = assertIs<AcceptanceResult.AcceptedCleanupPending>(fixture.coordinator.submit(request))
                val accepted = fixture.document()
                assertEquals(
                    request.session.work.drafts
                        .single()
                        .appearance,
                    accepted.currents.global.appearance,
                )
                assertEquals(pending, fixture.coordinator.outcome("accept"))
                glow.failAccept = false
                assertIs<AcceptanceResult.Accepted>(fixture.coordinator.retryRecovery("accept"))
                assertEquals(accepted, fixture.document())
                assertEquals(1, glow.writes)
                assertEquals(1, glow.accepts)
                assertTrue(fixture.events.none { it.startsWith("restore-") })
                assertIs<StoreLoad.Ready>(fixture.store.load())
            }
        }

    @Test
    fun `unreadable cleanup retry retains proven acceptance and performs no native work`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val glow = fixture.effect(EffectOwner.GLOW)
                glow.failAccept = true
                val accepted =
                    assertIs<AcceptanceResult.AcceptedCleanupPending>(fixture.coordinator.submit(fixture.request()))
                val document = fixture.document()
                val events = fixture.events.toList()
                glow.failAccept = false
                fixture.files.arm(IoEvent(IoOperation.READ, "state.xml", IoBoundary.BEFORE))

                val pending =
                    assertIs<AcceptanceResult.AcceptedCleanupPending>(fixture.coordinator.retryRecovery("accept"))

                assertEquals(accepted.revision, pending.revision)
                assertEquals(accepted.operationId, pending.operationId)
                assertEquals("store-unavailable", pending.failure.code)
                assertEquals(pending, fixture.coordinator.outcome("accept"))
                assertEquals(events, fixture.events)
                assertEquals(0, fixture.files.pendingFailures)
                assertIs<AcceptanceResult.Accepted>(fixture.coordinator.retryRecovery("accept"))
                assertEquals(document, fixture.document())
                assertEquals(1, glow.writes)
                assertEquals(1, glow.accepts)
                assertTrue(fixture.events.none { it.startsWith("restore-") })
            }
        }

    @Test
    fun `unreadable accepted cleanup blocks a new request without changing the old outcome`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                fixture.effect(EffectOwner.GLOW).failAccept = true
                assertIs<AcceptanceResult.AcceptedCleanupPending>(fixture.coordinator.submit(fixture.request()))
                val events = fixture.events.toList()
                fixture.files.arm(IoEvent(IoOperation.READ, "state.xml", IoBoundary.BEFORE))

                val blocked = assertIs<AcceptanceResult.Blocked>(fixture.coordinator.submit(fixture.request("new")))

                assertEquals(listOf(AcceptanceBlock.RECOVERY), blocked.reasons.toList())
                val pending = assertIs<AcceptanceResult.AcceptedCleanupPending>(fixture.coordinator.outcome("accept"))
                assertEquals("store-unavailable", pending.failure.code)
                assertEquals(blocked, fixture.coordinator.outcome("new"))
                assertEquals(events, fixture.events)
                assertEquals(1, fixture.flushes)
            }
        }
}
