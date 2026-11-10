package dev.ayuislands.studio

import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.rotation.RotationCandidate
import dev.ayuislands.rotation.RotationCompletionPlan
import dev.ayuislands.rotation.RotationRuntimeCheckpoint
import dev.ayuislands.rotation.RotationStepPlan
import dev.ayuislands.rotation.RotationStepPlanner
import dev.ayuislands.rotation.RuntimeAvailability
import dev.ayuislands.rotation.RuntimeInputs
import dev.ayuislands.rotation.RuntimeOwner
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioRuntimeState
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.CommitResolution
import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.JournalDecodeResult
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioJournalCodec
import dev.ayuislands.studio.persistence.StudioXml
import dev.ayuislands.studio.persistence.openStudioFiles
import dev.ayuislands.studio.session.StudioAcceptancePreparation
import dev.ayuislands.studio.session.StudioApplyCoordinator
import dev.ayuislands.studio.session.StudioApplyEnvironment
import dev.ayuislands.studio.session.StudioDraftPolicy
import dev.ayuislands.studio.session.StudioRuntimeLease
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StudioRotationPreparationTest {
    @TempDir lateinit var directory: Path
    private val operationId = UUID.fromString("50000000-0000-0000-0000-000000000001")
    private val completedAt = TimeUnit.HOURS.toMillis(3)

    @Test
    fun `prepared user step reaches the journal before setters and completion advances its frozen choice once`(): Unit =
        runBlocking {
            StudioApplyFixture(directory).use { fixture ->
                val request = request(fixture)
                val environment = FrozenStepEnvironment(fixture, operationId, completedAt)
                val coordinator = StudioApplyCoordinator(fixture.store, environment, fixture.effects, fixture.mutex)
                fixture.effect(EffectOwner.ACCENT).beforePrepare = {
                    val pending = assertIs<StoreLoad.Recovering>(fixture.store.load()).journal
                    val step = assertNotNull(pending.recovery.rotationStep)
                    assertEquals(environment.preparedPlan.rotationStep, step)
                    assertEquals(operationId, step.operationId)
                    assertEquals("raw-user-transaction", pending.token.transactionId)
                    assertEquals("#ABC123", step.hex)
                    assertNull(
                        pending.candidate.runtime.rotation
                            ?.retryCandidate,
                    )
                    assertEquals(
                        7,
                        pending.candidate.runtime.rotation
                            ?.successfulStepSerial,
                    )
                }

                val accepted = assertIs<AcceptanceResult.Accepted>(coordinator.submit(request))
                assertEquals(accepted, coordinator.submit(request))
                val checkpoint = assertNotNull(fixture.document().runtime.rotation)

                assertEquals(1, environment.preparations)
                assertEquals(1, environment.completions)
                assertEquals(1, fixture.effect(EffectOwner.ACCENT).writes)
                assertEquals(8L, checkpoint.successfulStepSerial)
                assertEquals(3, checkpoint.sequenceCursorRaw)
                assertEquals("#ABC123", checkpoint.output?.hex)
                assertEquals(completedAt, checkpoint.lastSuccessfulStepEpochMillis)
                assertEquals(completedAt + TimeUnit.HOURS.toMillis(6), checkpoint.schedule?.deadlineEpochMillis)
                assertNull(checkpoint.retryCandidate)
            }
        }

    @Test
    fun `prepared step survives restart and a lost commit acknowledgement without another color or serial`() {
        lateinit var candidate: StudioDocument
        lateinit var chosen: RotationCandidate
        StudioApplyFixture(directory).use { fixture ->
            val request = request(fixture)
            val selected =
                request.session.work.drafts
                    .single { it.id == request.session.work.selected }
            val current =
                fixture.initial.currents.global
                    .copy(appearance = selected.appearance, revision = 1)
            candidate =
                fixture.initial.copy(
                    header = fixture.initial.header.copy(revision = 1, transactionId = "raw-user-transaction"),
                    currents = fixture.initial.currents.copy(global = current),
                )
            val owner = RuntimeOwner(StudioTarget.Global, current.revision)
            val checkpoint = RotationRuntimeCheckpoint(owner, 3, 7)
            candidate = candidate.copy(runtime = candidate.runtime.copy(rotation = checkpoint))
            chosen =
                assertIs<RotationStepPlan.Ready>(
                    RotationStepPlanner.planStep(checkpoint, inputs(candidate), operationId, "#ABC123"),
                ).candidate
            assertIs<PrepareResult.Prepared>(
                fixture.store.prepare(
                    0,
                    candidate,
                    RecoveryPlan(StudioTarget.Global, StudioList(emptyList()), rotationStep = chosen),
                ),
            )
        }
        val files = FaultingStudioFiles(openStudioFiles(directory))
        lateinit var accepted: StudioDocument
        StudioAppearanceStore(files).use { reopened ->
            val pending = assertIs<StoreLoad.Recovering>(reopened.load()).journal
            assertEquals(chosen, pending.recovery.rotationStep)
            assertEquals(candidate, pending.candidate)
            val completed =
                assertIs<RotationCompletionPlan.Ready>(
                    RotationStepPlanner.planCompletion(
                        assertNotNull(candidate.runtime.rotation),
                        inputs(candidate),
                        assertNotNull(pending.recovery.rotationStep),
                        completedAt,
                    ),
                ).checkpoint
            accepted = candidate.copy(runtime = candidate.runtime.copy(rotation = completed))
            files.arm(
                IoEvent(IoOperation.REPLACE, "state.xml", IoBoundary.AFTER),
                IoEvent(IoOperation.READ, "state.xml", IoBoundary.BEFORE),
            )
            assertIs<CommitResult.OutcomeUnknown>(reopened.commit(pending.token, accepted.runtime))
        }
        StudioAppearanceStore(openStudioFiles(directory)).use { reopened ->
            val pending = assertIs<StoreLoad.Recovering>(reopened.load())
            assertEquals(chosen, pending.journal.recovery.rotationStep)
            assertEquals(accepted, pending.document)
            assertEquals(CommitResolution.Committed, reopened.resolveOutcome(pending.journal.token))
            val repeated = assertIs<CommitResult.Committed>(reopened.commit(pending.journal.token, candidate.runtime))
            assertEquals(accepted, repeated.document)
            assertEquals(JournalResult.Acknowledged, reopened.finishAccepted(pending.journal.token))
            assertEquals(accepted, assertIs<StoreLoad.Ready>(reopened.load()).document)
        }
    }

    @Test
    fun `older pending journals without a user rotation step retain their original recovery intent`() {
        StudioApplyFixture(directory).use { fixture ->
            val candidate =
                fixture.initial.copy(
                    header = fixture.initial.header.copy(revision = 1, transactionId = "older-journal"),
                )
            assertIs<PrepareResult.Prepared>(
                fixture.store.prepare(
                    0,
                    candidate,
                    RecoveryPlan(StudioTarget.Global, StudioList(emptyList())),
                ),
            )
            val pending = assertIs<StoreLoad.Recovering>(fixture.store.load()).journal
            val envelope = StudioXml.parse(StudioJournalCodec.encode(pending))
            val payload =
                GZIPInputStream(StudioXml.decodeBytes(envelope.child("payload").text).inputStream()).use { stream ->
                    StudioXml.parse(stream.readBytes())
                }
            val older =
                payload.copy(
                    children =
                        payload.children.map { section ->
                            if (section.name ==
                                "recovery"
                            ) {
                                section.copy(children = section.children.filterNot { it.name == "rotation-step" })
                            } else {
                                section
                            }
                        },
                )
            val bytes =
                StudioXml.write(StudioXml.envelope("journal", envelope.attributes + ("schema" to "1"), older))
            val decoded = assertIs<JournalDecodeResult.Ready>(StudioJournalCodec.decode(bytes))

            assertNull(decoded.journal.recovery.rotationStep)
            assertEquals(pending, decoded.journal)
            assertEquals(fixture.initial.runtime, decoded.journal.candidate.runtime)
        }
    }

    private fun request(fixture: StudioApplyFixture): AcceptanceRequest {
        val original = fixture.request("raw-user-transaction")
        val record = original.session
        val draft = record.work.drafts.single { it.id == record.work.selected }
        val enabled =
            StudioDraftPolicy.edit(
                draft,
                FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "accentRotationEnabled")),
                FieldValue.Present(RawValue.BooleanValue(true)),
            )
        val random =
            StudioDraftPolicy.edit(
                enabled,
                FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "accentRotationMode")),
                FieldValue.Present(RawValue.Text("RANDOM")),
            )
        return original.copy(session = record.copy(work = record.work.copy(drafts = StudioList(listOf(random)))))
    }
}

private class FrozenStepEnvironment(
    private val delegate: StudioApplyEnvironment,
    private val operationId: UUID,
    private val completedAt: Long,
) : StudioApplyEnvironment by delegate {
    lateinit var preparedPlan: AcceptancePlan.Commit
    var preparations = 0
    var completions = 0

    override suspend fun prepareAcceptance(
        request: AcceptanceRequest,
        plan: AcceptancePlan.Commit,
    ): StudioAcceptancePreparation {
        preparations++
        val owner = RuntimeOwner(request.session.target, plan.candidate.currents.global.revision)
        val checkpoint = RotationRuntimeCheckpoint(owner, 3, 7)
        val proposed = plan.candidate.copy(runtime = plan.candidate.runtime.copy(rotation = checkpoint))
        val selected =
            assertIs<RotationStepPlan.Ready>(
                RotationStepPlanner.planStep(checkpoint, inputs(proposed), operationId, "#ABC123"),
            )
        preparedPlan =
            plan.copy(
                candidate = proposed,
                rotationStep = selected.candidate,
                effects = StudioList((plan.effects + EffectOwner.ACCENT).distinct().sortedBy { it.ordinal }),
            )
        val prepared = assertIs<StudioAcceptancePreparation.Prepared>(delegate.prepareAcceptance(request, preparedPlan))
        val lease =
            object : StudioRuntimeLease by prepared.lease {
                override suspend fun settledRuntime(candidate: StudioDocument): StudioRuntimeState {
                    completions++
                    val completed =
                        assertIs<RotationCompletionPlan.Ready>(
                            RotationStepPlanner.planCompletion(
                                assertNotNull(candidate.runtime.rotation),
                                inputs(candidate),
                                assertNotNull(prepared.plan.rotationStep),
                                completedAt,
                            ),
                        )
                    return candidate.runtime.copy(rotation = completed.checkpoint)
                }
            }
        return prepared.copy(lease = lease)
    }
}

private fun inputs(document: StudioDocument): RuntimeInputs {
    val current = document.currents.global
    return RuntimeInputs(
        RuntimeOwner(StudioTarget.Global, current.revision),
        current.appearance,
        RuntimeAvailability(
            lastDefinitivePro = true,
            effectiveFollowSystem = false,
            variant = AyuVariant.MIRAGE,
            targetValid = true,
            recoveryPending = false,
        ),
    )
}
