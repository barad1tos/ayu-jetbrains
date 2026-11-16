package dev.ayuislands.studio

import dev.ayuislands.studio.model.AcceptanceBlock
import dev.ayuislands.studio.model.ApplyRequest
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.CommitResolution
import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.NativeValue
import dev.ayuislands.studio.persistence.PendingJournal
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.RecoveryPurpose
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.persistence.StoreIssue
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioFiles
import dev.ayuislands.studio.persistence.openStudioFiles
import dev.ayuislands.studio.session.ApplySettlement
import dev.ayuislands.studio.session.RestoreContinuation
import dev.ayuislands.studio.session.SettlementProblem
import dev.ayuislands.studio.session.StudioApplyCoordinator
import dev.ayuislands.studio.session.StudioNativeWrite
import dev.ayuislands.studio.session.StudioOwnerOperation
import dev.ayuislands.studio.session.StudioRecoveryLease
import dev.ayuislands.studio.session.Submission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioApplyRestartTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `restart restores only owned values and leaves the saved draft available`() {
        val older = receipt(EffectOwner.GLOW)
        val newer = receipt(EffectOwner.WORKSPACE)
        val prepared = prepare(listOf(older, newer))
        val effects = NativeState(older to 1, newer to 2)
        reopen(effects) { coordinator, store ->
            assertEquals(ApplySettlement.NotAccepted, coordinator.retry(APPLY)?.settlement)
            assertEquals(StoreLoad.Ready(prepared.first), store.load())
            assertEquals(0, effects.values.getValue(older.id))
            assertEquals(2, effects.values.getValue(newer.id))
            assertEquals(
                listOf("resume-rollback", "foreign-WORKSPACE", "restore-GLOW", "release-rollback"),
                effects.events,
            )
        }
    }

    @Test
    fun `restart resumes accepted cleanup without applying or releasing twice after a lost acknowledgement`() {
        val owner = receipt(EffectOwner.GLOW)
        val (_, accepted) = prepare(listOf(owner), committed = true)
        val effects = NativeState(owner to 1)
        reopen(effects, loseCleanup = true) { coordinator, store ->
            val pending = assertIs<ApplySettlement.AcceptedCleanupPending>(coordinator.retry(APPLY)?.settlement)
            assertEquals(accepted, pending.document)
            assertFalse(coordinator.acknowledge(APPLY))
            assertEquals(StoreLoad.Ready(accepted), store.load())

            val request = ApplyRequest(editedJournalRecord(accepted), 1)
            assertEquals(Submission.Blocked(AcceptanceBlock.INVALID_REQUEST), coordinator.submit(request, APPLY))
            assertEquals(ApplySettlement.Accepted(accepted), coordinator.retry(APPLY)?.settlement)
            assertEquals(ApplySettlement.Accepted(accepted), coordinator.outcome(APPLY)?.settlement)
            assertTrue(coordinator.acknowledge(APPLY))
            assertNull(coordinator.retry(APPLY))
            assertEquals(1, effects.values.getValue(owner.id))
            assertEquals(listOf("resume-accepted", "accept-GLOW", "release-accepted"), effects.events)
        }
    }

    @Test
    fun `accepted recovery remains retryable when its host cannot provide a lease`() {
        val owner = receipt(EffectOwner.GLOW)
        val (_, accepted) = prepare(listOf(owner), committed = true)
        val effects = NativeState(owner to 1).apply { canReattach = { false } }
        val failure = IllegalStateException("Recovery host initialization failed")
        reopen(effects) { coordinator, store ->
            assertEquals(
                ApplySettlement.AcceptedCleanupPending(accepted, SettlementProblem.RecoveryLeaseUnavailable),
                coordinator.retry(APPLY)?.settlement,
            )
            effects.canReattach = { throw failure }
            val failed = assertIs<ApplySettlement.AcceptedCleanupPending>(coordinator.retry(APPLY)?.settlement)
            assertEquals(accepted, failed.document)
            val cause = assertIs<SettlementProblem.RecoveryLeaseFailed>(failed.problem).cause
            assertEquals(failure.message, assertIs<IllegalStateException>(cause).message)
            val pending = assertIs<StoreLoad.Recovering>(store.load())
            assertEquals(accepted, pending.document)
            assertEquals(listOf(owner), pending.journal.receipts)
            assertEquals(1, effects.values.getValue(owner.id))
            assertTrue(effects.events.isEmpty())

            effects.canReattach = { true }
            assertEquals(ApplySettlement.Accepted(accepted), coordinator.retry(APPLY)?.settlement)
            assertEquals(StoreLoad.Ready(accepted), store.load())
            assertEquals(listOf("resume-accepted", "accept-GLOW", "release-accepted"), effects.events)
        }
    }

    @Test
    fun `a missing restored owner keeps the journal until it can be attached`() {
        val owner = receipt(EffectOwner.GLOW)
        val (initial, _) = prepare(listOf(owner))
        val effects = NativeState(owner to 1).apply { unavailable = true }
        reopen(effects) { coordinator, store ->
            assertNull(coordinator.retry(OTHER))
            assertIs<ApplySettlement.RecoveryRequired>(coordinator.retry(APPLY)?.settlement)
            assertEquals(listOf(owner), assertIs<StoreLoad.Recovering>(store.load()).journal.receipts)
            assertFalse(coordinator.acknowledge(APPLY))
            assertEquals(1, effects.values.getValue(owner.id))
            effects.unavailable = false

            assertEquals(ApplySettlement.NotAccepted, coordinator.retry(APPLY)?.settlement)
            assertEquals(StoreLoad.Ready(initial), store.load())
            assertEquals(0, effects.values.getValue(owner.id))
            assertEquals(listOf("resume-rollback", "restore-GLOW", "release-rollback"), effects.events)
        }
    }

    @Test
    fun `failed journal read leaves the pending Apply retryable without touching native values`() {
        val owner = receipt(EffectOwner.GLOW)
        val (initial, _) = prepare(listOf(owner))
        val effects = NativeState(owner to 1)
        reopen(effects, readFault = ReadFault.JOURNAL) { coordinator, store ->
            assertEquals(
                ApplySettlement.RecoveryRequired(SettlementProblem.Journal(StoreIssue.IO)),
                coordinator.retry(APPLY)?.settlement,
            )
            val pending = assertIs<StoreLoad.Recovering>(store.load())
            assertEquals(initial, pending.document)
            assertEquals(listOf(owner), pending.journal.receipts)
            assertEquals(1, effects.values.getValue(owner.id))
            assertTrue(effects.events.isEmpty())

            assertEquals(ApplySettlement.NotAccepted, coordinator.retry(APPLY)?.settlement)
            assertEquals(StoreLoad.Ready(initial), store.load())
            assertEquals(0, effects.values.getValue(owner.id))
        }
    }

    @Test
    fun `restart with unreadable authority reports recovery and leaves native state untouched`() {
        val owner = receipt(EffectOwner.GLOW)
        val (initial, _) = prepare(listOf(owner))
        val effects = NativeState(owner to 1)
        reopen(effects, readFault = ReadFault.OUTCOME) { coordinator, store ->
            assertIs<ApplySettlement.RecoveryRequired>(coordinator.retry(APPLY)?.settlement)
            assertEquals(1, effects.values.getValue(owner.id))
            assertTrue(effects.events.isEmpty())
            assertEquals(initial, assertIs<StoreLoad.Recovering>(store.load()).document)
            assertEquals(ApplySettlement.NotAccepted, coordinator.retry(APPLY)?.settlement)
        }
    }

    @Test
    fun `cancellation after recovery lease acquisition does not acquire it again`() {
        val owner = receipt(EffectOwner.GLOW)
        val (_, accepted) = prepare(listOf(owner), committed = true)
        val effects = NativeState(owner to 1)
        val attached = CompletableDeferred<Unit>()
        val deliver = CompletableDeferred<Unit>()
        val pause: suspend () -> Unit = {
            attached.complete(Unit)
            deliver.await()
        }
        reopen(effects, afterResume = pause) { coordinator, _ ->
            coroutineScope {
                val first = launch { coordinator.retry(APPLY) }
                attached.await()
                first.cancel()
                deliver.complete(Unit)
                first.join()
            }

            assertNull(coordinator.outcome(APPLY))
            assertEquals(listOf("resume-accepted"), effects.events)
            assertEquals(ApplySettlement.Accepted(accepted), coordinator.retry(APPLY)?.settlement)
            assertEquals(listOf("resume-accepted", "accept-GLOW", "release-accepted"), effects.events)
        }
    }

    @Test
    fun `Retry cannot claim a different operation or a draft store journal`() {
        val (initial, _) = prepare(emptyList(), purpose = RecoveryPurpose.Metadata)
        val effects = NativeState()
        reopen(effects) { coordinator, store ->
            assertNull(coordinator.retry(OTHER))
            assertNull(coordinator.retry(APPLY))
            assertEquals(initial, assertIs<StoreLoad.Recovering>(store.load()).document)
            assertEquals(emptyList(), effects.events)
        }
    }

    private fun prepare(
        receipts: List<PortableReceipt>,
        committed: Boolean = false,
        purpose: RecoveryPurpose = RecoveryPurpose.UserAcceptance,
    ): Pair<StudioDocument, StudioDocument> {
        val fresh = catalogTestDocument()
        val initial = fresh.copy(sessions = StudioList(listOf(editedJournalRecord(fresh))))
        val accepted =
            initial.copy(
                header = initial.header.copy(revision = 1, transactionId = APPLY.toString()),
                sessions = StudioList(emptyList()),
            )
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            assertEquals(StoreLoad.Empty, store.load())
            assertEquals(JournalResult.Acknowledged, store.preserveSource(initial.header.migration))
            val bootstrap = RecoveryPlan(StudioTarget.Global, StudioList(emptyList()))
            val first = assertIs<PrepareResult.Prepared>(store.prepare(-1, initial, bootstrap)).token
            assertIs<CommitResult.Committed>(store.commit(first, initial.runtime))
            assertEquals(JournalResult.Acknowledged, store.finishAccepted(first))
            val plan = bootstrap.copy(owners = StudioList(receipts.map { it.owner }), purpose = purpose)
            val pending = assertIs<PrepareResult.Prepared>(store.prepare(0, accepted, plan)).token
            assertEquals(JournalResult.Acknowledged, store.recordRecovery(pending, receipts))
            if (committed) assertIs<CommitResult.Committed>(store.commit(pending, accepted.runtime))
        }
        return initial to accepted
    }

    private fun reopen(
        effects: NativeState,
        loseCleanup: Boolean = false,
        readFault: ReadFault? = null,
        afterResume: suspend () -> Unit = {},
        block: suspend (StudioApplyCoordinator, StudioAppearanceStore) -> Unit,
    ) {
        val queue = Mutex()
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val files = Faults(openStudioFiles(directory), loseCleanup, readFault)
            StudioAppearanceStore(files).use { store ->
                runBlocking { block(coordinator(store, dispatcher, queue, effects, afterResume), store) }
            }
        }
    }

    private fun coordinator(
        store: StudioAppearanceStore,
        dispatcher: CoroutineDispatcher,
        queue: Mutex,
        effects: NativeState,
        afterResume: suspend () -> Unit,
    ): StudioApplyCoordinator =
        StudioApplyCoordinator(
            store,
            queue,
            dispatcher,
            flush = { error("Recovery must not flush another draft") },
            observe = { _, _ -> error("Recovery must not plan a new Apply") },
            acquire = { error("Recovery must not acquire a new Apply") },
            recover = { journal, decision ->
                assertTrue(queue.isLocked)
                effects.resume(journal, decision).also { afterResume() }
            },
        )

    private class NativeState(
        vararg initial: Pair<PortableReceipt, Int>,
    ) {
        val values = initial.associate { (receipt, value) -> receipt.id to value }.toMutableMap()
        val events = mutableListOf<String>()
        var unavailable = false
        var canReattach: () -> Boolean = { true }

        fun resume(
            journal: PendingJournal,
            decision: CommitResolution,
        ): StudioRecoveryLease? {
            if (!canReattach()) return null
            val outcome =
                when (decision) {
                    CommitResolution.Committed -> "accepted"
                    CommitResolution.NotCommitted -> "rollback"
                    CommitResolution.Unknown -> error("Native owners cannot decide the durable outcome")
                }
            assertEquals(APPLY.toString(), journal.token.transactionId)
            events.add("resume-$outcome")
            return object : StudioRecoveryLease {
                override suspend fun recover(
                    owner: EffectOwner,
                    receipts: StudioList<PortableReceipt>,
                ): StudioOwnerOperation? = if (unavailable) null else operation(owner, receipts.single())

                override suspend fun release(accepted: StudioDocument?) {
                    assertEquals(decision == CommitResolution.Committed, accepted != null)
                    events.add("release-$outcome")
                }
            }
        }

        private fun operation(
            owner: EffectOwner,
            durable: PortableReceipt,
        ): StudioOwnerOperation =
            object : StudioOwnerOperation {
                private var receipt = durable
                override val receipts: List<PortableReceipt> get() = listOf(receipt)

                override suspend fun apply(writes: StudioNativeWrite): Boolean =
                    error("Recovery must not replay the original native setter")

                override suspend fun restore(blocked: List<PortableReceipt>): RestoreContinuation {
                    assertTrue(blocked.isEmpty())
                    if (values.getValue(receipt.id) == 1) {
                        values[receipt.id] = 0
                        receipt = receipt.copy(status = RestoreStatus.RESTORED)
                        events.add("restore-${owner.name}")
                    } else {
                        receipt = receipt.copy(status = RestoreStatus.FOREIGN)
                        events.add("foreign-${owner.name}")
                    }
                    return RestoreContinuation.COMPLETE
                }

                override suspend fun accept() {
                    events.add("accept-${owner.name}")
                }
            }
    }

    private class Faults(
        private val delegate: StudioFiles,
        private var loseCleanup: Boolean,
        private var readFault: ReadFault?,
    ) : StudioFiles by delegate {
        override fun read(name: String): ByteArray? {
            if (name == "pending.xml") {
                val fault = readFault
                readFault = null
                when (fault) {
                    ReadFault.JOURNAL -> throw IOException("Could not read the pending Apply journal")
                    ReadFault.OUTCOME -> rejectStateRead = true
                    null -> Unit
                }
            } else if (name == "state.xml" && rejectStateRead) {
                rejectStateRead = false
                throw IOException("Could not confirm authoritative settings during recovery")
            }
            return delegate.read(name)
        }

        private var rejectStateRead = false

        override fun remove(name: String) {
            delegate.remove(name)
            if (name == "pending.xml" && loseCleanup) {
                loseCleanup = false
                throw IOException("Lost the journal removal acknowledgement")
            }
        }
    }

    private enum class ReadFault {
        JOURNAL,
        OUTCOME,
    }

    private companion object {
        val APPLY: UUID = UUID.fromString("00000000-0000-4000-8000-000000000401")
        val OTHER: UUID = UUID.fromString("00000000-0000-4000-8000-000000000402")
    }
}

private fun receipt(owner: EffectOwner): PortableReceipt =
    PortableReceipt(
        owner.name,
        owner,
        NativeFieldIdentity("test-$owner", "enabled", "previous-ide-process"),
        NativeValue.Present(RawValue.Integer(0)),
        NativeValue.Present(RawValue.Integer(1)),
        RestoreStatus.OWNED,
    )
