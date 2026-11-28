package dev.ayuislands.studio.runtime

import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.rotation.ClockSample
import dev.ayuislands.rotation.FullIntervalReason
import dev.ayuislands.rotation.RotationAvailability
import dev.ayuislands.rotation.RotationIntent
import dev.ayuislands.rotation.RotationResume
import dev.ayuislands.rotation.RotationRuntimeCommand
import dev.ayuislands.rotation.RotationRuntimeEvent
import dev.ayuislands.rotation.RotationRuntimeSnapshot
import dev.ayuislands.rotation.RotationRuntimeState
import dev.ayuislands.rotation.RotationStepInputs
import dev.ayuislands.rotation.RotationStepPlan
import dev.ayuislands.rotation.RotationStepPlanner
import dev.ayuislands.rotation.RotationTimerGeneration
import dev.ayuislands.rotation.transitionRotationRuntime
import dev.ayuislands.studio.FaultingStudioFiles
import dev.ayuislands.studio.IoBoundary
import dev.ayuislands.studio.IoEvent
import dev.ayuislands.studio.IoOperation
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.AcceptanceLease
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.LegacyArchive
import dev.ayuislands.studio.model.LegacyFile
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.MigrationSource
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.CommitResolution
import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.LegacyCapture
import dev.ayuislands.studio.persistence.PendingJournal
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.RecoveryPurpose
import dev.ayuislands.studio.persistence.StoreIssue
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioLegacyCapture
import dev.ayuislands.studio.persistence.openStudioFiles
import dev.ayuislands.studio.session.ApplyExecution
import dev.ayuislands.studio.session.ApplyFailure
import dev.ayuislands.studio.session.ApplySettlement
import dev.ayuislands.studio.session.SettlementProblem
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class StudioRotationSettlementTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `a matching effect proposes one step and a recovered commit publishes that exact checkpoint`() {
        open().use { store ->
            val accepted = initialize(store)
            val work = work(accepted)
            val prepared = prepare(store, accepted, work)
            val awaiting = RotationStepPhase.Preparing(work)
            val applying = assertIs<RotationStepPhase.Applying>(admitRotationEffects(awaiting, prepared, work.context))
            assertSame(applying, admitRotationEffects(applying, prepared, work.context))
            assertEquals(accepted, assertIs<StoreLoad.Recovering>(store.load()).document)

            val appliedAt = 24_000L
            val result = RotationEffectsOutcome.Applied(appliedAt)
            val committing =
                assertIs<RotationStepPhase.Committing>(
                    settleRotationEffects(applying, prepared.token, result, work.context),
                )
            assertSame(committing, settleRotationEffects(committing, prepared.token, result, work.context))
            assertEquals(work.context.checkpoint.successfulStepSerial + 1, committing.checkpoint.successfulStepSerial)
            assertEquals(work.candidate.hex, committing.checkpoint.output?.hex)
            assertEquals(appliedAt, committing.checkpoint.lastSuccessfulStepEpochMillis)
            assertEquals(appliedAt + 6 * 3_600_000L, committing.checkpoint.schedule?.deadlineEpochMillis)
            assertEquals(accepted, assertIs<StoreLoad.Recovering>(store.load()).document)

            val written =
                assertIs<CommitResult.Committed>(
                    store.commit(prepared.token, accepted.runtime.copy(rotation = committing.checkpoint)),
                )
            val missingAck = CommitResult.OutcomeUnknown(StoreIssue.IO)
            val waiting =
                assertIs<RotationStepPhase.Recovering>(
                    settleRotationCommit(committing, prepared.token, missingAck),
                )
            assertSame(waiting, settleRotationCommit(waiting, prepared.token, missingAck))
            assertEquals(CommitResolution.Committed, store.resolveOutcome(prepared.token))
            val readback = assertIs<StoreLoad.Recovering>(store.load())
            val acknowledged = CommitResult.Committed(requireNotNull(readback.document))
            val finished =
                assertIs<RotationStepPhase.Accepted>(
                    settleRotationCommit(waiting, prepared.token, acknowledged),
                )
            assertEquals(committing, finished.commit)
            assertEquals(committing.checkpoint, written.document.runtime.rotation)
            assertEquals(accepted.currents, written.document.currents)
            assertEquals(accepted.catalog, written.document.catalog)
            assertSame(finished, settleRotationCommit(finished, prepared.token, acknowledged))
            assertEquals(JournalResult.Acknowledged, store.finishAccepted(prepared.token))
            assertEquals(StoreLoad.Ready(written.document), store.load())
        }
    }

    @Test
    fun `a committed token with different runtime history leaves rotation recovery pending`() {
        open().use { store ->
            val accepted = initialize(store)
            val work = work(accepted)
            val journal = prepare(store, accepted, work)
            val applying =
                assertIs<RotationStepPhase.Applying>(
                    admitRotationEffects(RotationStepPhase.Preparing(work), journal, work.context),
                )
            val committing =
                assertIs<RotationStepPhase.Committing>(
                    settleRotationEffects(
                        applying,
                        journal.token,
                        RotationEffectsOutcome.Applied(24_000),
                        work.context,
                    ),
                )

            val written = assertIs<CommitResult.Committed>(store.commit(journal.token, accepted.runtime))
            assertEquals(accepted.runtime.rotation, written.document.runtime.rotation)
            val recovered = settleRotationCommit(committing, journal.token, written)
            assertIs<RotationStepPhase.Recovering>(recovered)
            assertEquals(written.document, assertIs<StoreLoad.Recovering>(store.load()).document)
        }
    }

    @Test
    fun `a retry with the same color ignores the original attempt journal and completion`() {
        open().use { store ->
            val accepted = initialize(store)
            val first = work(accepted)
            val firstJournal = prepare(store, accepted, first)
            assertEquals(JournalResult.Acknowledged, store.finishRollback(firstJournal.token))
            val retry = first.copy(operationId = UUID(0, 2))
            val retryJournal = prepare(store, accepted, retry)
            val preparing = RotationStepPhase.Preparing(retry)
            assertSame(preparing, admitRotationEffects(preparing, firstJournal, retry.context))
            val applying =
                assertIs<RotationStepPhase.Applying>(admitRotationEffects(preparing, retryJournal, retry.context))
            val outcome = RotationEffectsOutcome.Applied(24_000L)
            assertSame(applying, settleRotationEffects(applying, firstJournal.token, outcome, retry.context))
            val committing =
                assertIs<RotationStepPhase.Committing>(
                    settleRotationEffects(applying, retryJournal.token, outcome, retry.context),
                )
            assertEquals(first.candidate.hex, committing.checkpoint.output?.hex)
            val stale = CommitResult.NotCommitted(StoreIssue.STALE)
            assertSame(committing, settleRotationCommit(committing, firstJournal.token, stale))
        }
    }

    @Test
    fun `a changed lease makes either a prepared operation or a completed effect go to restoration`() {
        open().use { store ->
            val accepted = initialize(store)
            val work = work(accepted)
            val journal = prepare(store, accepted, work)
            val preparing = RotationStepPhase.Preparing(work)
            val newContext =
                work.context.copy(
                    lease =
                        work.context.lease.copy(
                            targetGeneration =
                                work.context.lease.targetGeneration + 1,
                        ),
                )
            val before = assertIs<RotationStepPhase.Restoring>(admitRotationEffects(preparing, journal, newContext))
            assertEquals(RotationStepRestoration.LostLease, before.reason)
            val applying = assertIs<RotationStepPhase.Applying>(admitRotationEffects(preparing, journal, work.context))
            val after =
                assertIs<RotationStepPhase.Restoring>(
                    settleRotationEffects(applying, journal.token, RotationEffectsOutcome.Applied(24_000), newContext),
                )
            assertEquals(RotationStepRestoration.LostLease, after.reason)
            assertEquals(journal.token, after.effect.token)
            assertEquals(accepted, assertIs<StoreLoad.Recovering>(store.load()).document)
        }
    }

    @Test
    fun `a failed effect or a proved uncommitted write leaves the accepted checkpoint intact`() {
        open().use { store ->
            val accepted = initialize(store)
            val work = work(accepted)
            val journal = prepare(store, accepted, work)
            val applying =
                assertIs<RotationStepPhase.Applying>(
                    admitRotationEffects(RotationStepPhase.Preparing(work), journal, work.context),
                )
            val failed =
                assertIs<RotationStepPhase.Restoring>(
                    settleRotationEffects(applying, journal.token, RotationEffectsOutcome.Rejected, work.context),
                )
            assertEquals(RotationStepRestoration.NativeFailure, failed.reason)
            assertEquals(JournalResult.Acknowledged, store.finishRollback(journal.token))
            assertEquals(StoreLoad.Ready(accepted), store.load())
            val retry = work.copy(operationId = UUID(0, 2))
            val retryJournal = prepare(store, accepted, retry)
            val retryEffect =
                assertIs<RotationStepPhase.Applying>(
                    admitRotationEffects(RotationStepPhase.Preparing(retry), retryJournal, retry.context),
                )
            val committing =
                assertIs<RotationStepPhase.Committing>(
                    settleRotationEffects(
                        retryEffect,
                        retryJournal.token,
                        RotationEffectsOutcome.Applied(24_000),
                        retry.context,
                    ),
                )
            assertEquals(CommitResolution.NotCommitted, store.resolveOutcome(retryJournal.token))
            val rejected =
                assertIs<RotationStepPhase.Restoring>(
                    settleRotationCommit(committing, retryJournal.token, CommitResult.NotCommitted(StoreIssue.IO)),
                )
            assertEquals(RotationStepRestoration.CommitRejected, rejected.reason)
            assertEquals(accepted, assertIs<StoreLoad.Recovering>(store.load()).document)
            assertEquals(JournalResult.Acknowledged, store.finishRollback(retryJournal.token))
            assertEquals(StoreLoad.Ready(accepted), store.load())
        }
    }

    @Test
    fun `schedule acceptance saves only the appointment and rejects an already consumed request`() {
        open().use { store ->
            val accepted = initialize(store, RotationResume.FullInterval(FullIntervalReason.FOLLOW_SYSTEM))
            val request = scheduleRequest(accepted)
            val attempt = UUID(0, 10)

            val execution = commitRotationSchedule(store, request, request.source, attempt)
            assertNull(execution.failure)
            val committed = assertIs<ApplySettlement.Accepted>(execution.settlement).document
            assertEquals(
                accepted.header.copy(revision = accepted.header.revision + 1, transactionId = attempt.toString()),
                committed.header,
            )
            assertEquals(accepted.runtime.copy(rotation = request.proposed.checkpoint), committed.runtime)
            assertEquals(accepted, committed.copy(header = accepted.header, runtime = accepted.runtime))
            assertEquals(StoreLoad.Ready(committed), store.load())

            assertEquals(
                ApplyExecution(ApplySettlement.NotAccepted, ApplyFailure.LeaseLost),
                commitRotationSchedule(store, request, request.source, UUID(0, 11)),
            )
            assertEquals(StoreLoad.Ready(committed), store.load())
        }
    }

    @Test
    fun `a schedule request loses admission when a fresh observation no longer allows rotation`() {
        open().use { store ->
            val accepted = initialize(store, RotationResume.FullInterval(FullIntervalReason.FOLLOW_SYSTEM))
            val request = scheduleRequest(accepted)
            val disabled = request.source.copy(intent = request.source.intent.copy(enabled = false))

            assertEquals(
                ApplyExecution(ApplySettlement.NotAccepted, ApplyFailure.LeaseLost),
                commitRotationSchedule(store, request, disabled, UUID(0, 12)),
            )
            assertEquals(StoreLoad.Ready(accepted), store.load())
        }
    }

    @Test
    fun `a proved failed checkpoint write removes its empty journal and preserves the previous appointment`() {
        val files = FaultingStudioFiles(openStudioFiles(directory))
        StudioAppearanceStore(files).use { store ->
            val accepted = initialize(store, RotationResume.FullInterval(FullIntervalReason.FOLLOW_SYSTEM))
            val request = scheduleRequest(accepted)
            files.arm(IoEvent(IoOperation.REPLACE, "state.xml", IoBoundary.BEFORE))

            assertEquals(
                ApplyExecution(ApplySettlement.NotAccepted, ApplyFailure.Commit(StoreIssue.IO)),
                commitRotationSchedule(store, request, request.source, UUID(0, 13)),
            )
            assertEquals(StoreLoad.Ready(accepted), store.load())
        }
    }

    @Test
    fun `an unconfirmed checkpoint readback retains its journal for recovery`() {
        val files = FaultingStudioFiles(openStudioFiles(directory))
        StudioAppearanceStore(files).use { store ->
            val accepted = initialize(store, RotationResume.FullInterval(FullIntervalReason.FOLLOW_SYSTEM))
            val request = scheduleRequest(accepted)
            val attempt = UUID(0, 14)
            files.arm(
                IoEvent(IoOperation.REPLACE, "state.xml", IoBoundary.AFTER),
                IoEvent(IoOperation.READ, "state.xml", IoBoundary.BEFORE),
            )

            val execution = commitRotationSchedule(store, request, request.source, attempt)
            assertIs<ApplySettlement.RecoveryRequired>(execution.settlement)
            assertIs<ApplyFailure.Commit>(execution.failure)
            val observed = assertIs<StoreLoad.Recovering>(store.load())
            assertEquals(RecoveryPurpose.RotationSchedule(attempt), observed.journal.recovery.purpose)
            assertEquals(emptyList(), observed.journal.receipts)
            assertEquals(request.proposed.checkpoint, observed.document?.runtime?.rotation)
            assertEquals(CommitResolution.Committed, store.resolveOutcome(observed.journal.token))
        }
    }

    @Test
    fun `a lost preparation acknowledgement retains the runtime journal without writing the appointment`() {
        val files = FaultingStudioFiles(openStudioFiles(directory))
        StudioAppearanceStore(files).use { store ->
            val accepted = initialize(store, RotationResume.FullInterval(FullIntervalReason.FOLLOW_SYSTEM))
            val request = scheduleRequest(accepted)
            val attempt = UUID(0, 16)
            files.arm(IoEvent(IoOperation.REPLACE, "pending.xml", IoBoundary.AFTER))

            val execution = commitRotationSchedule(store, request, request.source, attempt)
            assertEquals(
                ApplyExecution(
                    ApplySettlement.RecoveryRequired(SettlementProblem.Journal(StoreIssue.IO)),
                    ApplyFailure.Prepare(StoreIssue.IO),
                ),
                execution,
            )
            val retained = assertIs<StoreLoad.Recovering>(store.load())
            assertEquals(accepted, retained.document)
            assertEquals(RecoveryPurpose.RotationSchedule(attempt), retained.journal.recovery.purpose)
            assertEquals(CommitResolution.NotCommitted, store.resolveOutcome(retained.journal.token))
        }
    }

    @Test
    fun `a confirmed appointment with a pending journal is not reported as fully settled`() {
        val files = FaultingStudioFiles(openStudioFiles(directory))
        StudioAppearanceStore(files).use { store ->
            val accepted = initialize(store, RotationResume.FullInterval(FullIntervalReason.FOLLOW_SYSTEM))
            val request = scheduleRequest(accepted)
            files.arm(IoEvent(IoOperation.REMOVE, "pending.xml", IoBoundary.BEFORE))

            val execution = commitRotationSchedule(store, request, request.source, UUID(0, 15))
            assertNull(execution.failure)
            val pending = assertIs<ApplySettlement.AcceptedCleanupPending>(execution.settlement)
            assertEquals(SettlementProblem.Journal(StoreIssue.IO), pending.problem)
            assertEquals(request.proposed.checkpoint, pending.document.runtime.rotation)
            val observed = assertIs<StoreLoad.Recovering>(store.load())
            assertEquals(pending.document, observed.document)
            val blocked = commitRotationSchedule(store, request, request.source, UUID(0, 17))
            assertEquals(
                ApplyExecution(
                    ApplySettlement.RecoveryRequired(SettlementProblem.Journal(StoreIssue.RECOVERY_REQUIRED)),
                    ApplyFailure.Prepare(StoreIssue.RECOVERY_REQUIRED),
                ),
                blocked,
            )
            assertEquals(observed, store.load())
            assertEquals(JournalResult.Acknowledged, store.finishAccepted(observed.journal.token))
            assertEquals(StoreLoad.Ready(pending.document), store.load())
        }
    }

    private fun scheduleRequest(document: StudioDocument): RotationRuntimeCommand.PersistCheckpoint {
        val snapshot =
            RotationRuntimeSnapshot(
                checkpoint = requireNotNull(document.runtime.rotation),
                intent = RotationIntent(true, "PRESET", 6),
                availability = RotationAvailability(LicenseEntitlement.LICENSED, false, AyuVariant.MIRAGE, true, false),
                generation = RotationTimerGeneration(service = 2, target = 3),
            )
        val transition =
            transitionRotationRuntime(
                RotationRuntimeState.Loading,
                RotationRuntimeEvent.Reconcile(snapshot),
                ClockSample(3_600_000L, 0),
            )
        return assertIs<RotationRuntimeCommand.PersistCheckpoint>(transition.commands.single())
    }

    private fun open(): StudioAppearanceStore = StudioAppearanceStore(openStudioFiles(directory))

    private fun initialize(
        store: StudioAppearanceStore,
        resume: RotationResume? = null,
    ): StudioDocument {
        val settings =
            """
            <application>
              <component name="AyuIslandsSettings">
                <option name="accentRotationEnabled" value="true"/>
                <option name="accentRotationMode" value="PRESET"/>
                <option name="accentRotationIntervalHours" value="6"/>
              </component>
            </application>
            """.trimIndent().encodeToByteArray()
        val source =
            MigrationSource(
                "rotation-settlement",
                StudioList(
                    LegacyStore.entries.map { legacy ->
                        val file =
                            if (legacy ==
                                LegacyStore.SETTINGS
                            ) {
                                LegacyFile.Present(StudioBytes(settings))
                            } else {
                                LegacyFile.Absent
                            }
                        LegacyArchive(legacy, file)
                    },
                ),
            )
        val capture = assertIs<LegacyCapture.Ready>(StudioLegacyCapture.read(source, "initial", "/work"))
        val migrated = assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(capture.snapshot)).document
        val document =
            if (resume == null) {
                migrated
            } else {
                val checkpoint = requireNotNull(migrated.runtime.rotation).copy(resume = resume)
                migrated.copy(runtime = migrated.runtime.copy(rotation = checkpoint))
            }
        val target = requireNotNull(document.runtime.rotation).owner.target
        assertEquals(StoreLoad.Empty, store.load())
        assertEquals(JournalResult.Acknowledged, store.preserveSource(source))
        val initial =
            assertIs<PrepareResult.Prepared>(
                store.prepare(-1, document, RecoveryPlan(target, StudioList(emptyList()))),
            )
        assertEquals(CommitResult.Committed(document), store.commit(initial.token, document.runtime))
        assertEquals(JournalResult.Acknowledged, store.finishAccepted(initial.token))
        return document
    }

    private fun work(document: StudioDocument): RotationStepWork {
        val checkpoint = requireNotNull(document.runtime.rotation)
        val inputs = RotationStepInputs(checkpoint.owner, RotationIntent(true, "PRESET", 6), AyuVariant.MIRAGE)
        val stamp = AcceptanceLease(document.header.revision, targetGeneration = 1, capabilityRevision = 1)
        val operation = UUID(0, 1)
        val plan = assertIs<RotationStepPlan.Ready>(RotationStepPlanner.planStep(checkpoint, inputs, operation))
        return RotationStepWork(operation, plan.candidate, RotationStepContext(checkpoint, inputs, stamp))
    }

    private fun prepare(
        store: StudioAppearanceStore,
        accepted: StudioDocument,
        work: RotationStepWork,
    ): PendingJournal {
        val staged =
            accepted.copy(
                header =
                    accepted.header.copy(
                        revision = accepted.header.revision + 1,
                        transactionId = work.operationId.toString(),
                    ),
            )
        val recovery =
            RecoveryPlan(
                work.candidate.owner.target,
                StudioList(listOf(EffectOwner.ACCENT)),
                rotationStep = work.candidate,
                purpose = RecoveryPurpose.RotationStep(work.operationId),
            )
        return assertIs<PrepareResult.Prepared>(store.prepare(accepted.header.revision, staged, recovery)).journal
    }
}
