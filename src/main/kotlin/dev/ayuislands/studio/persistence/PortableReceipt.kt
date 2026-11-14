package dev.ayuislands.studio.persistence

import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue

internal enum class RestoreStatus { PENDING, OWNED, RESTORED, FOREIGN, UNAVAILABLE, KEPT_AFTER_RESTART }

internal data class NativeFieldIdentity(
    val resource: String,
    val field: String,
    val ownerToken: String,
)

internal sealed interface NativeValue {
    data object Absent : NativeValue

    data class Present(
        val value: RawValue,
    ) : NativeValue
}

/** Portable before/planned-write pair; the named surface owner alone interprets and restores it. */
internal data class PortableReceipt(
    val id: String,
    val owner: EffectOwner,
    val identity: NativeFieldIdentity,
    val before: NativeValue,
    val lastWritten: NativeValue,
    val status: RestoreStatus,
)
