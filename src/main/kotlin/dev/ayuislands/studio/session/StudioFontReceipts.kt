package dev.ayuislands.studio.session

import dev.ayuislands.font.FontData
import dev.ayuislands.font.FontPreferenceField
import dev.ayuislands.font.FontPreferencePolicy
import dev.ayuislands.font.FontRestoreOrigin
import dev.ayuislands.font.FontRestoreState
import dev.ayuislands.font.FontSnapshot
import dev.ayuislands.font.FontSurface
import dev.ayuislands.font.FontWritePhase
import dev.ayuislands.font.FontWriteRecord
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.NativeValue
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus

/** Stable identity supplied by the native scheme census; a name never substitutes for resource identity. */
internal data class FontReceiptIdentity(
    val resource: String,
    val surface: FontSurface,
    val ownerToken: String,
)

/** Maps one native attempt to immutable original receipts and append-only restoration evidence. */
internal object StudioFontReceipts {
    fun blockedFields(
        identity: FontReceiptIdentity,
        receipts: List<PortableReceipt>,
    ): Set<FontPreferenceField> {
        val prefix = "font/${identity.surface.name}/"
        return receipts
            .flatMap { receipt ->
                val native = receipt.identity
                when {
                    native.resource != identity.resource || isTerminal(state(receipt.status)) -> emptyList()
                    native.field == StudioSchemeIdentityReceipt.FIELD -> FontPreferenceField.entries
                    !native.field.startsWith(prefix) -> emptyList()
                    else -> {
                        val field =
                            FontPreferenceField.entries.singleOrNull {
                                it.name == native.field.removePrefix(prefix)
                            }
                        if (field == null) FontPreferenceField.entries else listOf(field)
                    }
                }
            }.toSet()
    }

    fun encode(
        identity: FontReceiptIdentity,
        record: FontWriteRecord,
    ): StudioList<PortableReceipt> {
        require(identity.resource.isNotBlank() && identity.ownerToken.isNotBlank())
        val originals =
            scopes(record.before, record.target).map { scope ->
                val native = nativeIdentity(identity, scope)
                PortableReceipt(
                    originalId(native),
                    EffectOwner.FONT,
                    native,
                    payload(record.before, record.effectiveBefore),
                    NativeValue.Present(StudioFontCodec.encode(record.target)),
                    status(record, scope, isOriginal = true),
                )
            }
        val restorations =
            record.origins.mapIndexed { index, origin ->
                val scope =
                    when (origin) {
                        is FontRestoreOrigin.Mode -> ReceiptScope.Mode
                        is FontRestoreOrigin.Field -> ReceiptScope.Field(origin.field)
                    }
                val native = nativeIdentity(identity, scope)
                require(originals.any { it.identity == native }) { "Restore scope does not belong to the attempt" }
                val source = originalId(native)
                val before =
                    when (origin) {
                        is FontRestoreOrigin.Mode -> payload(origin.snapshot, origin.effective, source)
                        is FontRestoreOrigin.Field ->
                            payload(
                                FontSnapshot.Explicit(origin.preferences),
                                origin.preferences,
                                source,
                            )
                    }
                PortableReceipt(
                    "$source/restore/$index",
                    EffectOwner.FONT,
                    native,
                    before,
                    NativeValue.Present(StudioFontCodec.encode(record.before)),
                    status(record, scope, isOriginal = false),
                )
            }
        return StudioList(originals + restorations)
    }

    fun decode(
        identity: FontReceiptIdentity,
        receipts: List<PortableReceipt>,
    ): FontWriteRecord {
        require(receipts.isNotEmpty() && receipts.map { it.id }.distinct().size == receipts.size)
        val first = receipts.first()
        val baseline = readPayload(first.before)
        require(baseline.source == null)
        val target = StudioFontCodec.decode(present(first.lastWritten))
        val fieldScopes = scopes(baseline.snapshot, target)
        require(receipts.size >= fieldScopes.size)
        val originals = receipts.take(fieldScopes.size)
        for ((index, receipt) in originals.withIndex()) {
            val native = nativeIdentity(identity, fieldScopes[index])
            require(receipt.owner == EffectOwner.FONT && receipt.identity == native && receipt.id == originalId(native))
            require(receipt.before == first.before && receipt.lastWritten == first.lastWritten)
        }
        val fields = mutableMapOf<FontPreferenceField, FontRestoreState>()
        var mode = FontRestoreState.PENDING
        for ((scope, receipt) in fieldScopes.zip(originals)) {
            when (scope) {
                ReceiptScope.Mode -> mode = state(receipt.status)
                is ReceiptScope.Field -> fields[scope.value] = state(receipt.status)
            }
        }
        val origins =
            receipts.drop(fieldScopes.size).mapIndexed { index, receipt ->
                val restored = readRestore(receipt, originals, fieldScopes, index)
                when (val scope = restored.scope) {
                    ReceiptScope.Mode -> mode = restored.state
                    is ReceiptScope.Field -> fields[scope.value] = restored.state
                }
                restored.origin
            }
        return FontWriteRecord(baseline.snapshot, target, baseline.effective, phase(originals), mode, fields, origins)
    }

    private fun readRestore(
        receipt: PortableReceipt,
        originals: List<PortableReceipt>,
        scopes: List<ReceiptScope>,
        index: Int,
    ): RestoredReceipt {
        val payload = readPayload(receipt.before)
        val sourceIndex = originals.indexOfFirst { it.id == payload.source }
        require(sourceIndex >= 0)
        val original = originals[sourceIndex]
        require(receipt.owner == EffectOwner.FONT && receipt.identity == original.identity)
        require(receipt.id == "${original.id}/restore/$index")
        val baseline = readPayload(original.before)
        require(receipt.lastWritten == NativeValue.Present(StudioFontCodec.encode(baseline.snapshot)))
        val originalState = state(original.status)
        val restoredState = state(receipt.status)
        require(isTerminal(originalState) == isTerminal(restoredState))
        require(!isTerminal(originalState) || originalState == restoredState)
        val scope = scopes[sourceIndex]
        val origin =
            when (scope) {
                ReceiptScope.Mode -> FontRestoreOrigin.Mode(payload.snapshot, payload.effective)
                is ReceiptScope.Field -> {
                    val explicit = requireNotNull(payload.snapshot as? FontSnapshot.Explicit)
                    FontRestoreOrigin.Field(scope.value, explicit.preferences)
                }
            }
        return RestoredReceipt(
            scope,
            origin,
            if (isTerminal(restoredState)) restoredState else FontRestoreState.WRITING,
        )
    }

    private fun payload(
        snapshot: FontSnapshot,
        effective: FontData,
        source: String? = null,
    ): NativeValue {
        val fields =
            mutableListOf(
                "version" to RawValue.Integer(1),
                "role" to RawValue.Text(if (source == null) "apply" else "restore"),
                "snapshot" to StudioFontCodec.encode(snapshot),
            )
        when (snapshot) {
            FontSnapshot.Inherited ->
                fields.add(
                    "effective" to StudioFontCodec.encode(FontSnapshot.Explicit(effective)),
                )
            is FontSnapshot.Explicit -> require(FontPreferencePolicy.matches(snapshot.preferences, effective))
        }
        source?.let { fields.add("source" to RawValue.Text(it)) }
        return NativeValue.Present(StudioFontCodec.record(fields))
    }

    private fun readPayload(value: NativeValue): FontPayload {
        val fields = StudioFontCodec.readFields(present(value))
        require(fields["version"] == RawValue.Integer(1))
        val source =
            when (fields["role"]) {
                RawValue.Text("apply") -> null
                RawValue.Text(
                    "restore",
                ),
                -> requireNotNull(fields["source"] as? RawValue.Text).value.also { require(it.isNotBlank()) }
                else -> throw IllegalArgumentException("Unknown Font receipt role")
            }
        val snapshot = StudioFontCodec.decode(fields.getValue("snapshot"))
        val keys =
            buildSet {
                addAll(setOf("version", "role", "snapshot"))
                if (source != null) add("source")
                if (snapshot == FontSnapshot.Inherited) add("effective")
            }
        require(fields.keys == keys)
        val effective =
            when (snapshot) {
                FontSnapshot.Inherited ->
                    requireNotNull(
                        StudioFontCodec.decode(fields.getValue("effective")) as? FontSnapshot.Explicit,
                    ).preferences
                is FontSnapshot.Explicit -> snapshot.preferences
            }
        return FontPayload(snapshot, effective, source)
    }

    private fun scopes(
        before: FontSnapshot,
        target: FontSnapshot,
    ): List<ReceiptScope> =
        if (before is FontSnapshot.Explicit && target is FontSnapshot.Explicit) {
            FontPreferenceField.entries.map(ReceiptScope::Field)
        } else {
            listOf(ReceiptScope.Mode)
        }

    private fun nativeIdentity(
        identity: FontReceiptIdentity,
        scope: ReceiptScope,
    ): NativeFieldIdentity {
        val name =
            when (scope) {
                ReceiptScope.Mode -> "MODE"
                is ReceiptScope.Field -> scope.value.name
            }
        return NativeFieldIdentity(identity.resource, "font/${identity.surface.name}/$name", identity.ownerToken)
    }

    private fun originalId(identity: NativeFieldIdentity): String =
        "${identity.ownerToken}/${identity.resource}/${identity.field}"

    private fun status(
        record: FontWriteRecord,
        scope: ReceiptScope,
        isOriginal: Boolean,
    ): RestoreStatus {
        val restored =
            when (scope) {
                ReceiptScope.Mode -> record.inheritanceState
                is ReceiptScope.Field -> record.fields[scope.value] ?: FontRestoreState.PENDING
            }
        return when (restored) {
            FontRestoreState.RESTORED -> RestoreStatus.RESTORED
            FontRestoreState.FOREIGN -> RestoreStatus.FOREIGN
            FontRestoreState.PENDING, FontRestoreState.WRITING ->
                if (isOriginal &&
                    record.applyPhase == FontWritePhase.SETTLED
                ) {
                    RestoreStatus.OWNED
                } else {
                    RestoreStatus.PENDING
                }
        }
    }

    private fun phase(originals: List<PortableReceipt>): FontWritePhase {
        val unresolved = originals.map { it.status }.filterNot { isTerminal(state(it)) }
        if (unresolved.isEmpty()) return FontWritePhase.SETTLED
        if (RestoreStatus.OWNED in unresolved) {
            require(unresolved.all { it == RestoreStatus.OWNED }) { "Inconsistent Font apply provenance" }
            return FontWritePhase.SETTLED
        }
        return FontWritePhase.WRITING
    }

    private fun state(status: RestoreStatus): FontRestoreState =
        when (status) {
            RestoreStatus.RESTORED -> FontRestoreState.RESTORED
            RestoreStatus.FOREIGN -> FontRestoreState.FOREIGN
            RestoreStatus.PENDING, RestoreStatus.OWNED, RestoreStatus.UNAVAILABLE -> FontRestoreState.PENDING
            RestoreStatus.KEPT_AFTER_RESTART -> throw IllegalArgumentException("Invalid Font restore status")
        }

    private fun isTerminal(state: FontRestoreState): Boolean =
        state == FontRestoreState.RESTORED || state == FontRestoreState.FOREIGN

    private fun present(value: NativeValue): RawValue = requireNotNull(value as? NativeValue.Present).value
}

private sealed interface ReceiptScope {
    data object Mode : ReceiptScope

    data class Field(
        val value: FontPreferenceField,
    ) : ReceiptScope
}

private data class FontPayload(
    val snapshot: FontSnapshot,
    val effective: FontData,
    val source: String?,
)

private data class RestoredReceipt(
    val scope: ReceiptScope,
    val origin: FontRestoreOrigin,
    val state: FontRestoreState,
)
