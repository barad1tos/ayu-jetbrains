package dev.ayuislands.studio.session

import dev.ayuislands.glow.GlowCheckpointPhase
import dev.ayuislands.glow.GlowFieldState
import dev.ayuislands.glow.GlowPaneAppearance
import dev.ayuislands.glow.GlowPaneField
import dev.ayuislands.glow.GlowPaneRecord
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.persistence.StudioReceiptPolicy

/** The journal keeps evidence; only the retained native checkpoint can write a pane. */
internal object StudioGlowPaneReceipts {
    fun capture(
        resource: String,
        ownerToken: String,
        process: StudioProcess,
        record: GlowPaneRecord,
        tokens: StudioNativeTokens,
    ): Map<GlowPaneField, PortableReceipt> =
        record.fields.mapValues { (field, _) ->
            val identity = NativeFieldIdentity(resource, "glow/pane/${field.name}", ownerToken)
            PortableReceipt(
                id = "$ownerToken/$resource/${identity.field}",
                owner = EffectOwner.GLOW,
                identity = identity,
                before = StudioNativeEvidence.envelope(process, value(field, record.before, tokens)),
                lastWritten = StudioNativeEvidence.envelope(process, value(field, record.planned, tokens)),
                status = RestoreStatus.PENDING,
            )
        }

    fun observe(
        originals: Map<GlowPaneField, PortableReceipt>,
        record: GlowPaneRecord,
    ): List<PortableReceipt> =
        originals.map { (field, receipt) ->
            receipt.copy(status = status(record.phase, record.fields.getValue(field)))
        }

    fun blockedFields(
        originals: Map<GlowPaneField, PortableReceipt>,
        blocked: List<PortableReceipt>,
    ): Set<GlowPaneField> =
        originals
            .filterValues { original ->
                blocked.any {
                    it.identity.resource == original.identity.resource &&
                        it.identity.field == original.identity.field &&
                        !StudioReceiptPolicy.isTerminal(it)
                }
            }.keys

    private fun status(
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

    private fun value(
        field: GlowPaneField,
        appearance: GlowPaneAppearance,
        tokens: StudioNativeTokens,
    ): RawValue =
        when (field) {
            GlowPaneField.COLOR -> StudioNativeEvidence.reference(tokens.token(appearance.color))
            GlowPaneField.STYLE -> RawValue.Text(appearance.style.name)
            GlowPaneField.INTENSITY -> RawValue.Integer(appearance.intensity)
            GlowPaneField.WIDTH -> RawValue.Integer(appearance.width)
            GlowPaneField.PLACEMENT -> RawValue.Text(appearance.placement.name)
            GlowPaneField.SHAPE -> RawValue.Text(appearance.shape.name)
            GlowPaneField.VISIBILITY -> RawValue.BooleanValue(appearance.isVisible)
            GlowPaneField.MOVEMENT,
            GlowPaneField.BASELINE,
            GlowPaneField.AMPLITUDE,
            GlowPaneField.WAVEFORM_INTENSITY,
            GlowPaneField.LOOP_SECONDS,
            GlowPaneField.TRACE_DENSITY,
            GlowPaneField.TRACE_LENGTH,
            -> waveformValue(field, appearance.waveform)
        }

    private fun waveformValue(
        field: GlowPaneField,
        waveform: WaveformConfig,
    ): RawValue =
        when (field) {
            GlowPaneField.MOVEMENT -> RawValue.Text(waveform.movement.name)
            GlowPaneField.BASELINE -> RawValue.Text(waveform.baseline.name)
            GlowPaneField.AMPLITUDE -> RawValue.Integer(waveform.amplitude)
            GlowPaneField.WAVEFORM_INTENSITY -> RawValue.Integer(waveform.intensity)
            GlowPaneField.LOOP_SECONDS -> RawValue.FloatBits(waveform.loopSeconds.toRawBits())
            GlowPaneField.TRACE_DENSITY -> RawValue.Integer(waveform.traceDensity)
            GlowPaneField.TRACE_LENGTH -> RawValue.Integer(waveform.traceLength)
            else -> error("Not a Glow waveform field: $field")
        }
}
