package dev.ayuislands.studio.session

import dev.ayuislands.glow.GlowCheckpointPhase
import dev.ayuislands.glow.GlowFieldState
import dev.ayuislands.glow.GlowPaneAppearance
import dev.ayuislands.glow.GlowPaneField
import dev.ayuislands.glow.GlowPaneRecord
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.model.StudioProcessRelation
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.StudioNativeEvidence.envelope
import dev.ayuislands.studio.session.StudioNativeEvidence.isTerminal
import dev.ayuislands.studio.session.StudioNativeEvidence.readProcess
import dev.ayuislands.studio.session.StudioNativeEvidence.record

/** Resource identifies the process and exact native target incarnation, never a project or window name. */
internal data class GlowReceiptIdentity(
    val resource: String,
    val ownerToken: String,
    val process: StudioProcess,
)

/** Portable evidence for ephemeral targets; native objects remain with their retained checkpoints. */
internal object StudioGlowReceipts {
    private const val PANE_PREFIX = "glow/pane/"

    fun encodePane(
        identity: GlowReceiptIdentity,
        snapshot: GlowPaneRecord,
        beforeColorToken: String,
        plannedColorToken: String,
    ): StudioList<PortableReceipt> {
        require(identity.resource.isNotBlank() && identity.ownerToken.isNotBlank())
        val receipts =
            GlowPaneField.entries.filter { it in snapshot.fields }.map { field ->
                create(
                    identity,
                    "$PANE_PREFIX${field.name}",
                    paneValue(field, snapshot.before, beforeColorToken),
                    paneValue(field, snapshot.planned, plannedColorToken),
                    status(snapshot.phase, snapshot.fields.getValue(field)),
                )
            }
        return StudioList(receipts)
    }

    /** Refresh only the status of the same retained checkpoint, preserving original evidence byte-for-byte. */
    fun refreshPane(
        originals: List<PortableReceipt>,
        snapshot: GlowPaneRecord,
    ): StudioList<PortableReceipt> {
        val scopes = originals.map { paneField(it.identity.field) }
        require(scopes.toSet() == snapshot.fields.keys && scopes.size == snapshot.fields.size)
        require(originals.all { it.owner == EffectOwner.GLOW })
        return StudioList(
            originals.zip(scopes).map { (receipt, field) ->
                if (isTerminal(receipt.status)) {
                    receipt
                } else {
                    receipt.copy(status = status(snapshot.phase, snapshot.fields.getValue(requireNotNull(field))))
                }
            },
        )
    }

    fun blockedPaneFields(
        resource: String,
        newer: List<PortableReceipt>,
    ): Set<GlowPaneField> =
        newer
            .flatMap { receipt ->
                val native = receipt.identity
                if (native.resource != resource ||
                    isTerminal(receipt.status) ||
                    !native.field.startsWith(PANE_PREFIX)
                ) {
                    emptyList()
                } else {
                    paneField(native.field)?.let(::listOf) ?: GlowPaneField.entries
                }
            }.toSet()

    /** A lost handle is not proof of cleanup. Only a confirmed different process makes its effects impossible. */
    fun recoverLost(
        receipts: List<PortableReceipt>,
        currentProcess: StudioProcess?,
    ): StudioList<PortableReceipt> =
        StudioList(
            receipts.map { receipt ->
                require(receipt.owner == EffectOwner.GLOW)
                if (isTerminal(receipt.status)) {
                    receipt
                } else {
                    val before = readProcess(receipt.before)
                    require(before == readProcess(receipt.lastWritten)) { "Glow receipt process provenance differs" }
                    val restored =
                        StudioProcessPolicy.compare(before, currentProcess) == StudioProcessRelation.DIFFERENT
                    receipt.copy(status = if (restored) RestoreStatus.RESTORED else RestoreStatus.UNAVAILABLE)
                }
            },
        )

    fun create(
        identity: GlowReceiptIdentity,
        field: String,
        before: RawValue,
        planned: RawValue,
        progress: RestoreStatus,
    ): PortableReceipt {
        require(identity.resource.isNotBlank() && identity.ownerToken.isNotBlank() && field.isNotBlank())
        val native = NativeFieldIdentity(identity.resource, field, identity.ownerToken)
        return PortableReceipt(
            id(native),
            EffectOwner.GLOW,
            native,
            envelope(identity.process, before),
            envelope(identity.process, planned),
            progress,
        )
    }

    fun id(identity: NativeFieldIdentity): String = "${identity.ownerToken}/${identity.resource}/${identity.field}"

    private fun paneField(name: String): GlowPaneField? =
        GlowPaneField.entries.singleOrNull { "$PANE_PREFIX${it.name}" == name }

    fun status(
        phase: GlowCheckpointPhase,
        state: GlowFieldState,
    ): RestoreStatus =
        when (state) {
            GlowFieldState.RESTORED -> {
                RestoreStatus.RESTORED
            }

            GlowFieldState.FOREIGN -> {
                RestoreStatus.FOREIGN
            }

            GlowFieldState.PREPARED -> {
                RestoreStatus.PENDING
            }

            GlowFieldState.WRITING -> {
                if (phase == GlowCheckpointPhase.APPLIED || phase == GlowCheckpointPhase.ACCEPTED) {
                    RestoreStatus.OWNED
                } else {
                    RestoreStatus.PENDING
                }
            }
        }

    fun paneValue(
        field: GlowPaneField,
        appearance: GlowPaneAppearance,
        colorToken: String,
    ): RawValue =
        when (field) {
            GlowPaneField.COLOR -> {
                require(colorToken.isNotBlank()) { "Glow color identity is unavailable" }
                RawValue.Text(colorToken)
            }

            GlowPaneField.STYLE -> {
                RawValue.Text(appearance.style.name)
            }

            GlowPaneField.INTENSITY -> {
                RawValue.Integer(appearance.intensity)
            }

            GlowPaneField.WIDTH -> {
                RawValue.Integer(appearance.width)
            }

            GlowPaneField.PLACEMENT -> {
                RawValue.Text(appearance.placement.name)
            }

            GlowPaneField.SHAPE -> {
                RawValue.Text(appearance.shape.name)
            }

            GlowPaneField.VISIBILITY -> {
                RawValue.BooleanValue(appearance.isVisible)
            }

            else -> {
                waveformValue(field, appearance.waveform)
            }
        }

    fun waveform(config: WaveformConfig): RawValue =
        record(
            listOf(
                GlowPaneField.MOVEMENT,
                GlowPaneField.BASELINE,
                GlowPaneField.AMPLITUDE,
                GlowPaneField.WAVEFORM_INTENSITY,
                GlowPaneField.LOOP_SECONDS,
                GlowPaneField.TRACE_DENSITY,
                GlowPaneField.TRACE_LENGTH,
            ).map { it.name to waveformValue(it, config) },
        )

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
