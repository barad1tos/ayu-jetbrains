package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.EffectOwner
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
}

/** Retains prepared native handles even when a setter only partially applies. */
internal class StudioOwnerWork(
    journal: PendingJournal,
) {
    private val retained = linkedMapOf<EffectOwner, StudioOwnerOperation>()

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
