package dev.ayuislands.studio.session

import dev.ayuislands.glow.GlowCheckpointPhase
import dev.ayuislands.glow.GlowFieldState
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.persistence.StudioReceiptPolicy

internal const val GLOW_PANE_PREFIX = "glow/pane/"
internal const val GLOW_FOCUS_PREFIX = "glow/focus/"
internal const val GLOW_MEMBERSHIP_FIELD = "glow/overlay/membership"

internal fun glowFieldReceiptId(identity: NativeFieldIdentity): String =
    "${identity.ownerToken}/${identity.resource}/${identity.field}"

internal fun glowMembershipReceiptId(resource: String): String = "$resource/membership"

internal fun glowRestoreReceiptId(
    originalId: String,
    sequence: Int,
): String = "$originalId/restore/$sequence"

internal fun glowReceiptStatus(
    phase: GlowCheckpointPhase,
    state: GlowFieldState,
): RestoreStatus =
    when (state) {
        GlowFieldState.PREPARED -> RestoreStatus.PENDING
        GlowFieldState.RESTORED -> RestoreStatus.RESTORED
        GlowFieldState.FOREIGN -> RestoreStatus.FOREIGN
        GlowFieldState.WRITING ->
            if (phase == GlowCheckpointPhase.APPLIED || phase == GlowCheckpointPhase.ACCEPTED) {
                RestoreStatus.OWNED
            } else {
                RestoreStatus.PENDING
            }
    }

/** An unsettled receipt protects its exact native field regardless of which owner wrote it. */
internal fun <Field> blockedGlowFields(
    originals: Map<Field, PortableReceipt>,
    newer: List<PortableReceipt>,
): Set<Field> =
    originals
        .filterValues { original ->
            newer.any {
                it.identity.resource == original.identity.resource &&
                    it.identity.field == original.identity.field &&
                    !StudioReceiptPolicy.isTerminal(it)
            }
        }.keys
