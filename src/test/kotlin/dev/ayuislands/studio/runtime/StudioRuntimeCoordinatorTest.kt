package dev.ayuislands.studio.runtime

import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.rotation.ClockSample
import dev.ayuislands.rotation.RotationAcceptanceOrigin
import dev.ayuislands.rotation.RotationCommand
import dev.ayuislands.rotation.RotationRuntimeCheckpoint
import dev.ayuislands.rotation.RotationRuntimeState
import dev.ayuislands.rotation.RotationSchedule
import dev.ayuislands.rotation.RotationSnapshot
import dev.ayuislands.rotation.RotationTicket
import dev.ayuislands.rotation.RotationTransition
import dev.ayuislands.rotation.RuntimeAvailability
import dev.ayuislands.rotation.RuntimeInputs
import dev.ayuislands.rotation.RuntimeOwner
import dev.ayuislands.studio.IoBoundary
import dev.ayuislands.studio.IoEvent
import dev.ayuislands.studio.IoOperation
import dev.ayuislands.studio.StudioApplyFixture
import dev.ayuislands.studio.model.AcceptanceOrigin
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionEffect
import dev.ayuislands.studio.model.StudioApplyUpdate
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioRuntimeState
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.session.StudioAcceptancePreparation
import dev.ayuislands.studio.session.StudioApplyCoordinator
import dev.ayuislands.studio.session.StudioApplyEnvironment
import dev.ayuislands.studio.session.StudioDraftPolicy
import dev.ayuislands.studio.session.StudioRuntimeLease
import dev.ayuislands.studio.sessionRecord
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StudioRuntimeCoordinatorTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `accepted user step arms once after cleanup and cannot repeat on duplicate facts`(): Unit =
        runBlocking {
            RuntimeHarness(directory).use { harness ->
                val request = harness.request()

                val accepted = assertIs<AcceptanceResult.Accepted>(harness.apply.submit(request))

                assertEquals(listOf(0), harness.environment.armsAtCommit)
                assertEquals(listOf(0), harness.environment.armsAtRelease)
                harness.assertAcceptedStep()
                val waiting = assertIs<RotationRuntimeState.Waiting>(harness.runtime.state)
                assertEquals(harness.document().runtime.rotation, waiting.snapshot.checkpoint)
                assertEquals(harness.scheduler.arms.single(), waiting.ticket)
                assertEquals(accepted, harness.apply.submit(request))
                val updates = harness.native.updates.toList()
                for (update in updates) {
                    if (update is StudioApplyUpdate.Committed ||
                        (update is StudioApplyUpdate.Effect && update.result == SessionEffect.CleanupSettled)
                    ) {
                        harness.scheduler.record(harness.runtime.observe(update, harness.environment.clock))
                    }
                }
                assertEquals(1, harness.scheduler.arms.size)
                assertEquals(1, harness.native.effect(EffectOwner.ACCENT).writes)
                assertEquals(1, harness.environment.completions)
            }
        }

    @Test
    fun `accepted cleanup failure retains the checkpoint and arms only after cleanup retry`(): Unit =
        runBlocking {
            RuntimeHarness(directory).use { harness ->
                harness.native.beforePublish = { update ->
                    if (update is StudioApplyUpdate.Committed) {
                        harness.native.files.arm(IoEvent(IoOperation.REMOVE, "pending.xml", IoBoundary.BEFORE))
                    }
                }
                val request = harness.request()

                assertIs<AcceptanceResult.AcceptedCleanupPending>(harness.apply.submit(request))

                val accepted = harness.document()
                assertEquals(8L, accepted.runtime.rotation?.successfulStepSerial)
                assertEquals(accepted.runtime.rotation, harness.snapshot().checkpoint)
                assertTrue(harness.scheduler.arms.isEmpty())
                assertEquals(listOf(0), harness.environment.armsAtRelease)
                assertIs<StoreLoad.Recovering>(harness.native.store.load())
                harness.native.beforePublish = null

                assertIs<AcceptanceResult.Accepted>(harness.apply.retryRecovery(request.transactionId))

                assertEquals(accepted, harness.document())
                harness.assertAcceptedStep()
                assertEquals(1, harness.environment.completions)
                assertEquals(1, harness.native.effect(EffectOwner.ACCENT).accepts)
            }
        }

    @Test
    fun `failed immediate user step restores the old checkpoint and draft without an automatic retry`(): Unit =
        runBlocking {
            RuntimeHarness(directory).use { harness ->
                harness.native.effect(EffectOwner.ACCENT).failAfter = true
                val request = harness.request()

                assertIs<AcceptanceResult.NotAccepted>(harness.apply.submit(request))

                val restored = harness.document()
                assertEquals(harness.baseline.runtime, restored.runtime)
                assertEquals(harness.baseline.currents, restored.currents)
                assertEquals(request.session, restored.sessions.single())
                assertEquals(harness.initialState, harness.runtime.state)
                assertEquals(0, harness.native.effect(EffectOwner.ACCENT).value)
                assertTrue(harness.scheduler.commands.isEmpty())
                assertEquals(0, harness.environment.completions)
                assertIs<StoreLoad.Ready>(harness.native.store.load())
            }
        }

    @Test
    fun `lost commit acknowledgement resolves the same user step and performs cleanup without replay`(): Unit =
        runBlocking {
            RuntimeHarness(directory).use { harness ->
                harness.native.effect(EffectOwner.ACCENT).afterApply = {
                    harness.native.files.arm(
                        IoEvent(IoOperation.REPLACE, "state.xml", IoBoundary.AFTER),
                        IoEvent(IoOperation.READ, "state.xml", IoBoundary.BEFORE),
                        IoEvent(IoOperation.READ, "state.xml", IoBoundary.BEFORE),
                    )
                }
                val request = harness.request()

                assertIs<AcceptanceResult.RecoveryRequired>(harness.apply.submit(request))

                assertTrue(harness.scheduler.arms.isEmpty())
                assertTrue(harness.native.events.none { it.startsWith("restore-") })
                val journal = assertIs<StoreLoad.Recovering>(harness.native.store.load()).journal
                val frozen = assertNotNull(journal.recovery.rotationStep)
                assertEquals(request.transactionId, journal.token.transactionId)
                assertEquals(harness.operationId, frozen.operationId)

                assertIs<AcceptanceResult.Accepted>(harness.apply.retryRecovery(request.transactionId))

                harness.assertAcceptedStep()
                assertEquals(
                    frozen.hex,
                    harness
                        .document()
                        .runtime.rotation
                        ?.output
                        ?.hex,
                )
                assertEquals(1, harness.environment.preparations)
                assertEquals(1, harness.environment.completions)
                assertEquals(1, harness.native.effect(EffectOwner.ACCENT).accepts)
                assertTrue(harness.native.events.none { it.startsWith("restore-") })
            }
        }

    @Test
    fun `focus changes after preparation invalidate the lease before the first setter`(): Unit =
        runBlocking {
            RuntimeHarness(directory).use { harness ->
                harness.native.effect(EffectOwner.ACCENT).beforePrepare = {
                    val project = StudioTarget.Project(StudioProjectKey("project-b"))
                    harness.runtime.observeFocus(FocusSnapshot(project, 2))
                }
                val request = harness.request()

                assertIs<AcceptanceResult.NotAccepted>(harness.apply.submit(request))

                assertEquals(1, harness.environment.preparations)
                assertEquals(0, harness.native.effect(EffectOwner.ACCENT).writes)
                assertEquals(harness.baseline.runtime, harness.document().runtime)
                assertEquals(request.session, harness.document().sessions.single())
                assertTrue(harness.scheduler.commands.isEmpty())
                assertEquals(0, harness.environment.completions)
                assertIs<StoreLoad.Ready>(harness.native.store.load())
            }
        }
}

private class RuntimeHarness(
    directory: Path,
) : AutoCloseable {
    val native = StudioApplyFixture(directory)
    val operationId: UUID = UUID.fromString("60000000-0000-0000-0000-000000000001")
    val baseline = seedCheckpoint()
    val initialState =
        RotationRuntimeState.Off(
            RotationSnapshot(
                assertNotNull(baseline.runtime.rotation),
                RuntimeInputs(
                    RuntimeOwner(StudioTarget.Global, baseline.currents.global.revision),
                    baseline.currents.global.appearance,
                    RuntimeAvailability(
                        lastDefinitivePro = true,
                        effectiveFollowSystem = false,
                        variant = AyuVariant.MIRAGE,
                        targetValid = true,
                        recoveryPending = false,
                    ),
                ),
            ),
        )
    val runtime = StudioRuntimeCoordinator(initialState, FocusSnapshot(StudioTarget.Global, 1))
    val scheduler = RecordingSchedule()
    val environment = RuntimeEnvironment(native, runtime, scheduler, operationId)
    val apply = StudioApplyCoordinator(native.store, environment, native.effects, native.mutex)

    fun document(): StudioDocument = native.document()

    fun snapshot(): RotationSnapshot = assertIs<RotationRuntimeState.Loaded>(runtime.state).snapshot

    fun request(): AcceptanceRequest {
        val record = sessionRecord(baseline)
        val selected = record.work.drafts.single { it.id == record.work.selected }
        val enabled =
            StudioDraftPolicy.edit(
                selected,
                FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "accentRotationEnabled")),
                FieldValue.Present(RawValue.BooleanValue(true)),
                RotationAcceptanceOrigin.EXPLICIT_ROTATION_ENABLE,
            )
        return AcceptanceRequest(
            record.copy(work = record.work.copy(drafts = StudioList(listOf(enabled)))),
            "user-step-non-uuid",
            1,
            AcceptanceOrigin.SETTINGS,
        )
    }

    fun assertAcceptedStep() {
        val checkpoint = assertNotNull(document().runtime.rotation)
        assertEquals(8L, checkpoint.successfulStepSerial)
        assertEquals(4, checkpoint.sequenceCursorRaw)
        assertEquals(environment.clock.epochMillis, checkpoint.lastSuccessfulStepEpochMillis)
        assertEquals(
            environment.clock.epochMillis + TimeUnit.HOURS.toMillis(6),
            checkpoint.schedule?.deadlineEpochMillis,
        )
        assertEquals(1, native.effect(EffectOwner.ACCENT).writes)
        assertEquals(1, scheduler.arms.size)
        assertTrue(scheduler.commands.all { it is RotationCommand.ArmSchedule })
        assertIs<StoreLoad.Ready>(native.store.load())
    }

    private fun seedCheckpoint(): StudioDocument {
        val original = native.initial
        val owner = RuntimeOwner(StudioTarget.Global, original.currents.global.revision)
        val checkpoint =
            RotationRuntimeCheckpoint(
                owner,
                3,
                7,
                schedule = RotationSchedule(0, TimeUnit.HOURS.toMillis(6), TimeUnit.HOURS.toMillis(6), 5),
            )
        val seeded =
            original.copy(
                header = original.header.copy(revision = 1, transactionId = "seed-checkpoint"),
                runtime = original.runtime.copy(rotation = checkpoint),
            )
        val prepared =
            assertIs<PrepareResult.Prepared>(
                native.store.prepare(
                    0,
                    seeded,
                    RecoveryPlan(StudioTarget.Global, StudioList(emptyList())),
                ),
            )
        assertIs<CommitResult.Committed>(native.store.commit(prepared.token, seeded.runtime))
        assertEquals(JournalResult.Acknowledged, native.store.finishAccepted(prepared.token))
        return seeded
    }

    override fun close() = native.close()
}

private class RuntimeEnvironment(
    private val delegate: StudioApplyEnvironment,
    private val runtime: StudioRuntimeCoordinator,
    private val scheduler: RecordingSchedule,
    private val operationId: UUID,
) : StudioApplyEnvironment by delegate {
    val clock = ClockSample(TimeUnit.HOURS.toMillis(3), TimeUnit.HOURS.toNanos(3))
    val armsAtCommit = mutableListOf<Int>()
    val armsAtRelease = mutableListOf<Int>()
    var preparations = 0
    var completions = 0

    override suspend fun prepareAcceptance(
        request: AcceptanceRequest,
        plan: AcceptancePlan.Commit,
    ): StudioAcceptancePreparation {
        preparations++
        val decision =
            runtime.prepare(RuntimeAcceptanceRequest(operationId, request, plan), ClockSample(0, 0))
        val ready = assertIs<RuntimePreparationPlan.Ready>(decision)
        val acquired = assertIs<StudioAcceptancePreparation.Prepared>(delegate.prepareAcceptance(request, ready.plan))
        val lease =
            object : StudioRuntimeLease by acquired.lease {
                override suspend fun isValid(): Boolean =
                    acquired.lease.isValid() && runtime.isValid(request.transactionId)

                override suspend fun settledRuntime(candidate: StudioDocument): StudioRuntimeState {
                    completions++
                    val settled = acquired.lease.settledRuntime(candidate)
                    return runtime.settledRuntime(request.transactionId, candidate.copy(runtime = settled), clock)
                }

                override suspend fun release(accepted: StudioDocument?) {
                    acquired.lease.release(accepted)
                    scheduler.record(runtime.release(request.transactionId, accepted, clock))
                    if (accepted != null) armsAtRelease += scheduler.arms.size
                }
            }
        return acquired.copy(lease = lease)
    }

    override suspend fun publish(update: StudioApplyUpdate) {
        delegate.publish(update)
        scheduler.record(runtime.observe(update, clock))
        if (update is StudioApplyUpdate.Committed) armsAtCommit += scheduler.arms.size
    }
}

private class RecordingSchedule {
    val commands = mutableListOf<RotationCommand>()
    val arms: List<RotationTicket> get() = commands.filterIsInstance<RotationCommand.ArmSchedule>().map { it.ticket }

    fun record(transition: RotationTransition) {
        commands += transition.commands
    }
}
