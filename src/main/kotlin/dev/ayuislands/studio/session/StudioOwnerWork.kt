package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.CommitResolution
import dev.ayuislands.studio.persistence.PendingJournal
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.StoreIssue
import kotlinx.coroutines.CoroutineDispatcher

/** Qualifies Apply on the target dispatcher immediately before a synchronous native write. */
internal fun interface StudioNativeWrite {
    suspend fun execute(
        dispatcher: CoroutineDispatcher,
        write: () -> Unit,
    ): Boolean
}

/** Capture at least one receipt before apply; update its ownership after verifying native writes. */
internal interface StudioOwnerOperation {
    val receipts: List<PortableReceipt>

    /** Returns false if a native write was refused because qualification was lost. */
    suspend fun apply(writes: StudioNativeWrite): Boolean

    /** Capture any additional rollback evidence without mutating the native surface. */
    suspend fun prepareRestore(blocked: List<PortableReceipt>) = Unit

    /** Restore only fields still owned by this operation and respect unresolved newer writes. */
    suspend fun restore(blocked: List<PortableReceipt>): RestoreContinuation

    /** Release native checkpoints after durable commit. A failed release must be safe to retry. */
    suspend fun accept()
}

internal enum class RestoreContinuation { COMPLETE, REQUIRED }

/** Retains prepared native handles even when a setter only partially applies. */
internal class StudioOwnerWork(
    journal: PendingJournal,
    private val recover: suspend (EffectOwner, StudioList<PortableReceipt>) -> StudioOwnerOperation? = { _, _ -> null },
) {
    private val retained = linkedMapOf<EffectOwner, StudioOwnerOperation>()
    private val accepted = mutableSetOf<EffectOwner>()

    var journal: PendingJournal = journal
        private set

    val operations: Map<EffectOwner, StudioOwnerOperation>
        get() = retained.toMap()

    fun retain(
        owner: EffectOwner,
        operation: StudioOwnerOperation,
    ) {
        check(retained.putIfAbsent(owner, operation) == null)
    }

    suspend fun handle(owner: EffectOwner): StudioOwnerOperation? {
        retained[owner]?.let { return it }
        val receipts = StudioList(journal.receipts.filter { it.owner == owner })
        if (receipts.isEmpty()) return null
        return recover(owner, receipts)?.also { retain(owner, it) }
    }

    fun hasAccepted(owner: EffectOwner): Boolean = owner in accepted

    fun markAccepted(owner: EffectOwner) {
        check(owner in retained && accepted.add(owner))
    }

    fun acknowledge(journal: PendingJournal) {
        check(this.journal.token == journal.token)
        this.journal = journal
    }
}

internal sealed interface OwnerApply {
    data object Completed : OwnerApply

    data object LeaseLost : OwnerApply

    data class OwnerFailed(
        val owner: EffectOwner,
        val cause: RuntimeException,
    ) : OwnerApply

    data class ReceiptFailed(
        val owner: EffectOwner,
        val issue: StoreIssue,
    ) : OwnerApply
}

internal sealed interface OwnerRollback {
    data object Settled : OwnerRollback

    data object Pending : OwnerRollback

    data class Decision(
        val resolution: CommitResolution,
    ) : OwnerRollback

    data class OwnerFailed(
        val owner: EffectOwner,
        val cause: RuntimeException,
    ) : OwnerRollback

    data class ReceiptFailed(
        val owner: EffectOwner,
        val issue: StoreIssue,
    ) : OwnerRollback
}

internal sealed interface OwnerAcceptance {
    data object Completed : OwnerAcceptance

    data class Decision(
        val resolution: CommitResolution,
    ) : OwnerAcceptance

    data class HandleUnavailable(
        val owner: EffectOwner,
    ) : OwnerAcceptance

    data class OwnerFailed(
        val owner: EffectOwner,
        val cause: RuntimeException,
    ) : OwnerAcceptance
}
