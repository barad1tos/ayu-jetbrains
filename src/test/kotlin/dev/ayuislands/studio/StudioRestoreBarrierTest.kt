package dev.ayuislands.studio

import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.openStudioFiles
import dev.ayuislands.studio.session.StudioApplyCoordinator
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioRestoreBarrierTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `new rollback evidence is acknowledged before its native setters`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val glow = fixture.effect(EffectOwner.GLOW)
                glow.requiresRestoreEvidence = true
                glow.failAfter = true
                assertIs<AcceptanceResult.NotAccepted>(fixture.coordinator.submit(fixture.request()))
                assertEquals(0, glow.value)
                assertEquals(1, glow.writes)
                assertIs<StoreLoad.Ready>(fixture.store.load())
            }
        }

    @Test
    fun `rejected rollback evidence stops all remaining setters and retains recovery`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val workspace = fixture.effect(EffectOwner.WORKSPACE)
                workspace.requiresRestoreEvidence = true
                workspace.failAfter = true
                workspace.afterRestorePrepare = {
                    fixture.files.arm(IoEvent(IoOperation.REPLACE, "pending.xml", IoBoundary.BEFORE))
                }
                assertIs<AcceptanceResult.RecoveryRequired>(fixture.coordinator.submit(fixture.allOwnersRequest()))
                assertTrue(fixture.effects.all { it.value == 1 })
                assertTrue(fixture.events.none { it.startsWith("restore-") })
                val pending = assertIs<StoreLoad.Recovering>(fixture.store.load()).journal
                assertTrue(pending.receipts.none { it.id == "WORKSPACE-restore" })
                workspace.afterRestorePrepare = null
                assertIs<AcceptanceResult.NotAccepted>(fixture.coordinator.retryRecovery("all"))
                assertTrue(fixture.effects.all { it.value == 0 && it.writes == 1 })
            }
        }

    @Test
    fun `lost acknowledgement after restoration stops before earlier owners`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val workspace = fixture.effect(EffectOwner.WORKSPACE)
                workspace.failAfter = true
                workspace.afterRestore = {
                    fixture.files.arm(IoEvent(IoOperation.REPLACE, "pending.xml", IoBoundary.BEFORE))
                }
                assertIs<AcceptanceResult.RecoveryRequired>(fixture.coordinator.submit(fixture.allOwnersRequest()))
                assertEquals(0, workspace.value)
                assertTrue(fixture.effects.filter { it !== workspace }.all { it.value == 1 })
                assertEquals(listOf("restore-WORKSPACE"), fixture.events.filter { it.startsWith("restore-") })
                workspace.afterRestore = null
                assertIs<AcceptanceResult.NotAccepted>(fixture.coordinator.retryRecovery("all"))
                assertTrue(fixture.effects.all { it.value == 0 && it.writes == 1 })
            }
        }

    @Test
    fun `rollback append survives reopen whether its acknowledgement was lost before or after replacement`(): Unit =
        runBlocking {
            for (boundary in IoBoundary.entries) {
                val root = directory.resolve(boundary.name)
                StudioApplyFixture(root).use { fixture ->
                    val workspace = fixture.effect(EffectOwner.WORKSPACE)
                    workspace.requiresRestoreEvidence = true
                    workspace.failAfter = true
                    workspace.afterRestorePrepare = {
                        fixture.files.arm(IoEvent(IoOperation.REPLACE, "pending.xml", boundary))
                    }
                    assertIs<AcceptanceResult.RecoveryRequired>(fixture.coordinator.submit(fixture.allOwnersRequest()))
                    assertTrue(fixture.effects.all { it.value == 1 })
                    val receipts = assertIs<StoreLoad.Recovering>(fixture.store.load()).journal.receipts
                    assertEquals(boundary == IoBoundary.AFTER, receipts.any { it.id == "WORKSPACE-restore" })
                    fixture.store.close()
                    StudioAppearanceStore(openStudioFiles(root)).use { reopened ->
                        assertEquals(receipts, assertIs<StoreLoad.Recovering>(reopened.load()).journal.receipts)
                        val owners =
                            fixture.effects.map { previous ->
                                ApplyTestEffect(previous.owner, reopened, fixture.events).apply {
                                    surface = previous.surface
                                    requiresRestoreEvidence = previous.requiresRestoreEvidence
                                }
                            }
                        val coordinator = StudioApplyCoordinator(reopened, fixture, owners, fixture.mutex)
                        assertIs<AcceptanceResult.NotAccepted>(coordinator.retryRecovery("all"))
                        assertTrue(owners.all { it.value == 0 && it.writes == 0 })
                        assertEquals(
                            fixture.initial.currents,
                            assertIs<StoreLoad.Ready>(reopened.load()).document.currents,
                        )
                    }
                }
            }
        }
}
