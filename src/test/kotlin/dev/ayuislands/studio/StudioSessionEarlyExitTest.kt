package dev.ayuislands.studio

import dev.ayuislands.studio.model.AcceptanceBlock
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.SessionEffect
import dev.ayuislands.studio.model.SessionNotice
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.StudioApplyUpdate
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioSessionEarlyExitTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `older pending rollback blocks the new session without borrowing its operation`(): Unit =
        runBlocking {
            SessionApplyFixture(directory, this).use { fixture ->
                val glow = fixture.native.effect(EffectOwner.GLOW)
                glow.failAfter = true
                glow.failRestore = true
                assertIs<AcceptanceResult.RecoveryRequired>(
                    fixture.native.coordinator.submit(fixture.native.request("old")),
                )
                val draft = fixture.controller.state.record
                val result = assertIs<AcceptanceResult.Blocked>(fixture.apply("new"))
                assertEquals(listOf(AcceptanceBlock.RECOVERY), result.reasons)
                assertEquals(SessionPhase.Editing, fixture.controller.state.phase)
                assertEquals(draft, fixture.controller.state.record)
                assertEquals(1, glow.writes)
                assertIs<AcceptanceResult.RecoveryRequired>(fixture.coordinator.outcome("old"))
                assertEquals(result, fixture.coordinator.outcome("new"))
                glow.failRestore = false
                assertIs<AcceptanceResult.NotAccepted>(fixture.coordinator.retryRecovery("old"))
                assertEquals(SessionPhase.Editing, fixture.controller.state.phase)
                assertTrue(fixture.notices.none { it is SessionNotice.Invariant }, fixture.notices.toString())
            }
        }

    @Test
    fun `older accepted cleanup cannot accept the new draft or strand it preparing`(): Unit =
        runBlocking {
            SessionApplyFixture(directory, this).use { fixture ->
                val glow = fixture.native.effect(EffectOwner.GLOW)
                glow.failAccept = true
                assertIs<AcceptanceResult.AcceptedCleanupPending>(
                    fixture.native.coordinator.submit(fixture.native.request("old")),
                )
                val draft = fixture.controller.state.record
                val result = assertIs<AcceptanceResult.Blocked>(fixture.apply("new"))
                assertEquals(listOf(AcceptanceBlock.RECOVERY), result.reasons)
                assertEquals(SessionPhase.Editing, fixture.controller.state.phase)
                assertEquals(draft, fixture.controller.state.record)
                assertEquals(1, glow.writes)
                assertIs<AcceptanceResult.AcceptedCleanupPending>(fixture.coordinator.outcome("old"))
                glow.failAccept = false
                assertIs<AcceptanceResult.Accepted>(fixture.coordinator.retryRecovery("old"))
                assertEquals(draft, fixture.controller.state.record)
                assertTrue(fixture.notices.none { it is SessionNotice.Invariant }, fixture.notices.toString())
            }
        }

    @Test
    fun `read only store before flushing leaves the new draft editable`(): Unit =
        runBlocking {
            SessionApplyFixture(directory, this).use { fixture ->
                fixture.native.files.arm(IoEvent(IoOperation.READ, "state.xml", IoBoundary.BEFORE))
                val draft = fixture.controller.state.record
                val result = assertIs<AcceptanceResult.Blocked>(fixture.apply("new"))
                assertEquals(listOf(AcceptanceBlock.READ_ONLY), result.reasons)
                assertEquals(SessionPhase.Editing, fixture.controller.state.phase)
                assertEquals(draft, fixture.controller.state.record)
                assertEquals(SessionNotice.Blocked(result.reasons), fixture.controller.state.notice)
                assertEquals(0, fixture.native.flushes)
                assertTrue(fixture.native.effects.all { it.writes == 0 })
            }
        }

    @Test
    fun `lease invalidated by progress delivery rolls back before durable acceptance`(): Unit =
        runBlocking {
            SessionApplyFixture(directory, this).use { fixture ->
                fixture.native.beforePublish = { update ->
                    if (update is StudioApplyUpdate.Effect && update.result == SessionEffect.SurfacesSettled) {
                        fixture.native.isLeaseValid = false
                    }
                }
                val draft = fixture.controller.state.record
                assertIs<AcceptanceResult.NotAccepted>(fixture.apply("first"))
                assertEquals(SessionPhase.Editing, fixture.controller.state.phase)
                assertEquals(draft, fixture.controller.state.record)
                assertEquals(fixture.native.initial.currents, fixture.native.document().currents)
                assertEquals(0, fixture.native.effect(EffectOwner.GLOW).value)
                assertTrue(fixture.native.updates.none { it is StudioApplyUpdate.Committed })
                assertTrue(fixture.notices.none { it is SessionNotice.Invariant }, fixture.notices.toString())
            }
        }
}
