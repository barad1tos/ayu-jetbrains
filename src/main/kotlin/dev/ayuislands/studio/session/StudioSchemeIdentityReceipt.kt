package dev.ayuislands.studio.session

import dev.ayuislands.font.FontIdentityRecord
import dev.ayuislands.font.FontIdentityRules
import dev.ayuislands.font.FontRestoreState
import dev.ayuislands.font.FontWritePhase
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.NativeValue
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus

/** One UUID property receipt; native adoption never snapshots or replaces the whole metadata map. */
internal object StudioSchemeIdentityReceipt {
    const val FIELD = "metadata/${FontIdentityRules.METADATA_KEY}"

    fun encode(
        owner: EffectOwner,
        ownerToken: String,
        record: FontIdentityRecord,
    ): PortableReceipt {
        require(ownerToken.isNotBlank() && FontIdentityRules.canonicalId(record.id) != null)
        val status =
            when (record.restoreState) {
                FontRestoreState.RESTORED -> RestoreStatus.RESTORED
                FontRestoreState.FOREIGN -> RestoreStatus.FOREIGN
                FontRestoreState.PENDING, FontRestoreState.WRITING ->
                    if (record.applyPhase == FontWritePhase.SETTLED) RestoreStatus.OWNED else RestoreStatus.PENDING
            }
        return PortableReceipt(
            "$ownerToken/${record.id}/$FIELD",
            owner,
            NativeFieldIdentity(record.id, FIELD, ownerToken),
            NativeValue.Absent,
            NativeValue.Present(RawValue.Text(record.id)),
            status,
        )
    }

    fun decode(
        owner: EffectOwner,
        ownerToken: String,
        receipt: PortableReceipt,
    ): FontIdentityRecord {
        val restored =
            when (receipt.status) {
                RestoreStatus.RESTORED -> FontRestoreState.RESTORED
                RestoreStatus.FOREIGN -> FontRestoreState.FOREIGN
                RestoreStatus.PENDING, RestoreStatus.OWNED, RestoreStatus.UNAVAILABLE -> FontRestoreState.PENDING
                RestoreStatus.KEPT_AFTER_RESTART -> throw IllegalArgumentException(
                    "Invalid scheme identity restore status",
                )
            }
        val phase =
            when (receipt.status) {
                RestoreStatus.OWNED, RestoreStatus.RESTORED, RestoreStatus.FOREIGN -> FontWritePhase.SETTLED
                RestoreStatus.PENDING, RestoreStatus.UNAVAILABLE -> FontWritePhase.WRITING
                RestoreStatus.KEPT_AFTER_RESTART -> throw IllegalArgumentException(
                    "Invalid scheme identity restore status",
                )
            }
        val record = FontIdentityRecord(receipt.identity.resource, phase, restored)
        require(encode(owner, ownerToken, record).copy(status = receipt.status) == receipt) {
            "Invalid scheme identity receipt"
        }
        return record
    }
}
