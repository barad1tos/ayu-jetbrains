package dev.ayuislands.studio.session

import dev.ayuislands.accent.AccentWriteState
import dev.ayuislands.accent.UiDefaultObservation
import dev.ayuislands.accent.UiDefaultWrite
import dev.ayuislands.accent.UiDefaultsCheckpoint
import dev.ayuislands.accent.UiDefaultsOwnership
import dev.ayuislands.accent.UiDefaultsTarget
import dev.ayuislands.accent.UiOverridePreparation
import dev.ayuislands.accent.UiOverrideState
import dev.ayuislands.accent.UiOverrideValue
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.StudioNativeEvidence.isTerminal

/** One final key write and its optional provenance share a retained, acknowledged native checkpoint. */
internal class StudioAccentDefaults private constructor(
    references: StudioAccentReferences,
    private val checkpoint: UiDefaultsCheckpoint,
    private val native: UiDefaultObservation,
    private val ownership: UiOverridePreparation?,
) : StudioAccentWrite {
    private val identity =
        references
            .identity(
                native.target.defaults,
            ).copy(ownerToken = requireNotNull(references.token(checkpoint)))
    private var phase = StudioAccentPhase.PREPARED
    private var rawReceipt =
        (native as? UiDefaultWrite)?.let {
            StudioAccentReceipts.encodeDefault(
                identity,
                it.key,
                AccentValueTokens(references.token(it.before), references.token(it.planned)),
                references.token(it.baseline),
            )
        }
    private var claimReceipt =
        ownership?.let {
            StudioAccentReceipts.encodeClaim(
                identity,
                native.key,
                references.claim(it.capturedClaim),
                references.claim(it.transition.next),
                native is UiDefaultWrite,
            )
        }

    override val receipts: StudioList<PortableReceipt>
        get() {
            refreshReceipts()
            return StudioList(listOfNotNull(rawReceipt, claimReceipt))
        }

    override fun apply() {
        check(phase == StudioAccentPhase.PREPARED) { "Accent default application was already started" }
        phase = StudioAccentPhase.APPLYING
        try {
            val applied = ownership?.let { it.owner.apply(it) } ?: checkpoint.apply(native as UiDefaultWrite)
            check(applied) { "Prepared Accent default changed before application: ${native.key}" }
            phase = StudioAccentPhase.APPLIED
        } finally {
            refreshReceipts()
        }
    }

    override fun restore(blockedBy: List<PortableReceipt>) {
        check(phase != StudioAccentPhase.ACCEPTED) { "Accepted Accent defaults cannot be restored" }
        if (phase == StudioAccentPhase.RESTORED) return
        if (phase == StudioAccentPhase.PREPARED) {
            // No write was admitted, so even a provenance restore would be an unrelated mutation.
            checkpoint.commit()
            phase = StudioAccentPhase.RESTORED
            refreshReceipts()
            return
        }
        phase = StudioAccentPhase.RESTORING
        try {
            checkpoint.restoreFields(
                StudioAccentReceipts.blockedDefaults(identity.resource, setOf(native.key), blockedBy),
            )
            check(!checkpoint.hasPending) { "Accent default restoration remains incomplete: ${native.key}" }
            phase = StudioAccentPhase.RESTORED
        } finally {
            refreshReceipts()
        }
    }

    override fun accept() {
        if (phase == StudioAccentPhase.ACCEPTED) return
        check(phase == StudioAccentPhase.APPLIED) { "Accent default application has not completed" }
        refreshReceipts()
        checkpoint.commit()
        phase = StudioAccentPhase.ACCEPTED
    }

    private fun refreshReceipts() {
        if (phase == StudioAccentPhase.ACCEPTED) return
        val state = checkpoint.stateOf(native)
        rawReceipt = rawReceipt?.let { StudioAccentReceipts.refresh(it, state, phase == StudioAccentPhase.APPLIED) }
        val claim = claimReceipt ?: return
        if (!isTerminal(claim.status)) claimReceipt = claim.copy(status = claimStatus(state))
    }

    private fun claimStatus(state: AccentWriteState): RestoreStatus {
        if (state == AccentWriteState.RELINQUISHED) return RestoreStatus.FOREIGN
        val prepared = requireNotNull(ownership)
        val current = prepared.owner.observeClaim(native.key)
        return when (phase) {
            StudioAccentPhase.PREPARED, StudioAccentPhase.APPLYING, StudioAccentPhase.RESTORING -> {
                RestoreStatus.PENDING
            }

            StudioAccentPhase.APPLIED -> {
                if (current === prepared.transition.next) RestoreStatus.OWNED else RestoreStatus.FOREIGN
            }

            StudioAccentPhase.RESTORED -> {
                val previous = prepared.capturedClaim
                if (current === previous || (previous === null && current === UiOverrideState.Unowned)) {
                    RestoreStatus.RESTORED
                } else {
                    RestoreStatus.FOREIGN
                }
            }

            StudioAccentPhase.ACCEPTED -> {
                error("Accepted Accent evidence is frozen")
            }
        }
    }

    companion object {
        fun prepare(
            references: StudioAccentReferences,
            owner: UiDefaultsOwnership,
            key: String,
            requested: UiOverrideValue?,
            target: UiDefaultsTarget = UiDefaultsTarget(),
        ): StudioAccentDefaults {
            val checkpoint = UiDefaultsCheckpoint(target)
            owner.record(checkpoint, setOf(key))
            val ownership = requireNotNull(owner.prepare(key, requested, checkpoint))
            return StudioAccentDefaults(references, checkpoint, ownership.native, ownership)
        }

        fun prepareRaw(
            references: StudioAccentReferences,
            key: String,
            value: Any?,
            target: UiDefaultsTarget = UiDefaultsTarget(),
        ): StudioAccentDefaults {
            val checkpoint = UiDefaultsCheckpoint(target)
            val write = requireNotNull(checkpoint.prepare(key, value))
            return StudioAccentDefaults(references, checkpoint, write, null)
        }
    }
}
