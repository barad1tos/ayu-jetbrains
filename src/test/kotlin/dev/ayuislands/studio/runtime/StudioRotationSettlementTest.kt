package dev.ayuislands.studio.runtime

import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.rotation.RotationIntent
import dev.ayuislands.rotation.RotationStepInputs
import dev.ayuislands.rotation.RotationStepPlan
import dev.ayuislands.rotation.RotationStepPlanner
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
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioLegacyCapture
import dev.ayuislands.studio.persistence.openStudioFiles
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
            val waiting =
                assertIs<RotationStepPhase.Recovering>(
                    settleRotationCommit(committing, prepared.token, CommitResolution.Unknown),
                )
            assertSame(waiting, settleRotationCommit(waiting, prepared.token, CommitResolution.Unknown))
            val finished =
                assertIs<RotationStepPhase.Accepted>(
                    settleRotationCommit(waiting, prepared.token, store.resolveOutcome(prepared.token)),
                )
            assertEquals(committing, finished.commit)
            assertEquals(committing.checkpoint, written.document.runtime.rotation)
            assertEquals(accepted.currents, written.document.currents)
            assertEquals(accepted.catalog, written.document.catalog)
            assertSame(finished, settleRotationCommit(finished, prepared.token, CommitResolution.Committed))
            assertEquals(JournalResult.Acknowledged, store.finishAccepted(prepared.token))
            assertEquals(StoreLoad.Ready(written.document), store.load())
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
            assertSame(committing, settleRotationCommit(committing, firstJournal.token, CommitResolution.Committed))
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
            val rejected =
                assertIs<RotationStepPhase.Restoring>(
                    settleRotationCommit(committing, retryJournal.token, store.resolveOutcome(retryJournal.token)),
                )
            assertEquals(RotationStepRestoration.CommitRejected, rejected.reason)
            assertEquals(accepted, assertIs<StoreLoad.Recovering>(store.load()).document)
            assertEquals(JournalResult.Acknowledged, store.finishRollback(retryJournal.token))
            assertEquals(StoreLoad.Ready(accepted), store.load())
        }
    }

    private fun open(): StudioAppearanceStore = StudioAppearanceStore(openStudioFiles(directory))

    private fun initialize(store: StudioAppearanceStore): StudioDocument {
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
        val document = assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(capture.snapshot)).document
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
