package dev.ayuislands.studio

import dev.ayuislands.rotation.RotationSnapshot
import dev.ayuislands.studio.model.AcceptanceContext
import dev.ayuislands.studio.model.AcceptanceOrigin
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.ApplyFailure
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.RuntimeFieldChange
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.StudioApplyUpdate
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.model.StudioRuntimeState
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.NativeValue
import dev.ayuislands.studio.persistence.PendingJournal
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioDraftStore
import dev.ayuislands.studio.persistence.openStudioFiles
import dev.ayuislands.studio.session.StudioAccentResult
import dev.ayuislands.studio.session.StudioAcceptancePreparation
import dev.ayuislands.studio.session.StudioApplyCoordinator
import dev.ayuislands.studio.session.StudioApplyEnvironment
import dev.ayuislands.studio.session.StudioDraftPolicy
import dev.ayuislands.studio.session.StudioEffectInput
import dev.ayuislands.studio.session.StudioEffectOperation
import dev.ayuislands.studio.session.StudioEffectParticipant
import dev.ayuislands.studio.session.StudioEffectRuntime
import dev.ayuislands.studio.session.StudioGlowHolds
import dev.ayuislands.studio.session.StudioNativeFacts
import dev.ayuislands.studio.session.StudioNativeObservation
import dev.ayuislands.studio.session.StudioRestoreContinuation
import dev.ayuislands.studio.session.StudioRuntimeLease
import dev.ayuislands.studio.session.StudioThemeTransition
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class StudioApplyFixture(
    directory: Path,
) : StudioApplyEnvironment,
    AutoCloseable {
    val initial = sessionDocument()
    val files = FaultingStudioFiles(openStudioFiles(directory))
    val store = StudioAppearanceStore(files)
    val events = mutableListOf<String>()
    val mutex = Mutex()
    val effects =
        EffectOwner.entries
            .filterNot { it == EffectOwner.PLUGINS }
            .map { ApplyTestEffect(it, store, events) }
    val coordinator = StudioApplyCoordinator(store, this, effects, mutex)
    val acquiredEffects = mutableListOf<StudioList<EffectOwner>>()
    val leases = mutableListOf<StudioRuntimeLease>()
    var flushes = 0
    var isLeaseValid = true
    var canAcquire = true
    var releaseFailure = false
    var beforeFlush: (() -> Unit)? = null
    var beforeRuntime: (() -> Unit)? = null
    var beforeObserve: (() -> Unit)? = null
    var beforeAcquire: (() -> Unit)? = null
    var beforePublish: ((StudioApplyUpdate) -> Unit)? = null
    var context: AcceptanceContext? = null
    var runtimeSeen: StudioRuntimeState? = null
    val updates = mutableListOf<StudioApplyUpdate>()
    private val drafts = StudioDraftStore(store)

    init {
        assertEquals(StoreLoad.Empty, store.load())
        assertEquals(JournalResult.Acknowledged, store.preserveSource(initial.header.migration))
        val token =
            assertIs<PrepareResult.Prepared>(
                store.prepare(
                    -1,
                    initial,
                    RecoveryPlan(StudioTarget.Global, StudioList(emptyList())),
                ),
            ).token
        assertIs<CommitResult.Committed>(store.commit(token, initial.runtime))
        assertEquals(JournalResult.Acknowledged, store.finishAccepted(token))
    }

    fun request(id: String = "accept"): AcceptanceRequest =
        AcceptanceRequest(
            requireNotNull(editedSession(initial).record),
            id,
            1,
            AcceptanceOrigin.SETTINGS,
        )

    fun allOwnersRequest(id: String = "all"): AcceptanceRequest {
        val record = sessionRecord(initial)
        val selected =
            StudioDraftPolicy.select(
                record.work,
                initial.catalog.first(),
                DraftId("factory"),
                initial.currents.global,
            )
        val shared =
            listOf(initial.shared.system, initial.shared.workspace, initial.shared.plugins).map { fields ->
                val before = fields.records.first { it.value is RawValue.BooleanValue }
                SharedIntent.Field(
                    before,
                    before.copy(value = RawValue.BooleanValue(!(before.value as RawValue.BooleanValue).value)),
                )
            }
        return AcceptanceRequest(
            record.copy(work = selected.copy(shared = StudioList(shared))),
            id,
            1,
            AcceptanceOrigin.SETTINGS,
        )
    }

    override suspend fun flush(record: StudioSessionRecord): DraftWriteResult =
        mutex.withLock {
            beforeFlush?.invoke()
            flushes++
            events.add("flush")
            drafts.save(record, "draft-$flushes")
        }

    override suspend fun publish(update: StudioApplyUpdate) {
        beforePublish?.invoke(update)
        updates.add(update)
    }

    suspend fun finishSession(
        sessionId: String,
        availability: SessionAvailability,
    ): DraftWriteResult =
        mutex.withLock {
            drafts.finish(sessionId, availability, "finish-$sessionId-${document().header.revision}")
        }

    override suspend fun observe(
        request: AcceptanceRequest,
        document: StudioDocument,
    ): AcceptanceContext {
        beforeObserve?.invoke()
        context?.let { return it }
        val observed = sessionInputs(document, sessionState(document).copy(record = request.session)).context
        val selected =
            request.session.work.drafts
                .single { it.id == request.session.work.selected }
        return observed.copy(font = observed.font.copy(requested = selected.appearance.font))
    }

    override suspend fun prepareAcceptance(
        request: AcceptanceRequest,
        plan: AcceptancePlan.Commit,
    ): StudioAcceptancePreparation {
        beforeAcquire?.invoke()
        events.add("acquire")
        acquiredEffects.add(plan.effects)
        return if (canAcquire) {
            StudioAcceptancePreparation.Prepared(lease(), plan)
        } else {
            StudioAcceptancePreparation.Blocked(ApplyFailure("lease-invalid"))
        }
    }

    override suspend fun acquireRuntime(
        operationId: UUID,
        snapshot: RotationSnapshot,
        plan: AcceptancePlan.Commit,
    ): StudioAcceptancePreparation {
        beforeAcquire?.invoke()
        events.add("acquire-runtime")
        acquiredEffects.add(plan.effects)
        return if (canAcquire) {
            StudioAcceptancePreparation.Prepared(lease(), plan)
        } else {
            StudioAcceptancePreparation.Blocked(ApplyFailure("lease-invalid"))
        }
    }

    override suspend fun resume(journal: PendingJournal): StudioRuntimeLease = lease()

    private fun lease(): StudioRuntimeLease =
        object : StudioRuntimeLease {
            override val glow: StudioGlowHolds? = null
            override val accent: StudioAccentResult? = null
            override val observation: StudioNativeObservation? = null

            override suspend fun confirmAccent(result: StudioAccentResult): Unit =
                error("This fixture does not hold native Accent admission")

            override suspend fun confirmTheme(transition: StudioThemeTransition): Unit =
                error("This fixture does not hold native theme admission")

            override val native = StudioNativeFacts(true, StudioProcess(1, Instant.EPOCH))

            override suspend fun isValid(): Boolean = isLeaseValid

            override suspend fun settledRuntime(candidate: StudioDocument): StudioRuntimeState {
                runtimeSeen = candidate.runtime
                beforeRuntime?.invoke()
                return candidate.runtime
            }

            override suspend fun release(accepted: StudioDocument?) {
                events.add(if (accepted == null) "release-old" else "release-accepted")
                check(!releaseFailure) { "release failed" }
            }
        }.also(leases::add)

    fun document(): StudioDocument =
        when (val loaded = store.load()) {
            is StoreLoad.Ready -> loaded.document
            is StoreLoad.Recovering -> requireNotNull(loaded.document)
            else -> error("Unexpected store state $loaded")
        }

    fun effect(owner: EffectOwner): ApplyTestEffect = effects.single { it.owner == owner }

    override fun close() = store.close()
}

internal class ApplyTestEffect(
    override val owner: EffectOwner,
    private val store: StudioAppearanceStore,
    private val events: MutableList<String>,
) : StudioEffectParticipant {
    var surface = ApplyTestSurface(owner.name)
    var value: Int
        get() = surface.value
        set(next) {
            surface.value = next
        }
    var failBefore = false
    var failAfter = false
    var failRestore = false
    var failAccept = false
    var afterApply: (() -> Unit)? = null
    var afterAccept: (() -> Unit)? = null
    var beforePrepare: (() -> Unit)? = null
    var afterRestorePrepare: (() -> Unit)? = null
    var afterRestore: (() -> Unit)? = null
    val runtimes = mutableListOf<StudioEffectRuntime>()
    val inputs = mutableListOf<StudioEffectInput>()
    var requiresRestoreEvidence = false
    var writes = 0
    var accepts = 0
    var runtimeChanges = StudioList<RuntimeFieldChange>(emptyList())

    override suspend fun prepare(
        input: StudioEffectInput,
        runtime: StudioEffectRuntime,
    ): StudioEffectOperation {
        runtimes.add(runtime)
        inputs.add(input)
        events.add("prepare-$owner")
        beforePrepare?.invoke()
        return operation(
            StudioList(
                listOf(
                    PortableReceipt(
                        "$owner-value",
                        owner,
                        NativeFieldIdentity(surface.identity, "value", input.candidate.header.transactionId),
                        NativeValue.Present(RawValue.Integer(value)),
                        NativeValue.Present(RawValue.Integer(1)),
                        RestoreStatus.PENDING,
                    ),
                ),
            ),
        )
    }

    override suspend fun recover(
        input: StudioEffectInput,
        receipts: StudioList<PortableReceipt>,
        runtime: StudioEffectRuntime,
    ): StudioEffectOperation {
        runtimes.add(runtime)
        inputs.add(input)
        return operation(receipts)
    }

    private fun operation(captured: StudioList<PortableReceipt>): StudioEffectOperation =
        object : StudioEffectOperation {
            override val runtimeChanges: StudioList<RuntimeFieldChange>
                get() = this@ApplyTestEffect.runtimeChanges

            override var receipts = captured
                private set

            override suspend fun apply() {
                events.add("apply-$owner")
                val journal = assertIs<StoreLoad.Recovering>(store.load()).journal
                assertTrue(journal.receipts.containsAll(receipts), "Before images must be durable before any setter")
                check(!failBefore) { "before setter" }
                value = 1
                writes++
                afterApply?.invoke()
                check(!failAfter) { "after setter" }
            }

            override suspend fun prepareRestore(blockedBy: StudioList<PortableReceipt>) {
                if (requiresRestoreEvidence && receipts.none { it.id == "$owner-restore" }) {
                    val original = receipts.first()
                    receipts =
                        StudioList(
                            receipts +
                                original.copy(
                                    id = "$owner-restore",
                                    before = NativeValue.Present(RawValue.Integer(value)),
                                    lastWritten = original.before,
                                    status = RestoreStatus.PENDING,
                                ),
                        )
                }
                afterRestorePrepare?.invoke()
            }

            override suspend fun restore(blockedBy: StudioList<PortableReceipt>): StudioRestoreContinuation {
                events.add("restore-$owner")
                if (requiresRestoreEvidence) {
                    val journal = assertIs<StoreLoad.Recovering>(store.load()).journal
                    check(receipts.any { it.id == "$owner-restore" }) { "Restore was not prepared" }
                    assertTrue(journal.receipts.containsAll(receipts), "Rollback evidence must precede setters")
                }
                check(!failRestore) { "restore failed" }
                val receipt = receipts.first()
                if (receipt.status == RestoreStatus.RESTORED || receipt.status == RestoreStatus.FOREIGN) {
                    return StudioRestoreContinuation.NONE
                }
                if (blockedBy.any {
                        it.identity.resource == receipt.identity.resource && it.identity.field == receipt.identity.field
                    }
                ) {
                    receipts = StudioList(receipts.map { it.copy(status = RestoreStatus.UNAVAILABLE) })
                    return StudioRestoreContinuation.NONE
                }
                val before = ((receipt.before as NativeValue.Present).value as RawValue.Integer).value
                val planned = ((receipt.lastWritten as NativeValue.Present).value as RawValue.Integer).value
                val status =
                    if (value == planned || value == before) {
                        value = before
                        RestoreStatus.RESTORED
                    } else {
                        RestoreStatus.FOREIGN
                    }
                receipts = StudioList(receipts.map { it.copy(status = status) })
                afterRestore?.invoke()
                return StudioRestoreContinuation.NONE
            }

            override suspend fun accept() {
                events.add("accept-$owner")
                check(!failAccept) { "accept failed" }
                val accepted = assertIs<StoreLoad.Recovering>(store.load())
                assertEquals(accepted.journal.token.transactionId, accepted.document?.header?.transactionId)
                accepts++
                afterAccept?.invoke()
            }
        }
}

internal class ApplyTestSurface(
    val identity: String,
    var value: Int = 0,
)

/** Direct participant fixtures have no held Glow managers. */
internal object EmptyEffectRuntime : StudioEffectRuntime {
    override val glow: StudioGlowHolds? = null
    override val accent: StudioAccentResult? = null
    override val observation: StudioNativeObservation? = null

    override suspend fun confirmAccent(result: StudioAccentResult): Unit =
        error("This fixture does not hold native Accent admission")

    override suspend fun confirmTheme(transition: StudioThemeTransition): Unit =
        error("This fixture does not hold native theme admission")

    override val native = StudioNativeFacts(true, StudioProcess(1, Instant.EPOCH))
}
