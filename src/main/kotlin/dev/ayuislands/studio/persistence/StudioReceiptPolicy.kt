package dev.ayuislands.studio.persistence

import dev.ayuislands.studio.model.EffectOwner

/** Settled native effects no longer prevent journal cleanup or block a later owner. */
internal object StudioReceiptPolicy {
    private val retainedGeometry = setOf("WIDTH_FIRST_SIZE", "WIDTH_LAST_SIZE", "WIDTH_BOUNDS")

    /** Only outcomes that settle every owner are terminal without receipt context. */
    fun isTerminal(status: RestoreStatus): Boolean =
        when (status) {
            RestoreStatus.RESTORED, RestoreStatus.FOREIGN -> true
            RestoreStatus.PENDING,
            RestoreStatus.OWNED,
            RestoreStatus.UNAVAILABLE,
            RestoreStatus.KEPT_AFTER_RESTART,
            -> false
        }

    /** Retained geometry settles only the explicitly permitted Workspace fields. */
    fun isTerminal(receipt: PortableReceipt): Boolean =
        isTerminal(receipt.status) ||
            (receipt.status == RestoreStatus.KEPT_AFTER_RESTART && canRetain(receipt.owner, receipt.identity.field))

    fun canRetain(
        owner: EffectOwner,
        field: String,
    ): Boolean = owner == EffectOwner.WORKSPACE && field in retainedGeometry
}
