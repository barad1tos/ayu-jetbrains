package dev.ayuislands.studio.session

import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.accent.AccentGroup
import dev.ayuislands.accent.AccentWriteState
import dev.ayuislands.accent.ChromeCacheSlot
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
import dev.ayuislands.studio.session.StudioNativeEvidence.reference

internal data class AccentReceiptIdentity(
    val resource: String,
    val ownerToken: String,
    val process: StudioProcess,
)

/** Opaque identities, including raw absence; no native value is evaluated or serialized. */
internal data class AccentValueTokens(
    val before: String?,
    val planned: String?,
)

internal sealed interface AccentClaimTokens {
    data object Missing : AccentClaimTokens

    data object Unowned : AccentClaimTokens

    data object Relinquished : AccentClaimTokens

    data class Owned(
        val original: String?,
        val expected: String?,
    ) : AccentClaimTokens
}

/** Evidence for ephemeral Accent fields only; editor schemes and integrations have separate recovery. */
internal object StudioAccentReceipts {
    private const val DEFAULT_PREFIX = "accent/ui/"
    private const val CLAIM_PREFIX = "accent/claim/"
    private const val PEER_FIELD = "accent/chrome/peer"
    private const val CACHE_PREFIX = "accent/chrome/cache/"
    private const val MEMBERSHIP_PREFIX = "accent/chrome/membership/"
    private const val ENABLED_PREFIX = "accent/element/enabled/"
    private const val BRACKET_FIELD = "accent/bracket/listeners"

    fun encodeEnabled(
        identity: AccentReceiptIdentity,
        id: AccentElementId,
        before: Boolean?,
        planned: Boolean,
    ): PortableReceipt {
        require(id.group != AccentGroup.CHROME)
        return create(
            identity,
            ENABLED_PREFIX + id.name,
            before?.let { RawValue.BooleanValue(it) } ?: RawValue.Null,
            RawValue.BooleanValue(planned),
        )
    }

    fun encodeBracket(
        identity: AccentReceiptIdentity,
        values: AccentValueTokens,
    ): PortableReceipt = create(identity, BRACKET_FIELD, reference(values.before), reference(values.planned))

    fun encodeMembership(
        identity: AccentReceiptIdentity,
        id: AccentElementId,
        values: AccentValueTokens,
    ): PortableReceipt {
        require(id.group == AccentGroup.CHROME)
        return create(identity, MEMBERSHIP_PREFIX + id.name, reference(values.before), reference(values.planned))
    }

    fun encodeDefault(
        identity: AccentReceiptIdentity,
        key: String,
        values: AccentValueTokens,
        baseline: String? = values.before,
    ): PortableReceipt {
        require(key.isNotBlank())
        return create(
            identity,
            DEFAULT_PREFIX + key,
            record("raw" to reference(values.before), "baseline" to reference(baseline)),
            record("raw" to reference(values.planned), "baseline" to reference(baseline)),
        )
    }

    fun encodeClaim(
        identity: AccentReceiptIdentity,
        key: String,
        before: AccentClaimTokens,
        planned: AccentClaimTokens,
        isWrite: Boolean,
    ): PortableReceipt {
        require(key.isNotBlank())
        return create(
            identity,
            CLAIM_PREFIX + key,
            record("claim" to claimValue(before), "writes-default" to RawValue.BooleanValue(isWrite)),
            record("claim" to claimValue(planned), "writes-default" to RawValue.BooleanValue(isWrite)),
        )
    }

    fun encodePeer(
        identity: AccentReceiptIdentity,
        values: AccentValueTokens,
    ): PortableReceipt = create(identity, PEER_FIELD, reference(values.before), reference(values.planned))

    fun encodeCache(
        identity: AccentReceiptIdentity,
        slot: ChromeCacheSlot,
        key: String,
        generation: Long,
        values: AccentValueTokens,
    ): PortableReceipt {
        require(key.isNotBlank())
        return create(
            identity,
            "$CACHE_PREFIX${slot.name}/$key",
            record("generation" to RawValue.LongValue(generation), "raw" to reference(values.before)),
            record("generation" to RawValue.LongValue(generation), "raw" to reference(values.planned)),
        )
    }

    fun refresh(
        receipt: PortableReceipt,
        state: AccentWriteState,
        isApplied: Boolean,
    ): PortableReceipt {
        require(receipt.owner == EffectOwner.ACCENT && isEphemeral(receipt.identity.field))
        if (isTerminal(receipt.status)) return receipt
        val status =
            when (state) {
                AccentWriteState.PREPARED -> RestoreStatus.PENDING
                AccentWriteState.OWNED -> if (isApplied) RestoreStatus.OWNED else RestoreStatus.PENDING
                AccentWriteState.RELINQUISHED -> RestoreStatus.FOREIGN
                AccentWriteState.RESTORED -> RestoreStatus.RESTORED
            }
        return receipt.copy(status = status)
    }

    fun blockedDefaults(
        resource: String,
        keys: Set<String>,
        newer: List<PortableReceipt>,
    ): Set<String> =
        buildSet {
            for (receipt in newer) {
                if (receipt.identity.resource != resource || isTerminal(receipt.status)) continue
                val field = receipt.identity.field
                val key = defaultKey(field)
                if (key in keys) add(requireNotNull(key))
                if (!isEphemeral(field)) addAll(keys)
            }
        }

    fun isBlocked(
        resource: String,
        field: String,
        newer: List<PortableReceipt>,
    ): Boolean =
        newer.any {
            it.identity.resource == resource &&
                !isTerminal(it.status) &&
                (fieldsOverlap(field, it.identity.field) || !isEphemeral(it.identity.field))
        }

    /** Lost process-local handles cannot restore persistent Accent fields or claim cleanup in the same process. */
    fun recoverLost(
        receipts: List<PortableReceipt>,
        currentProcess: StudioProcess?,
    ): StudioList<PortableReceipt> =
        StudioList(
            receipts.map { receipt ->
                require(receipt.owner == EffectOwner.ACCENT)
                if (isTerminal(receipt.status)) {
                    receipt
                } else if (!isEphemeral(receipt.identity.field)) {
                    receipt.copy(status = RestoreStatus.UNAVAILABLE)
                } else {
                    val origin = readProcess(receipt.before)
                    require(origin == readProcess(receipt.lastWritten)) { "Accent receipt process provenance differs" }
                    val gone = StudioProcessPolicy.compare(origin, currentProcess) == StudioProcessRelation.DIFFERENT
                    receipt.copy(status = if (gone) RestoreStatus.RESTORED else RestoreStatus.UNAVAILABLE)
                }
            },
        )

    private fun create(
        identity: AccentReceiptIdentity,
        field: String,
        before: RawValue,
        planned: RawValue,
    ): PortableReceipt {
        require(identity.resource.isNotBlank() && identity.ownerToken.isNotBlank())
        val native = NativeFieldIdentity(identity.resource, field, identity.ownerToken)
        return PortableReceipt(
            "${native.ownerToken}/${native.resource}/${native.field}",
            EffectOwner.ACCENT,
            native,
            envelope(identity.process, before),
            envelope(identity.process, planned),
            RestoreStatus.PENDING,
        )
    }

    private fun claimValue(claim: AccentClaimTokens): RawValue =
        when (claim) {
            AccentClaimTokens.Missing -> {
                RawValue.Null
            }

            AccentClaimTokens.Unowned -> {
                record("kind" to RawValue.Text("unowned"))
            }

            AccentClaimTokens.Relinquished -> {
                record("kind" to RawValue.Text("relinquished"))
            }

            is AccentClaimTokens.Owned -> {
                record(
                    "kind" to RawValue.Text("owned"),
                    "original" to reference(claim.original),
                    "expected" to reference(claim.expected),
                )
            }
        }

    private fun defaultKey(field: String): String? =
        when {
            field.startsWith(DEFAULT_PREFIX) -> field.removePrefix(DEFAULT_PREFIX).takeIf(String::isNotBlank)
            field.startsWith(CLAIM_PREFIX) -> field.removePrefix(CLAIM_PREFIX).takeIf(String::isNotBlank)
            else -> null
        }

    private fun fieldsOverlap(
        field: String,
        other: String,
    ): Boolean {
        if (field == other) return true
        val key = defaultKey(field) ?: return false
        return key == defaultKey(other)
    }

    private fun isEphemeral(field: String): Boolean {
        if (defaultKey(field) != null || field == PEER_FIELD || field == BRACKET_FIELD) return true
        if (field.startsWith(ENABLED_PREFIX)) {
            val id = field.removePrefix(ENABLED_PREFIX)
            return AccentElementId.entries.any { it.group != AccentGroup.CHROME && it.name == id }
        }
        if (field.startsWith(MEMBERSHIP_PREFIX)) {
            val id = field.removePrefix(MEMBERSHIP_PREFIX)
            return AccentElementId.entries.any { it.group == AccentGroup.CHROME && it.name == id }
        }
        if (!field.startsWith(CACHE_PREFIX)) return false
        val parts = field.removePrefix(CACHE_PREFIX).split('/', limit = 2)
        return parts.size == 2 && parts[1].isNotBlank() && ChromeCacheSlot.entries.any { it.name == parts[0] }
    }
}
