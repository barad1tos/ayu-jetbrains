package dev.ayuislands.studio.session

import dev.ayuislands.glow.GlowCheckpointPhase
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.StudioNativeEvidence.reference

/** Tokens name exact prepared native resources, not window names or mutable child indices. */
internal sealed interface GlowMemberTokens {
    data class Overlay(
        val entry: String,
        val pane: String,
        val host: String,
        val layer: String,
        val componentListener: String?,
        val boundsListener: String?,
        val isEditor: Boolean,
    ) : GlowMemberTokens

    data class Focus(
        val listener: String,
        val component: String,
        val parent: String?,
        val window: String?,
    ) : GlowMemberTokens
}

/** Group cleanup stays with the native owner; membership never implies settlement of dependent fields. */
internal object StudioGlowMembershipReceipts {
    private const val OVERLAY = "glow/overlay/membership"
    private const val FOCUS = "glow/focus/membership"

    fun encodeAttachment(
        identity: GlowReceiptIdentity,
        member: GlowMemberTokens,
    ): PortableReceipt =
        StudioGlowReceipts.create(identity, field(member), RawValue.Null, descriptor(member), RestoreStatus.PENDING)

    fun encodeRemoval(
        identity: GlowReceiptIdentity,
        member: GlowMemberTokens,
    ): PortableReceipt =
        StudioGlowReceipts.create(identity, field(member), descriptor(member), RawValue.Null, RestoreStatus.PENDING)

    fun refreshAttachment(
        receipt: PortableReceipt,
        phase: GlowCheckpointPhase,
    ): PortableReceipt =
        refresh(
            receipt,
            when (phase) {
                GlowCheckpointPhase.PREPARED, GlowCheckpointPhase.APPLYING, GlowCheckpointPhase.RESTORING -> {
                    RestoreStatus.PENDING
                }

                GlowCheckpointPhase.APPLIED, GlowCheckpointPhase.ACCEPTED -> {
                    RestoreStatus.OWNED
                }

                GlowCheckpointPhase.RESTORED -> {
                    RestoreStatus.RESTORED
                }
            },
        )

    fun refreshRemoval(
        receipt: PortableReceipt,
        isComplete: Boolean,
    ): PortableReceipt = refresh(receipt, if (isComplete) RestoreStatus.RESTORED else RestoreStatus.PENDING)

    fun isBlocked(
        resources: Set<String>,
        newer: List<PortableReceipt>,
    ): Boolean = newer.any { it.identity.resource in resources && !StudioNativeEvidence.isTerminal(it.status) }

    private fun refresh(
        receipt: PortableReceipt,
        progress: RestoreStatus,
    ): PortableReceipt {
        require(receipt.owner == EffectOwner.GLOW && receipt.identity.field in setOf(OVERLAY, FOCUS))
        require(receipt.id == StudioGlowReceipts.id(receipt.identity))
        return if (StudioNativeEvidence.isTerminal(receipt.status)) receipt else receipt.copy(status = progress)
    }

    private fun field(member: GlowMemberTokens): String =
        when (member) {
            is GlowMemberTokens.Overlay -> OVERLAY
            is GlowMemberTokens.Focus -> FOCUS
        }

    private fun descriptor(member: GlowMemberTokens): RawValue =
        when (member) {
            is GlowMemberTokens.Overlay -> {
                StudioNativeEvidence.record(
                    "entry" to reference(member.entry),
                    "pane" to reference(member.pane),
                    "host" to reference(member.host),
                    "layer" to reference(member.layer),
                    "componentListener" to reference(member.componentListener),
                    "boundsListener" to reference(member.boundsListener),
                    "editor" to RawValue.BooleanValue(member.isEditor),
                )
            }

            is GlowMemberTokens.Focus -> {
                StudioNativeEvidence.record(
                    "listener" to reference(member.listener),
                    "component" to reference(member.component),
                    "parent" to reference(member.parent),
                    "window" to reference(member.window),
                )
            }
        }
}
