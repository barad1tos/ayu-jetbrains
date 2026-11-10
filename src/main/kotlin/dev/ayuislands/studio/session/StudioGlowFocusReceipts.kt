package dev.ayuislands.studio.session

import dev.ayuislands.glow.GlowBorderRestore
import dev.ayuislands.glow.GlowCheckpointPhase
import dev.ayuislands.glow.GlowFieldState
import dev.ayuislands.glow.GlowFocusAppearance
import dev.ayuislands.glow.GlowFocusField
import dev.ayuislands.glow.GlowFocusRecord
import dev.ayuislands.glow.GlowInitialBorderRecord
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.StudioNativeEvidence.reference

internal data class GlowFocusTokens(
    val beforeColor: String,
    val plannedColor: String,
    val beforeBorder: String?,
    val plannedBorder: String?,
    val baselineBorder: String?,
)

internal data class GlowBorderTokens(
    val observed: String?,
    val desired: String?,
)

/** Listener appearance and a shared component border have independent native ownership. */
internal object StudioGlowFocusReceipts {
    private const val PREFIX = "glow/focus/"
    private const val BORDER = "${PREFIX}BORDER"

    fun encodeInitial(
        component: GlowReceiptIdentity,
        snapshot: GlowInitialBorderRecord,
        tokens: GlowBorderTokens,
    ): PortableReceipt? {
        if (snapshot.before === snapshot.planned) return null
        require((snapshot.before == null) == (tokens.observed == null))
        require((snapshot.planned == null) == (tokens.desired == null))
        return StudioGlowReceipts.create(
            component,
            BORDER,
            StudioNativeEvidence.record(
                "border" to reference(tokens.observed),
                "baseline" to reference(tokens.observed),
                "focused" to RawValue.BooleanValue(snapshot.isFocused),
            ),
            reference(tokens.desired),
            StudioGlowReceipts.status(GlowCheckpointPhase.PREPARED, snapshot.state),
        )
    }

    fun refreshInitial(
        receipt: PortableReceipt,
        snapshot: GlowInitialBorderRecord,
        phase: GlowCheckpointPhase,
    ): PortableReceipt {
        require(receipt.owner == EffectOwner.GLOW && receipt.identity.field == BORDER)
        require(receipt.id == StudioGlowReceipts.id(receipt.identity))
        return if (StudioNativeEvidence.isTerminal(receipt.status)) {
            receipt
        } else {
            receipt.copy(status = StudioGlowReceipts.status(phase, snapshot.state))
        }
    }

    fun encode(
        listener: GlowReceiptIdentity,
        componentResource: String,
        snapshot: GlowFocusRecord,
        tokens: GlowFocusTokens,
    ): StudioList<PortableReceipt> =
        StudioList(
            GlowFocusField.entries.filter { it in snapshot.fields }.map { field ->
                val before: RawValue
                val planned: RawValue
                val identity: GlowReceiptIdentity
                when (field) {
                    GlowFocusField.APPEARANCE -> {
                        identity = listener
                        before = appearance(snapshot.before, tokens.beforeColor)
                        planned = appearance(snapshot.planned, tokens.plannedColor)
                    }

                    GlowFocusField.BORDER -> {
                        identity = listener.copy(resource = componentResource)
                        before =
                            StudioNativeEvidence.record(
                                "border" to reference(tokens.beforeBorder),
                                "baseline" to reference(tokens.baselineBorder),
                            )
                        planned = reference(tokens.plannedBorder)
                    }
                }
                StudioGlowReceipts.create(
                    identity,
                    "$PREFIX${field.name}",
                    before,
                    planned,
                    StudioGlowReceipts.status(snapshot.phase, snapshot.fields.getValue(field)),
                )
            },
        )

    /** Append this once for each fresh captured native preparation, before its write acknowledgement. */
    fun encodeRestore(
        component: GlowReceiptIdentity,
        sequence: Int,
        prepared: GlowBorderRestore,
        tokens: GlowBorderTokens,
    ): PortableReceipt {
        require(sequence >= 0)
        val source = StudioGlowReceipts.id(NativeFieldIdentity(component.resource, BORDER, component.ownerToken))
        val before =
            StudioNativeEvidence.record(
                "source" to RawValue.Text(source),
                "observed" to reference(tokens.observed),
                "focused" to RawValue.BooleanValue(prepared.isFocused),
                "outcome" to RawValue.Text(prepared.outcome.name),
            )
        return StudioGlowReceipts
            .create(component, BORDER, before, reference(tokens.desired), RestoreStatus.PENDING)
            .copy(id = "$source/restore/$sequence")
    }

    fun refresh(
        receipts: List<PortableReceipt>,
        snapshot: GlowFocusRecord,
    ): StudioList<PortableReceipt> {
        require(receipts.map { it.id }.distinct().size == receipts.size)
        val originals = receipts.filter { it.id == StudioGlowReceipts.id(it.identity) }
        val scopes = originals.map { focusField(it.identity.field) }
        require(scopes.toSet() == snapshot.fields.keys && scopes.size == snapshot.fields.size)
        return StudioList(receipts.map { refreshReceipt(it, snapshot) })
    }

    fun blockedFields(
        listenerResource: String,
        componentResource: String,
        newer: List<PortableReceipt>,
    ): Set<GlowFocusField> =
        GlowFocusField.entries.filterTo(mutableSetOf()) { scope ->
            val resource = if (scope == GlowFocusField.BORDER) componentResource else listenerResource
            newer.any { receipt ->
                val native = receipt.identity
                val nativeField = focusField(native.field)
                when {
                    native.resource != resource -> false
                    StudioNativeEvidence.isTerminal(receipt.status) -> false
                    !native.field.startsWith(PREFIX) -> false
                    else -> nativeField == null || nativeField == scope
                }
            }
        }

    private fun refreshReceipt(
        receipt: PortableReceipt,
        snapshot: GlowFocusRecord,
    ): PortableReceipt {
        require(receipt.owner == EffectOwner.GLOW)
        val scope = requireNotNull(focusField(receipt.identity.field))
        val source = StudioGlowReceipts.id(receipt.identity)
        val isOriginal = receipt.id == source
        if (!isOriginal) {
            require(scope == GlowFocusField.BORDER && receipt.id.startsWith("$source/restore/"))
            val sequence = receipt.id.removePrefix("$source/restore/").toIntOrNull()
            require(sequence != null && sequence >= 0)
        }
        if (StudioNativeEvidence.isTerminal(receipt.status)) return receipt
        val state = snapshot.fields.getValue(scope)
        val status =
            if (isOriginal || state == GlowFieldState.RESTORED || state == GlowFieldState.FOREIGN) {
                StudioGlowReceipts.status(snapshot.phase, state)
            } else {
                RestoreStatus.PENDING
            }
        return receipt.copy(status = status)
    }

    private fun focusField(name: String): GlowFocusField? =
        GlowFocusField.entries.singleOrNull { "$PREFIX${it.name}" == name }

    private fun appearance(
        value: GlowFocusAppearance,
        color: String,
    ): RawValue =
        StudioNativeEvidence.record(
            "color" to reference(color),
            "style" to RawValue.Text(value.style.name),
            "intensity" to RawValue.Integer(value.intensity),
            "enabled" to RawValue.BooleanValue(value.enabled),
        )
}
