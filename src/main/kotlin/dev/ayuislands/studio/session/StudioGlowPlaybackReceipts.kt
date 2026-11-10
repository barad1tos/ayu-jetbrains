package dev.ayuislands.studio.session

import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowCheckpointPhase
import dev.ayuislands.glow.GlowPaneField
import dev.ayuislands.glow.GlowPlaybackRequest
import dev.ayuislands.glow.GlowPlaybackTarget
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.StudioNativeEvidence.record
import dev.ayuislands.studio.session.StudioNativeEvidence.reference

internal data class GlowPlaybackSurface(
    val id: String,
    val pane: String,
    val host: String,
    val layer: String,
)

internal data class GlowPlaybackTokens(
    val surface: GlowPlaybackSurface,
    val color: String,
    val failure: GlowFailureTokens?,
)

/** Encodes captured values only; native playback and exact producer handles stay with the operation. */
internal object StudioGlowPlaybackReceipts {
    private const val FIELD = "glow/playback"

    fun baseline(
        animation: GlowAnimation,
        routeConfig: WaveformConfig?,
        targets: List<GlowPlaybackSurface>,
    ): RawValue =
        record(
            "animation" to RawValue.Text(animation.name),
            "route" to (routeConfig?.let(StudioGlowReceipts::waveform) ?: RawValue.Null),
            "targets" to RawValue.OrderedList(StudioList(targets.map(::surface))),
        )

    fun activation(
        intent: GlowPlaybackRequest.Activate,
        tokens: List<GlowPlaybackTokens>,
    ): RawValue {
        require(intent.targets.map { it.overlay.id } == tokens.map { it.surface.id })
        val environment = intent.environment
        val targets = intent.targets.zip(tokens).map { (prepared, identities) -> target(prepared, identities) }
        return record(
            "animation" to RawValue.Text(intent.animation.name),
            "route" to (intent.routeConfig?.let(StudioGlowReceipts::waveform) ?: RawValue.Null),
            "targets" to RawValue.OrderedList(StudioList(targets)),
            "environment" to
                record(
                    "focused" to (environment.focusedId?.let(RawValue::Text) ?: RawValue.Null),
                    "active" to RawValue.BooleanValue(environment.isApplicationActive),
                    "powerSave" to RawValue.BooleanValue(environment.isPowerSaveEnabled),
                ),
        )
    }

    fun encode(
        identity: GlowReceiptIdentity,
        before: RawValue,
        planned: RawValue,
    ): PortableReceipt = StudioGlowReceipts.create(identity, FIELD, before, planned, RestoreStatus.PENDING)

    /** A fresh restore attempt starts only from a proven pause and is acknowledged before its first effect. */
    fun encodeRestore(
        identity: GlowReceiptIdentity,
        sequence: Int,
        planned: RawValue,
    ): PortableReceipt {
        require(sequence >= 0)
        val source = StudioGlowReceipts.id(NativeFieldIdentity(identity.resource, FIELD, identity.ownerToken))
        val before = record("source" to RawValue.Text(source), "paused" to RawValue.BooleanValue(true))
        return encode(identity, before, planned).copy(id = "$source/restore/$sequence")
    }

    /** Stopping forward playback does not restore the original playback that it replaced. */
    fun refreshOriginal(
        receipt: PortableReceipt,
        forward: GlowCheckpointPhase?,
        isRestored: Boolean,
    ): PortableReceipt {
        checkReceipt(receipt)
        require(receipt.id == StudioGlowReceipts.id(receipt.identity))
        val status =
            when {
                isRestored -> RestoreStatus.RESTORED
                forward == GlowCheckpointPhase.APPLIED || forward == GlowCheckpointPhase.ACCEPTED -> RestoreStatus.OWNED
                else -> RestoreStatus.PENDING
            }
        return refresh(receipt, status)
    }

    /** Both cleaning up a failed attempt and handing off a completed restart settle that attempt only. */
    fun refreshRestore(
        receipt: PortableReceipt,
        phase: GlowCheckpointPhase,
    ): PortableReceipt {
        checkReceipt(receipt)
        val prefix = "${StudioGlowReceipts.id(receipt.identity)}/restore/"
        require(receipt.id.startsWith(prefix))
        val sequence = receipt.id.removePrefix(prefix).toIntOrNull()
        require(sequence != null && sequence >= 0)
        val status =
            if (phase == GlowCheckpointPhase.RESTORED || phase == GlowCheckpointPhase.ACCEPTED) {
                RestoreStatus.RESTORED
            } else {
                RestoreStatus.PENDING
            }
        return refresh(receipt, status)
    }

    fun isBlocked(
        resource: String,
        newer: List<PortableReceipt>,
    ): Boolean =
        newer.any {
            it.identity.resource == resource &&
                it.identity.field == FIELD &&
                !StudioNativeEvidence.isTerminal(it.status)
        }

    private fun target(
        prepared: GlowPlaybackTarget,
        tokens: GlowPlaybackTokens,
    ): RawValue =
        record(
            "surface" to surface(tokens.surface),
            "appearance" to
                record(
                    GlowPaneField.entries.map { field ->
                        field.name to StudioGlowReceipts.paneValue(field, prepared.appearance, tokens.color)
                    },
                ),
            "failure" to StudioGlowHealthReceipts.descriptor(prepared.failure, tokens.failure),
        )

    private fun surface(tokens: GlowPlaybackSurface): RawValue =
        record(
            "id" to RawValue.Text(tokens.id),
            "pane" to reference(tokens.pane),
            "host" to reference(tokens.host),
            "layer" to reference(tokens.layer),
        )

    private fun checkReceipt(receipt: PortableReceipt) {
        require(receipt.owner == EffectOwner.GLOW && receipt.identity.field == FIELD)
    }

    private fun refresh(
        receipt: PortableReceipt,
        status: RestoreStatus,
    ): PortableReceipt = if (StudioNativeEvidence.isTerminal(receipt.status)) receipt else receipt.copy(status = status)
}
