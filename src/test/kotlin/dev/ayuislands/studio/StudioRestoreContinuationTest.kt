package dev.ayuislands.studio

import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.NativeValue
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.session.StudioApplyCoordinator
import dev.ayuislands.studio.session.StudioEffectInput
import dev.ayuislands.studio.session.StudioEffectOperation
import dev.ayuislands.studio.session.StudioEffectParticipant
import dev.ayuislands.studio.session.StudioEffectRuntime
import dev.ayuislands.studio.session.StudioRestoreContinuation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioRestoreContinuationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `continuation captures actual restored state and starts only after its own receipt acknowledgement`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val deferred = DeferredRestoreEffect(fixture)
                assertIs<AcceptanceResult.NotAccepted>(deferred.createCoordinator().submit(fixture.allOwnersRequest()))
                assertEquals(listOf(0), deferred.capturedValues)
                assertEquals(1, deferred.starts)
                assertEquals(2, deferred.restores)
                assertTrue(deferred.isRunning)
                assertTrue(fixture.effects.all { it.value == 0 && it.writes == 1 })
                assertEquals(1, fixture.events.count { it == "restore-WORKSPACE" })
                assertIs<StoreLoad.Ready>(fixture.store.load())
            }
        }

    @Test
    fun `lost first step acknowledgement stops before continuation capture and earlier owners`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val deferred = DeferredRestoreEffect(fixture)
                val coordinator = deferred.createCoordinator()
                fixture.effect(EffectOwner.WORKSPACE).afterRestore = {
                    fixture.files.arm(IoEvent(IoOperation.REPLACE, "pending.xml", IoBoundary.BEFORE))
                }
                assertIs<AcceptanceResult.RecoveryRequired>(coordinator.submit(fixture.allOwnersRequest()))
                assertTrue(deferred.capturedValues.isEmpty())
                assertEquals(0, deferred.starts)
                assertTrue(fixture.effects.filter { it.owner != EffectOwner.WORKSPACE }.all { it.value == 1 })
                fixture.effect(EffectOwner.WORKSPACE).afterRestore = null
                assertIs<AcceptanceResult.NotAccepted>(coordinator.retryRecovery("all"))
                assertEquals(listOf(0), deferred.capturedValues)
                assertEquals(1, deferred.starts)
                assertEquals(1, fixture.events.count { it == "restore-WORKSPACE" })
            }
        }

    @Test
    fun `rejected continuation receipt cannot start playback or restore earlier owners`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val deferred = DeferredRestoreEffect(fixture)
                val coordinator = deferred.createCoordinator()
                deferred.afterPrepare = {
                    fixture.files.arm(IoEvent(IoOperation.REPLACE, "pending.xml", IoBoundary.BEFORE))
                }
                assertIs<AcceptanceResult.RecoveryRequired>(coordinator.submit(fixture.allOwnersRequest()))
                assertEquals(listOf(0), deferred.capturedValues)
                assertEquals(0, deferred.starts)
                assertFalse(deferred.isRunning)
                val pending = assertIs<StoreLoad.Recovering>(fixture.store.load()).journal
                assertTrue(pending.receipts.none { it.id == "WORKSPACE-startup" })
                assertTrue(fixture.effects.filter { it.owner != EffectOwner.WORKSPACE }.all { it.value == 1 })
                deferred.afterPrepare = null
                assertIs<AcceptanceResult.NotAccepted>(coordinator.retryRecovery("all"))
                assertEquals(1, deferred.starts)
            }
        }

    @Test
    fun `failed continuation is not retried and independent earlier owners still restore`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val deferred = DeferredRestoreEffect(fixture)
                deferred.failStartup = true
                val result = deferred.createCoordinator().submit(fixture.allOwnersRequest())
                assertIs<AcceptanceResult.RecoveryRequired>(result)
                assertEquals(1, deferred.starts)
                assertEquals(2, deferred.restores)
                assertTrue(deferred.isRunning)
                assertTrue(fixture.effects.all { it.value == 0 })
                val pending = assertIs<StoreLoad.Recovering>(fixture.store.load()).journal
                assertEquals(RestoreStatus.PENDING, pending.receipts.single { it.id == "WORKSPACE-startup" }.status)
            }
        }

    @Test
    fun `successful continuation with lost final acknowledgement is never started again on explicit retry`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val deferred = DeferredRestoreEffect(fixture)
                val coordinator = deferred.createCoordinator()
                deferred.afterStartup = {
                    fixture.files.arm(IoEvent(IoOperation.REPLACE, "pending.xml", IoBoundary.BEFORE))
                }
                assertIs<AcceptanceResult.RecoveryRequired>(coordinator.submit(fixture.allOwnersRequest()))
                assertEquals(1, deferred.starts)
                assertTrue(fixture.effects.filter { it.owner != EffectOwner.WORKSPACE }.all { it.value == 1 })
                deferred.afterStartup = null
                assertIs<AcceptanceResult.NotAccepted>(coordinator.retryRecovery("all"))
                assertEquals(1, deferred.starts)
                assertTrue(fixture.effects.all { it.value == 0 && it.writes == 1 })
            }
        }

    @Test
    fun `a second continuation request cannot trigger a third restore step`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val deferred = DeferredRestoreEffect(fixture)
                val coordinator = deferred.createCoordinator()
                deferred.requiresThird = true
                assertIs<AcceptanceResult.RecoveryRequired>(coordinator.submit(fixture.allOwnersRequest()))
                assertEquals(2, deferred.restores)
                assertEquals(1, deferred.starts)
                assertTrue(fixture.effects.all { it.value == 0 })
                deferred.requiresThird = false
                assertIs<AcceptanceResult.NotAccepted>(coordinator.retryRecovery("all"))
                assertEquals(1, deferred.starts)
            }
        }
}

/** Exercises the real store barriers; native playback ownership is tested in the Glow fixtures. */
private class DeferredRestoreEffect(
    private val fixture: StudioApplyFixture,
    private val source: ApplyTestEffect = fixture.effect(EffectOwner.WORKSPACE),
) : StudioEffectParticipant by source {
    val capturedValues = mutableListOf<Int>()
    var starts = 0
    var restores = 0
    var isRunning = false
    var failStartup = false
    var requiresThird = false
    var afterPrepare: (() -> Unit)? = null
    var afterStartup: (() -> Unit)? = null

    init {
        source.failAfter = true
    }

    fun createCoordinator(): StudioApplyCoordinator =
        StudioApplyCoordinator(
            fixture.store,
            fixture,
            fixture.effects.map { if (it === source) this else it },
            fixture.mutex,
        )

    override suspend fun prepare(
        input: StudioEffectInput,
        runtime: StudioEffectRuntime,
    ): StudioEffectOperation {
        val original = source.prepare(input, runtime)
        return object : StudioEffectOperation by original {
            private var phase = Phase.SURFACES
            private var startup: PortableReceipt? = null
            override val receipts: StudioList<PortableReceipt>
                get() = StudioList(original.receipts + listOfNotNull(startup))

            override suspend fun prepareRestore(blockedBy: StudioList<PortableReceipt>) {
                when (phase) {
                    Phase.SURFACES -> {
                        original.prepareRestore(blockedBy)
                    }

                    Phase.STARTUP -> {
                        if (startup == null) {
                            capturedValues.add(source.value)
                            val baseline = original.receipts.first()
                            startup =
                                baseline.copy(
                                    id = "WORKSPACE-startup",
                                    identity = baseline.identity.copy(field = "startup"),
                                    before = NativeValue.Present(RawValue.Integer(source.value)),
                                    lastWritten = NativeValue.Present(RawValue.Integer(1)),
                                    status = RestoreStatus.PENDING,
                                )
                        }
                        afterPrepare?.invoke()
                    }

                    Phase.COMPLETE -> {}
                }
            }

            override suspend fun restore(blockedBy: StudioList<PortableReceipt>): StudioRestoreContinuation {
                restores++
                when (phase) {
                    Phase.SURFACES -> {
                        original.restore(blockedBy)
                        if (original.receipts.any {
                                it.status != RestoreStatus.RESTORED && it.status != RestoreStatus.FOREIGN
                            }
                        ) {
                            return StudioRestoreContinuation.NONE
                        }
                        phase = Phase.STARTUP
                        return StudioRestoreContinuation.REQUIRED
                    }

                    Phase.STARTUP -> {
                        val journal = assertIs<StoreLoad.Recovering>(fixture.store.load()).journal
                        assertTrue(journal.receipts.containsAll(receipts), "Startup evidence must precede timers")
                        checkNotNull(startup)
                        isRunning = true
                        starts++
                        afterStartup?.invoke()
                        check(!failStartup) { "Startup mutated then failed" }
                        startup = checkNotNull(startup).copy(status = RestoreStatus.RESTORED)
                        phase = Phase.COMPLETE
                    }

                    Phase.COMPLETE -> {}
                }
                return if (requiresThird) StudioRestoreContinuation.REQUIRED else StudioRestoreContinuation.NONE
            }
        }
    }

    private enum class Phase { SURFACES, STARTUP, COMPLETE }
}
