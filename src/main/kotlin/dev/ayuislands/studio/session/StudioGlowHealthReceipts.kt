package dev.ayuislands.studio.session

import dev.ayuislands.glow.GlowFailureOrigin
import dev.ayuislands.glow.GlowFailureRecord
import dev.ayuislands.glow.GlowFieldState
import dev.ayuislands.glow.GlowPaneRecord
import dev.ayuislands.glow.GlowWaveformFailure
import dev.ayuislands.glow.shouldRetryWaveform
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.StudioNativeEvidence.reference

internal data class GlowFailureTokens(
    val occurrence: String,
    val producer: String?,
)

internal data class GlowHealthTokens(
    val before: GlowFailureTokens?,
    val observed: GlowFailureTokens?,
)

/** Failure occurrences have native producer ownership; exception details are not portable state. */
internal object StudioGlowHealthReceipts {
    private const val FIELD = "glow/waveform-health"

    fun encode(
        identity: GlowReceiptIdentity,
        snapshot: GlowFailureRecord,
        change: GlowPaneRecord?,
        tokens: GlowFailureTokens?,
    ): PortableReceipt {
        val before = descriptor(snapshot.before, tokens)
        return StudioGlowReceipts.create(
            identity,
            FIELD,
            before,
            if (plannedFailure(snapshot, change) === snapshot.before) before else RawValue.Null,
            StudioGlowReceipts.status(snapshot.phase, snapshot.state),
        )
    }

    fun plannedFailure(
        snapshot: GlowFailureRecord,
        change: GlowPaneRecord?,
    ): GlowWaveformFailure? {
        val clearsFailure =
            snapshot.before != null &&
                change != null &&
                shouldRetryWaveform(
                    change.before.shape,
                    change.before.waveform,
                    change.planned.shape,
                    change.planned.waveform,
                )
        return if (clearsFailure) null else snapshot.before
    }

    fun encodeRestore(
        identity: GlowReceiptIdentity,
        sequence: Int,
        snapshot: GlowFailureRecord,
        tokens: GlowHealthTokens,
    ): PortableReceipt {
        require(sequence >= 0)
        val prepared = requireNotNull(snapshot.restoration)
        val source = StudioGlowReceipts.id(NativeFieldIdentity(identity.resource, FIELD, identity.ownerToken))
        val before =
            StudioNativeEvidence.record(
                "source" to RawValue.Text(source),
                "observed" to descriptor(prepared.observed, tokens.observed),
                "outcome" to RawValue.Text(prepared.outcome.name),
            )
        return StudioGlowReceipts
            .create(identity, FIELD, before, descriptor(snapshot.before, tokens.before), RestoreStatus.PENDING)
            .copy(id = "$source/restore/$sequence")
    }

    fun refresh(
        receipts: List<PortableReceipt>,
        snapshot: GlowFailureRecord,
    ): StudioList<PortableReceipt> {
        require(receipts.map { it.id }.distinct().size == receipts.size)
        val original = receipts.single { it.id == StudioGlowReceipts.id(it.identity) }
        require(receipts.all { it.identity == original.identity && it.owner == EffectOwner.GLOW })
        require(original.identity.field == FIELD)
        return StudioList(receipts.map { refreshReceipt(it, original.id, snapshot) })
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

    private fun refreshReceipt(
        receipt: PortableReceipt,
        source: String,
        snapshot: GlowFailureRecord,
    ): PortableReceipt {
        val isOriginal = receipt.id == source
        if (!isOriginal) {
            require(receipt.id.startsWith("$source/restore/"))
            val sequence = receipt.id.removePrefix("$source/restore/").toIntOrNull()
            require(sequence != null && sequence >= 0)
        }
        if (StudioNativeEvidence.isTerminal(receipt.status)) return receipt
        val status =
            if (isOriginal || snapshot.state == GlowFieldState.RESTORED || snapshot.state == GlowFieldState.FOREIGN) {
                StudioGlowReceipts.status(snapshot.phase, snapshot.state)
            } else {
                RestoreStatus.PENDING
            }
        return receipt.copy(status = status)
    }

    fun descriptor(
        failure: GlowWaveformFailure?,
        tokens: GlowFailureTokens?,
    ): RawValue {
        if (failure == null) {
            require(tokens == null)
            return RawValue.Null
        }
        requireNotNull(tokens)
        val kind: String
        val hasProducer: Boolean
        when (val origin = failure.origin) {
            is GlowFailureOrigin.Local -> {
                kind = "LOCAL"
                hasProducer = origin.engine != null
            }

            is GlowFailureOrigin.Route -> {
                kind = "ROUTE"
                hasProducer = origin.playback != null
            }
        }
        require(hasProducer == (tokens.producer != null))
        return StudioNativeEvidence.record(
            "occurrence" to reference(tokens.occurrence),
            "origin" to RawValue.Text(kind),
            "producer" to reference(tokens.producer),
        )
    }
}
