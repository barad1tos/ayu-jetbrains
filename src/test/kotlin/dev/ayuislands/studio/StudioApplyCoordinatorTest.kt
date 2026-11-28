package dev.ayuislands.studio

import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ResolvedAccent
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.rotation.AccentRotationMode
import dev.ayuislands.rotation.ClockSample
import dev.ayuislands.rotation.FullIntervalReason
import dev.ayuislands.rotation.RotationAvailability
import dev.ayuislands.rotation.RotationIntent
import dev.ayuislands.rotation.RotationResume
import dev.ayuislands.rotation.RotationRuntimeCheckpoint
import dev.ayuislands.rotation.RotationRuntimeCommand
import dev.ayuislands.rotation.RotationRuntimeEvent
import dev.ayuislands.rotation.RotationRuntimeSnapshot
import dev.ayuislands.rotation.RotationRuntimeState
import dev.ayuislands.rotation.RotationTimerGeneration
import dev.ayuislands.rotation.RuntimeOwner
import dev.ayuislands.rotation.transitionRotationRuntime
import dev.ayuislands.studio.model.AcceptanceBlock
import dev.ayuislands.studio.model.AcceptanceLease
import dev.ayuislands.studio.model.AcceptanceObservation
import dev.ayuislands.studio.model.ApplyContext
import dev.ayuislands.studio.model.ApplyRequest
import dev.ayuislands.studio.model.AuthorityStatus
import dev.ayuislands.studio.model.DraftTracking
import dev.ayuislands.studio.model.EffectObservation
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioAccentContinuation
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioEffectiveContinuation
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioRuntimeState
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.TargetObservation
import dev.ayuislands.studio.model.TargetPresence
import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.NativeValue
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.persistence.StoreIssue
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioDraftStore
import dev.ayuislands.studio.persistence.StudioFiles
import dev.ayuislands.studio.persistence.openStudioFiles
import dev.ayuislands.studio.runtime.RotationCommandOutcome
import dev.ayuislands.studio.runtime.RotationObservation
import dev.ayuislands.studio.runtime.RotationStepAdmission
import dev.ayuislands.studio.runtime.RotationStepAttempt
import dev.ayuislands.studio.runtime.StudioRotationCommands
import dev.ayuislands.studio.runtime.prepareRotationStep
import dev.ayuislands.studio.session.ApplyExecution
import dev.ayuislands.studio.session.ApplyFailure
import dev.ayuislands.studio.session.ApplyInputs
import dev.ayuislands.studio.session.ApplySettlement
import dev.ayuislands.studio.session.RestoreContinuation
import dev.ayuislands.studio.session.SettlementProblem
import dev.ayuislands.studio.session.StudioApplyCoordinator
import dev.ayuislands.studio.session.StudioApplyLease
import dev.ayuislands.studio.session.StudioNativeWrite
import dev.ayuislands.studio.session.StudioOwnerOperation
import dev.ayuislands.studio.session.Submission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class StudioApplyCoordinatorTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `flush shares the queue and native checkpoints accept after durable commit`() =
        exercise { fixture ->
            val edit = editedJournalRecord(fixture.initial)
            val execution = fixture.applyDraft(edit)
            assertEquals(execution, fixture.applyDraft(edit))
            assertEquals(
                Submission.Blocked(AcceptanceBlock.INVALID_REQUEST),
                fixture.coordinator.submit(ApplyRequest(edit, 2), APPLY),
            )
            fixture.assertAccepted(edit, execution)
            assertTrue(fixture.coordinator.acknowledge(APPLY))
            assertNull(fixture.coordinator.outcome(APPLY))
        }

    @Test
    fun `a native mutation followed by failure restores the old appearance and keeps the draft`() =
        exercise { fixture ->
            fixture.failApply = true
            assertIs<ApplyFailure.Effects>(fixture.applyAndAssertRollback())
        }

    @Test
    fun `cancellation after commit retains the accepted appearance and settles without a second write`() =
        exercise { fixture ->
            fixture.cancelAcceptance = true
            val edit = editedJournalRecord(fixture.initial)
            assertFailsWith<CancellationException> { fixture.coordinator.submit(ApplyRequest(edit, 1), APPLY) }
            fixture.assertAccepted(edit, requireNotNull(fixture.coordinator.outcome(APPLY)))
        }

    @Test
    fun `new Apply settles retained cleanup even when the store already looks ready`() =
        exercise { fixture ->
            fixture.loseApplyCleanup = true
            val first = ApplyRequest(editedJournalRecord(fixture.initial), 1)
            val execution = assertIs<Submission.Admitted<ApplyExecution>>(fixture.coordinator.submit(first, APPLY))
            assertIs<ApplySettlement.AcceptedCleanupPending>(execution.result.settlement)
            assertFalse(fixture.coordinator.acknowledge(APPLY))
            val committed = assertIs<StoreLoad.Ready>(fixture.store.load()).document
            val next = ApplyRequest(committed.sessions.single(), 1)
            val settledEvents = fixture.events.toList()
            fixture.failNextCleanupRead()
            val retained = assertIs<Submission.Retained>(fixture.coordinator.submit(next, NEXT))
            assertEquals(APPLY, retained.operation)
            assertIs<ApplySettlement.AcceptedCleanupPending>(retained.execution.settlement)
            assertEquals(settledEvents, fixture.events)
            assertNull(fixture.coordinator.outcome(NEXT))
            fixture.expectPriorSettledOnFlush = true
            assertEquals(Submission.NoChanges, fixture.coordinator.submit(next, NEXT))
            val recovered = assertIs<ApplySettlement.Accepted>(fixture.coordinator.retry(APPLY)?.settlement)
            assertEquals(committed, recovered.document)
            assertTrue(fixture.coordinator.acknowledge(APPLY))
            assertEquals(listOf("flush", "acquire", "apply", "accept", "release", "flush"), fixture.events)
        }

    @Test
    fun `lost prepare acknowledgement binds only the exact journal and does not write a native field`() =
        exercise { fixture ->
            fixture.loseApplyPrepare = true
            val edit = editedJournalRecord(fixture.initial)
            val execution = fixture.applyDraft(edit)
            assertEquals(ApplySettlement.NotAccepted, execution.settlement)
            assertIs<ApplyFailure.Prepare>(execution.failure)
            assertEquals(listOf("flush", "acquire", "release"), fixture.events)
            fixture.assertUnchanged(edit)
        }

    @Test
    fun `lease loss during runtime projection restores native settings before publication`() =
        exercise { fixture ->
            fixture.loseLeaseBeforeCommit = true
            assertEquals(ApplyFailure.LeaseLost, fixture.applyAndAssertRollback())
        }

    @Test
    fun `lease loss on the commit dispatcher preserves the draft and old appearance`() =
        exercise { fixture ->
            fixture.loseLeaseOnCommitDispatch = true
            assertEquals(ApplyFailure.LeaseLost, fixture.applyAndAssertRollback())
        }

    @Test
    fun `runtime projection failure restores native settings and retains the saved draft`() =
        exercise { fixture ->
            val failure = IllegalStateException("Host closed during runtime projection")
            fixture.projectionFailure = failure
            assertEquals(ApplyFailure.Native(failure), fixture.applyAndAssertRollback())
        }

    @Test
    fun `a failed state replacement leaves the previous appearance and draft intact`() =
        exercise { fixture ->
            fixture.publicationFault = PublicationFault.REJECTED
            assertEquals(ApplyFailure.Commit(StoreIssue.IO), fixture.applyAndAssertRollback())
        }

    @Test
    fun `unknown write acknowledgement settles the accepted state once it is readable`() =
        exercise { fixture ->
            fixture.publicationFault = PublicationFault.ACKNOWLEDGEMENT_LOST
            val edit = editedJournalRecord(fixture.initial)
            fixture.assertAccepted(edit, fixture.applyDraft(edit))
        }

    @Test
    fun `a journaled rotation keeps its output after a lost commit acknowledgement and applies once`() =
        exercise(document = rotationDocument(retainAccent = true)) { fixture ->
            val prepared = fixture.prepareRotation()
            fixture.publicationFault = PublicationFault.ACKNOWLEDGEMENT_LOST

            val execution = fixture.applyRotation(prepared)
            val accepted = assertIs<ApplySettlement.Accepted>(execution.settlement).document
            assertNull(execution.failure)
            val rotation = requireNotNull(accepted.runtime.rotation)
            val candidate = prepared.phase.work.candidate
            assertEquals(candidate.hex, rotation.output?.hex)
            assertEquals(candidate.nextCursor, rotation.sequenceCursorRaw)
            assertEquals(1L, rotation.successfulStepSerial)
            assertEquals(ROTATION_COMPLETED_AT, rotation.lastSuccessfulStepEpochMillis)
            assertEquals(ROTATION_COMPLETED_AT, rotation.schedule?.scheduledAtEpochMillis)
            assertEquals(
                fixture.initial,
                accepted.copy(header = fixture.initial.header, runtime = fixture.initial.runtime),
            )
            assertEquals(
                requireNotNull(fixture.initial.runtime.effectiveContinuation).copy(accent = null),
                accepted.runtime.effectiveContinuation,
            )
            assertEquals(StoreLoad.Ready(accepted), fixture.store.load())
            assertEquals(execution, fixture.applyRotation(prepared))
            assertTrue(fixture.native)
            assertEquals(listOf("acquire", "apply", "accept", "release"), fixture.events)
        }

    @Test
    fun `a failed Accent write restores the recorded field and leaves accepted rotation history alone`() =
        exercise(document = rotationDocument()) { fixture ->
            val prepared = fixture.prepareRotation()
            fixture.failApply = true

            val execution = fixture.applyRotation(prepared)
            assertEquals(ApplySettlement.NotAccepted, execution.settlement)
            assertIs<ApplyFailure.Effects>(execution.failure)
            assertEquals(StoreLoad.Ready(fixture.initial), fixture.store.load())
            assertFalse(fixture.native)
            assertEquals(listOf("acquire", "apply", "restore", "release"), fixture.events)
        }

    @Test
    fun `a journaled Accent step whose lease has expired releases it before the first write`() =
        exercise(document = rotationDocument()) { fixture ->
            val prepared = fixture.prepareRotation()
            fixture.expireLeaseBeforePrepare = true

            val execution = fixture.applyRotation(prepared)
            assertEquals(ApplyExecution(ApplySettlement.NotAccepted, ApplyFailure.LeaseLost), execution)
            assertEquals(StoreLoad.Ready(fixture.initial), fixture.store.load())
            assertFalse(fixture.native)
            assertEquals(listOf("acquire", "release"), fixture.events)
        }

    @Test
    fun `pending rotation cleanup settles the same commit without reacquiring its Accent operation`() =
        exercise(document = rotationDocument()) { fixture ->
            val prepared = fixture.prepareRotation()
            fixture.loseApplyCleanup = true
            val first = fixture.applyRotation(prepared)
            val pending = assertIs<ApplySettlement.AcceptedCleanupPending>(first.settlement)
            assertFalse(fixture.coordinator.acknowledge(APPLY))

            val finished = assertIs<ApplySettlement.Accepted>(fixture.applyRotation(prepared).settlement)
            assertEquals(pending.document, finished.document)
            assertEquals(listOf("acquire", "apply", "accept", "release"), fixture.events)
            assertEquals(StoreLoad.Ready(finished.document), fixture.store.load())
        }

    @Test
    fun `a previously aborted journal cannot reacquire the Accent lease`() =
        exercise(document = rotationDocument()) { fixture ->
            val prepared = fixture.prepareRotation()
            assertEquals(JournalResult.Acknowledged, fixture.store.finishRollback(prepared.journal.token))

            assertIs<ApplySettlement.RecoveryRequired>(fixture.applyRotation(prepared).settlement)
            assertEquals(emptyList(), fixture.events)
            assertEquals(StoreLoad.Ready(fixture.initial), fixture.store.load())
        }

    @Test
    fun `closing the owner during post-commit settlement keeps the accepted rotation`() =
        exercise(document = rotationDocument()) { fixture ->
            val prepared = fixture.prepareRotation()
            fixture.cancelAcceptance = true

            assertFailsWith<CancellationException> { fixture.applyRotation(prepared) }
            val accepted = assertIs<ApplySettlement.Accepted>(fixture.coordinator.outcome(APPLY)?.settlement).document
            assertEquals(
                prepared.phase.work.candidate.hex,
                accepted.runtime.rotation
                    ?.output
                    ?.hex,
            )
            assertEquals(StoreLoad.Ready(accepted), fixture.store.load())
            assertTrue(fixture.native)
            assertEquals(listOf("acquire", "apply", "accept", "release"), fixture.events)
        }

    @Test
    fun `failure to capture an Accent lease rolls back the untouched journal`() =
        exercise(document = rotationDocument()) { fixture ->
            val prepared = fixture.prepareRotation()
            val failure = IllegalStateException("The captured window closed before acquiring the lease")
            val execution =
                fixture.coordinator.submitPreparedRotation(
                    prepared,
                    acquire = { throw failure },
                    completedAt = { error("An unstarted step cannot complete") },
                )
            assertEquals(ApplySettlement.NotAccepted, execution.settlement)
            val reported = assertIs<ApplyFailure.Native>(execution.failure).cause
            assertEquals(failure.message, assertIs<IllegalStateException>(reported).message)
            assertEquals(StoreLoad.Ready(fixture.initial), fixture.store.load())
            assertEquals(emptyList(), fixture.events)
        }

    @Test
    fun `focus moving after the Accent write restores that field before committing rotation history`() =
        exercise(document = rotationDocument()) { fixture ->
            val prepared = fixture.prepareRotation()
            fixture.loseLeaseBeforeCommit = true

            val execution = fixture.applyRotation(prepared)
            assertEquals(ApplyExecution(ApplySettlement.NotAccepted, ApplyFailure.LeaseLost), execution)
            assertEquals(StoreLoad.Ready(fixture.initial), fixture.store.load())
            assertFalse(fixture.native)
            assertEquals(listOf("acquire", "apply", "restore", "release"), fixture.events)
        }

    @Test
    fun `expired lease keeps its draft when the state file is temporarily unreadable`() =
        exercise { fixture ->
            fixture.expireLeaseBeforePrepare = true
            val edit = editedJournalRecord(fixture.initial)
            val first = fixture.applyDraft(edit)
            assertIs<SettlementProblem.Journal>(assertIs<ApplySettlement.RecoveryRequired>(first.settlement).problem)
            assertEquals(ApplyFailure.LeaseLost, first.failure)
            assertFalse(fixture.coordinator.acknowledge(APPLY))
            assertEquals(ApplySettlement.NotAccepted, fixture.applyDraft(edit).settlement)
            assertEquals(listOf("flush", "acquire", "release"), fixture.events)
            fixture.assertUnchanged(edit)
            assertTrue(fixture.coordinator.acknowledge(APPLY))
            assertNull(fixture.coordinator.retry(APPLY))
        }

    @Test
    fun `a reducer appointment command writes its checkpoint without acquiring native effects`() =
        exercise(
            document = rotationDocument(resume = RotationResume.FullInterval(FullIntervalReason.FOLLOW_SYSTEM)),
        ) { fixture ->
            val mode = AccentRotationMode.PRESET
            val command = assertIs<RotationRuntimeCommand.PersistCheckpoint>(fixture.issueRotation(mode))
            val commands = fixture.rotationCommands(observe = { fixture.observeRotation(mode) })

            val result = assertIs<RotationCommandOutcome.Executed>(commands.execute(command))
            assertNull(result.step)
            val accepted = assertIs<ApplySettlement.Accepted>(result.execution.settlement).document
            assertEquals(command.proposed.checkpoint, accepted.runtime.rotation)
            assertEquals(StoreLoad.Ready(accepted), fixture.store.load())
            assertEquals(emptyList(), fixture.events)
        }

    @Test
    fun `a due random command uses one draw and applies the frozen candidate through shared Apply`() =
        exercise(document = rotationDocument()) { fixture ->
            val mode = AccentRotationMode.RANDOM
            val command = assertIs<RotationRuntimeCommand.StepDue>(fixture.issueRotation(mode))
            var drawn = 0
            val commands =
                fixture.rotationCommands(
                    observe = { fixture.observeRotation(mode) },
                    randomColor = { variant ->
                        assertEquals(AyuVariant.MIRAGE, variant)
                        drawn++
                        "#AABBCC"
                    },
                )

            val result = assertIs<RotationCommandOutcome.Executed>(commands.execute(command))
            assertEquals("#AABBCC", result.step?.candidate?.hex)
            val accepted = assertIs<ApplySettlement.Accepted>(result.execution.settlement).document
            assertEquals(
                "#AABBCC",
                accepted.runtime.rotation
                    ?.output
                    ?.hex,
            )
            assertEquals(StoreLoad.Ready(accepted), fixture.store.load())
            assertEquals(RotationCommandOutcome.Rejected(RotationStepAdmission.Stale), commands.execute(command))
            assertEquals(1, drawn)
            assertEquals(listOf("acquire", "apply", "accept", "release"), fixture.events)
        }

    @Test
    fun `a changed effective context during color generation rejects the old command before writing`() =
        exercise(document = rotationDocument()) { fixture ->
            val mode = AccentRotationMode.RANDOM
            val command = assertIs<RotationRuntimeCommand.StepDue>(fixture.issueRotation(mode))
            var followingSystem = false
            val commands =
                fixture.rotationCommands(
                    observe = { fixture.observeRotation(mode, followingSystem) },
                    randomColor = {
                        followingSystem = true
                        "#AABBCC"
                    },
                )
            assertEquals(RotationCommandOutcome.Rejected(RotationStepAdmission.Stale), commands.execute(command))
            assertEquals(StoreLoad.Ready(fixture.initial), fixture.store.load())
            assertEquals(emptyList(), fixture.events)
        }

    @Test
    fun `cancellation just after preparing a command settles its untouched journal`() =
        exercise(document = rotationDocument()) { fixture ->
            val mode = AccentRotationMode.PRESET
            val command = assertIs<RotationRuntimeCommand.StepDue>(fixture.issueRotation(mode))
            val commands = fixture.rotationCommands(observe = { fixture.observeRotation(mode) })

            coroutineScope {
                val running = launch(start = CoroutineStart.LAZY) { commands.execute(command) }
                fixture.afterNextPreparation { running.cancel() }
                running.start()
                running.join()
                assertTrue(running.isCancelled)
            }
            assertEquals(
                ApplyExecution(ApplySettlement.NotAccepted, ApplyFailure.Cancelled),
                fixture.coordinator.outcome(APPLY),
            )
            assertEquals(StoreLoad.Ready(fixture.initial), fixture.store.load())
            assertFalse(fixture.native)
            assertEquals(listOf("acquire", "release"), fixture.events)
        }

    private suspend fun Fixture.applyDraft(edit: StudioSessionRecord): ApplyExecution =
        assertIs<Submission.Admitted<ApplyExecution>>(coordinator.submit(ApplyRequest(edit, 1), APPLY)).result

    private suspend fun Fixture.applyAndAssertRollback(): ApplyFailure? {
        val edit = editedJournalRecord(initial)
        val execution = applyDraft(edit)
        assertEquals(ApplySettlement.NotAccepted, execution.settlement)
        assertEquals(listOf("flush", "acquire", "apply", "restore", "release"), events)
        assertUnchanged(edit)
        return execution.failure
    }

    private fun Fixture.assertUnchanged(edit: StudioSessionRecord) {
        val stored = assertIs<StoreLoad.Ready>(store.load()).document
        assertEquals(initial.currents, stored.currents)
        assertEquals(listOf(edit), stored.sessions)
        assertFalse(native)
    }

    private fun Fixture.assertAccepted(
        edit: StudioSessionRecord,
        execution: ApplyExecution,
    ) {
        val stored = assertIs<StoreLoad.Ready>(store.load()).document
        assertEquals(assertIs<ApplySettlement.Accepted>(execution.settlement).document, stored)
        assertNull(execution.failure)
        assertTrue(native)
        assertEquals(listOf("flush", "acquire", "apply", "accept", "release"), events)
        val draft = edit.work.drafts.single()
        val expected =
            initial.currents.global.appearance
                .copy(glow = draft.appearance.glow)
        assertEquals(expected, stored.currents.global.appearance)
        assertEquals(initial.currents.projects, stored.currents.projects)
        assertEquals(initial.catalog, stored.catalog)
        assertEquals(initial.shared, stored.shared)
        assertEquals(initial.auxiliary, stored.auxiliary)
        val saved = stored.sessions.single()
        assertEquals(edit.id, saved.id)
        assertEquals(edit.target, saved.target)
        assertEquals(edit.generation + 1, saved.generation)
        assertEquals(stored.header.revision, saved.baseDocumentRevision)
        assertEquals(edit.work.selected, saved.work.selected)
        assertEquals(
            draft.copy(base = stored.currents.global, appearance = expected, tracking = DraftTracking(expected)),
            saved.work.drafts.single(),
        )
    }

    private fun exercise(
        document: StudioDocument = catalogTestDocument(),
        block: suspend (Fixture) -> Unit,
    ) {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            Fixture(directory, dispatcher, document).use { fixture ->
                runBlocking { withTimeout(5.seconds) { block(fixture) } }
            }
        }
    }

    @Test
    fun `cancelled Apply releases the queue after bounded recovery and retains committed state`() =
        exercise { fixture ->
            fixture.pauseRelease = true
            val edit = editedJournalRecord(fixture.initial)
            coroutineScope {
                val apply = launch { fixture.coordinator.submit(ApplyRequest(edit, 1), APPLY) }
                fixture.releaseStarted.await()
                apply.cancel()
                val finished =
                    withTimeoutOrNull(2.seconds) {
                        apply.join()
                        true
                    }
                fixture.resumeRelease.complete(Unit)
                assertEquals(true, finished)
            }
            assertIs<ApplySettlement.AcceptedCleanupPending>(fixture.coordinator.outcome(APPLY)?.settlement)
            assertFalse(fixture.coordinator.acknowledge(APPLY))
            fixture.pauseRelease = false
            assertIs<ApplySettlement.Accepted>(fixture.coordinator.retry(APPLY)?.settlement)
            assertTrue(fixture.native)
            assertEquals(1, fixture.events.count { it == "accept" })
            assertEquals(1, fixture.events.count { it == "apply" })
        }

    private class Fixture(
        directory: Path,
        private val dispatcher: CoroutineDispatcher,
        val initial: StudioDocument,
    ) : AutoCloseable {
        private val files = Faults(openStudioFiles(directory))
        val store = StudioAppearanceStore(files)
        val events = mutableListOf<String>()
        var native = false
        var failApply = false
        var cancelAcceptance = false
        var loseApplyCleanup = false
        var loseApplyPrepare = false
        var loseLeaseBeforeCommit = false
        var loseLeaseOnCommitDispatch = false
        var expireLeaseBeforePrepare = false
        var projectionFailure: RuntimeException? = null
        var publicationFault: PublicationFault? = null
        var pauseRelease = false
        val releaseStarted = CompletableDeferred<Unit>()
        val resumeRelease = CompletableDeferred<Unit>()
        var expectPriorSettledOnFlush = false
        private val queue = Mutex()
        private var leaseValid = true
        private val invalidateOnNextIo = AtomicBoolean()
        private val ioDispatcher =
            object : CoroutineDispatcher() {
                override fun dispatch(
                    context: CoroutineContext,
                    block: Runnable,
                ) {
                    dispatcher.dispatch(context) {
                        if (invalidateOnNextIo.getAndSet(false)) leaseValid = false
                        block.run()
                    }
                }
            }
        private val drafts = StudioDraftStore(store)
        val coordinator: StudioApplyCoordinator

        init {
            assertEquals(StoreLoad.Empty, store.load())
            assertEquals(JournalResult.Acknowledged, store.preserveSource(initial.header.migration))
            val bootstrap = RecoveryPlan(StudioTarget.Global, StudioList(emptyList()))
            val token = assertIs<PrepareResult.Prepared>(store.prepare(-1, initial, bootstrap)).token
            assertIs<CommitResult.Committed>(store.commit(token, initial.runtime))
            assertEquals(JournalResult.Acknowledged, store.finishAccepted(token))
            coordinator =
                StudioApplyCoordinator(
                    store,
                    queue,
                    ioDispatcher,
                    flush = { record ->
                        if (expectPriorSettledOnFlush) {
                            assertIs<ApplySettlement.Accepted>(
                                coordinator.outcome(APPLY)?.settlement,
                            )
                        }
                        queue.withLock {
                            events.add("flush")
                            drafts.save(record, "draft-${events.size}")
                        }
                    },
                    observe = { request, _ ->
                        ApplyInputs(
                            ApplyContext(
                                AcceptanceObservation(
                                    AuthorityStatus.READY,
                                    TargetObservation(request.session.target, 1, TargetPresence.ACTIVE),
                                ),
                                StudioList(emptyList()),
                            ),
                            EffectObservation(1),
                        )
                    },
                    acquire = {
                        assertTrue(queue.isLocked)
                        events.add("acquire")
                        files.losePrepare = loseApplyPrepare
                        if (expireLeaseBeforePrepare) {
                            leaseValid = false
                            files.readFailurePath = "state.xml"
                        }
                        lease()
                    },
                    recover = { _, _ -> error("Live Apply still owns the recovery handles") },
                    cancelSettlementTimeout = 250.milliseconds,
                )
        }

        fun prepareRotation(): RotationStepAdmission.Prepared {
            val observation = requireNotNull(observeRotation(AccentRotationMode.PRESET))
            val request = assertIs<RotationRuntimeCommand.StepDue>(issueRotation(AccentRotationMode.PRESET))
            return assertIs<RotationStepAdmission.Prepared>(
                prepareRotationStep(
                    store,
                    request,
                    observation.snapshot,
                    observation.lease,
                    RotationStepAttempt(APPLY),
                ),
            )
        }

        fun observeRotation(
            mode: AccentRotationMode,
            followingSystem: Boolean = false,
        ): RotationObservation? {
            val accepted = (store.load() as? StoreLoad.Ready)?.document ?: return null
            val snapshot =
                RotationRuntimeSnapshot(
                    checkpoint = requireNotNull(accepted.runtime.rotation),
                    intent = RotationIntent(true, mode.name, 6),
                    availability =
                        RotationAvailability(
                            LicenseEntitlement.LICENSED,
                            followingSystem,
                            AyuVariant.MIRAGE,
                            true,
                            false,
                        ),
                    generation = RotationTimerGeneration(service = 1, target = 1),
                )
            val stamp = AcceptanceLease(accepted.header.revision, targetGeneration = 1, capabilityRevision = 1)
            return RotationObservation(snapshot, stamp)
        }

        fun issueRotation(mode: AccentRotationMode): RotationRuntimeCommand {
            val snapshot = requireNotNull(observeRotation(mode)).snapshot
            val transition =
                transitionRotationRuntime(
                    RotationRuntimeState.Loading,
                    RotationRuntimeEvent.Reconcile(snapshot),
                    ClockSample(0L, 0L),
                )
            return transition.commands.single()
        }

        fun rotationCommands(
            observe: suspend () -> RotationObservation?,
            randomColor: (AyuVariant) -> String = { error("Preset rotation does not require a color draw") },
        ): StudioRotationCommands =
            StudioRotationCommands(
                store = store,
                queue = queue,
                ioDispatcher = ioDispatcher,
                observe = observe,
                submitStep = { prepare ->
                    coordinator.submitRotation(
                        prepare,
                        acquire = { rotationLease() },
                        completedAt = { ROTATION_COMPLETED_AT },
                    )
                },
                operationId = { APPLY },
                randomColor = randomColor,
            )

        suspend fun applyRotation(prepared: RotationStepAdmission.Prepared): ApplyExecution =
            coordinator.submitPreparedRotation(
                prepared,
                acquire = { work ->
                    assertEquals(prepared.phase.work, work)
                    rotationLease()
                },
                completedAt = { ROTATION_COMPLETED_AT },
            )

        private fun rotationLease(): StudioApplyLease {
            assertTrue(queue.isLocked)
            events.add("acquire")
            files.publicationFault = publicationFault
            leaseValid = !expireLeaseBeforePrepare
            return lease(EffectOwner.ACCENT)
        }

        private fun lease(effect: EffectOwner = EffectOwner.GLOW): StudioApplyLease =
            object : StudioApplyLease {
                override suspend fun qualifies(): Boolean = leaseValid

                override suspend fun capture(owner: EffectOwner): StudioOwnerOperation {
                    assertEquals(effect, owner)
                    return nativeOperation(owner)
                }

                override suspend fun settledRuntime(candidate: StudioDocument): StudioRuntimeState {
                    projectionFailure?.let { throw it }
                    files.publicationFault = publicationFault
                    leaseValid = !loseLeaseBeforeCommit
                    invalidateOnNextIo.set(loseLeaseOnCommitDispatch)
                    return candidate.runtime
                }

                override suspend fun release(accepted: StudioDocument?) {
                    if (pauseRelease) {
                        releaseStarted.complete(Unit)
                        resumeRelease.await()
                    }
                    events.add("release")
                }
            }

        private fun nativeOperation(owner: EffectOwner): StudioOwnerOperation =
            object : StudioOwnerOperation {
                private var receipt =
                    PortableReceipt(
                        id = owner.name.lowercase(),
                        owner = owner,
                        identity = NativeFieldIdentity("editor", owner.name.lowercase(), "test-window"),
                        before = NativeValue.Present(RawValue.BooleanValue(false)),
                        lastWritten = NativeValue.Present(RawValue.BooleanValue(true)),
                        status = RestoreStatus.PENDING,
                    )
                override val receipts: List<PortableReceipt> get() = listOf(receipt)

                override suspend fun apply(writes: StudioNativeWrite): Boolean =
                    writes.execute(dispatcher) {
                        val pending = assertIs<StoreLoad.Recovering>(store.load())
                        assertEquals(receipt, pending.journal.receipts.single())
                        events.add("apply")
                        native = true
                        if (failApply) error("Native mutation failed after changing the value")
                        receipt = receipt.copy(status = RestoreStatus.OWNED)
                        if (owner == EffectOwner.ACCENT && loseLeaseBeforeCommit) leaseValid = false
                    }

                override suspend fun restore(blocked: List<PortableReceipt>): RestoreContinuation {
                    events.add("restore")
                    native = false
                    receipt = receipt.copy(status = RestoreStatus.RESTORED)
                    return RestoreContinuation.COMPLETE
                }

                override suspend fun accept() {
                    val pending = assertIs<StoreLoad.Recovering>(store.load())
                    assertEquals(pending.journal.candidate, pending.document)
                    if (cancelAcceptance) {
                        cancelAcceptance = false
                        throw CancellationException("Host closed after commit")
                    }
                    events.add("accept")
                    files.loseCleanup = loseApplyCleanup
                }
            }

        fun failNextCleanupRead() {
            files.readFailurePath = "pending.xml"
        }

        fun afterNextPreparation(onPrepared: () -> Unit) {
            files.onPrepared = onPrepared
        }

        override fun close() = store.close()
    }

    private class Faults(
        private val delegate: StudioFiles,
    ) : StudioFiles by delegate {
        var loseCleanup = false
        var losePrepare = false
        var readFailurePath: String? = null
        var publicationFault: PublicationFault? = null
        var onPrepared: (() -> Unit)? = null

        override fun read(name: String): ByteArray? {
            if (name == readFailurePath) {
                readFailurePath = null
                throw IOException("State or journal read unavailable")
            }
            return delegate.read(name)
        }

        override fun replace(
            source: String,
            destination: String,
        ) {
            val fault = publicationFault.takeIf { destination == "state.xml" }
            if (fault != null) publicationFault = null
            if (fault == PublicationFault.REJECTED) throw IOException("State replacement unavailable")
            delegate.replace(source, destination)
            if (destination == "pending.xml") {
                val prepared = onPrepared
                onPrepared = null
                prepared?.invoke()
            }
            if (fault == PublicationFault.ACKNOWLEDGEMENT_LOST) {
                readFailurePath = destination
                throw IOException("Lost state replacement acknowledgement")
            }
            if (losePrepare && destination == "pending.xml") {
                losePrepare = false
                throw IOException("Lost prepare acknowledgement")
            }
        }

        override fun remove(name: String) {
            delegate.remove(name)
            if (loseCleanup && name == "pending.xml") {
                loseCleanup = false
                throw IOException("Lost deletion acknowledgement")
            }
        }
    }

    private enum class PublicationFault {
        REJECTED,
        ACKNOWLEDGEMENT_LOST,
    }

    private companion object {
        const val ROTATION_COMPLETED_AT = 2_000L
        val APPLY: UUID = UUID.fromString("0b4677ae-29f5-4c0d-993d-63e0122c2839")
        val NEXT: UUID = UUID.fromString("1b4677ae-29f5-4c0d-993d-63e0122c2839")

        fun rotationDocument(
            retainAccent: Boolean = false,
            resume: RotationResume = RotationResume.NoHistory,
        ): StudioDocument {
            val document = catalogTestDocument()
            val checkpoint =
                RotationRuntimeCheckpoint(
                    owner = RuntimeOwner(StudioTarget.Global, document.currents.global.revision),
                    sequenceCursorRaw = 0,
                    successfulStepSerial = 0,
                    resume = resume,
                )
            val runtime = document.runtime.copy(rotation = checkpoint)
            if (!retainAccent) return document.copy(runtime = runtime)
            val retainedAccent =
                StudioAccentContinuation(
                    AyuVariant.MIRAGE,
                    ResolvedAccent(AccentResolver.Source.PROJECT_OVERRIDE, "#5CCFE6"),
                    StudioList(emptyList()),
                )
            val continuation =
                StudioEffectiveContinuation(
                    RuntimeOwner(StudioTarget.Project(StudioProjectKey("previous-project")), 1),
                    document.currents.global.appearance,
                    retainedAccent,
                )
            return document.copy(runtime = runtime.copy(effectiveContinuation = continuation))
        }
    }
}
