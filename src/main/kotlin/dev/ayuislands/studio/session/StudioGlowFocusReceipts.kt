package dev.ayuislands.studio.session

import dev.ayuislands.glow.GlowFocusAppearance
import dev.ayuislands.glow.GlowFocusField
import dev.ayuislands.glow.GlowFocusRecord
import dev.ayuislands.glow.GlowFocusRestore
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus

/** A listener and a shared component are separate resources, even when one checkpoint owns both fields. */
internal class StudioGlowFocusReceipts(
    private val ownerToken: String,
    private val process: StudioProcess,
    private val tokens: StudioNativeTokens,
) {
    fun capture(
        listener: String,
        component: String,
        record: GlowFocusRecord,
    ): Map<GlowFocusField, PortableReceipt> =
        record.fields.mapValues { (field, _) ->
            val resource = if (field == GlowFocusField.BORDER) component else listener
            val identity = NativeFieldIdentity(resource, GLOW_FOCUS_PREFIX + field.name, ownerToken)
            val before =
                when (field) {
                    GlowFocusField.APPEARANCE -> appearance(record.before)
                    GlowFocusField.BORDER ->
                        StudioNativeEvidence.record(
                            "border" to reference(record.beforeBorder),
                            "baseline" to reference(record.baselineBorder),
                        )
                }
            val after =
                when (field) {
                    GlowFocusField.APPEARANCE -> appearance(record.planned)
                    GlowFocusField.BORDER -> reference(record.plannedBorder)
                }
            PortableReceipt(
                id = glowFieldReceiptId(identity),
                owner = EffectOwner.GLOW,
                identity = identity,
                before = StudioNativeEvidence.envelope(process, before),
                lastWritten = StudioNativeEvidence.envelope(process, after),
                status = RestoreStatus.PENDING,
            )
        }

    fun restoration(
        original: PortableReceipt,
        sequence: Int,
        prepared: GlowFocusRestore,
    ): PortableReceipt {
        val evidence =
            StudioNativeEvidence.record(
                "source" to RawValue.Text(original.id),
                "observed" to reference(prepared.observed),
                "focused" to RawValue.BooleanValue(prepared.isFocused),
            )
        return original.copy(
            id = glowRestoreReceiptId(original.id, sequence),
            before = StudioNativeEvidence.envelope(process, evidence),
            lastWritten = StudioNativeEvidence.envelope(process, reference(prepared.desired)),
            status = RestoreStatus.PENDING,
        )
    }

    private fun appearance(value: GlowFocusAppearance): RawValue =
        StudioNativeEvidence.record(
            "color" to reference(value.color),
            "style" to RawValue.Text(value.style.name),
            "intensity" to RawValue.Integer(value.intensity),
            "enabled" to RawValue.BooleanValue(value.enabled),
        )

    private fun reference(value: Any?): RawValue = StudioNativeEvidence.reference(value?.let(tokens::token))
}
