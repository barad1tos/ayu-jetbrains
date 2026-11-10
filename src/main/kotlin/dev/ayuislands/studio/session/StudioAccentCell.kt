package dev.ayuislands.studio.session

import dev.ayuislands.accent.AccentWriteCheckpoint
import dev.ayuislands.accent.ChromeBaseColors
import dev.ayuislands.accent.ChromePeerOverrides
import dev.ayuislands.accent.ExternalChromeOwnership
import dev.ayuislands.accent.elements.BracketFadeManager
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.theme.EditorSchemeOverrides
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeCheckpoints.EnabledCheckpoint

/** Retains one peer or cache cell; only outer durable acceptance may release its native rollback. */
internal class StudioAccentCell private constructor(
    private val native: AccentWriteCheckpoint,
    private var receipt: PortableReceipt,
    private val dependencies: List<NativeFieldIdentity> = emptyList(),
) : StudioAccentWrite {
    private var phase = StudioAccentPhase.PREPARED

    override val receipts: StudioList<PortableReceipt>
        get() {
            refreshReceipt()
            return StudioList(listOf(receipt))
        }

    override fun apply() {
        check(phase == StudioAccentPhase.PREPARED) { "Accent cell application was already started" }
        phase = StudioAccentPhase.APPLYING
        try {
            check(native.apply()) { "Prepared Accent cell changed before application" }
            phase = StudioAccentPhase.APPLIED
        } finally {
            refreshReceipt()
        }
    }

    override fun restore(blockedBy: List<PortableReceipt>) {
        check(phase != StudioAccentPhase.ACCEPTED) { "Accepted Accent cells cannot be restored" }
        if (phase == StudioAccentPhase.RESTORED) return
        phase = StudioAccentPhase.RESTORING
        try {
            check(!native.hasPending || !isBlocked(blockedBy)) {
                "Accent cell restoration blocked by a newer write"
            }
            native.restore()
            check(!native.hasPending) { "Accent cell restoration remains incomplete" }
            phase = StudioAccentPhase.RESTORED
        } finally {
            refreshReceipt()
        }
    }

    override fun accept() {
        if (phase == StudioAccentPhase.ACCEPTED) return
        check(phase == StudioAccentPhase.APPLIED) { "Accent cell application has not completed" }
        refreshReceipt()
        native.commit()
        native.releaseResources()
        phase = StudioAccentPhase.ACCEPTED
    }

    private fun refreshReceipt() {
        if (phase != StudioAccentPhase.ACCEPTED) {
            receipt = StudioAccentReceipts.refresh(receipt, native.state, phase == StudioAccentPhase.APPLIED)
        }
    }

    private fun isBlocked(blockedBy: List<PortableReceipt>): Boolean {
        val identity = receipt.identity
        if (StudioAccentReceipts.isBlocked(identity.resource, identity.field, blockedBy)) return true
        return dependencies.any { StudioAccentReceipts.isBlocked(it.resource, it.field, blockedBy) }
    }

    companion object {
        fun enabled(
            references: StudioAccentReferences,
            native: EnabledCheckpoint,
            fields: List<PortableReceipt>,
        ): StudioAccentCell {
            val identity =
                references
                    .identity(
                        EditorSchemeOverrides,
                    ).copy(ownerToken = requireNotNull(references.token(native)))
            return StudioAccentCell(
                native,
                StudioAccentReceipts.encodeEnabled(identity, native.id, native.before, native.planned),
                fields.map { it.identity },
            )
        }

        fun bracket(
            references: StudioAccentReferences,
            native: BracketFadeManager.FadeCheckpoint,
            fields: List<PortableReceipt>,
        ): StudioAccentCell {
            val identity =
                references
                    .identity(
                        BracketFadeManager,
                    ).copy(ownerToken = requireNotNull(references.token(native)))
            return StudioAccentCell(
                native,
                StudioAccentReceipts.encodeBracket(
                    identity,
                    AccentValueTokens(references.token(native.before), references.token(native.planned)),
                ),
                fields.map { it.identity },
            )
        }

        fun membership(
            references: StudioAccentReferences,
            native: ExternalChromeOwnership.OwnershipCheckpoint,
            surfaces: List<PortableReceipt>,
        ): StudioAccentCell {
            val identity =
                references.identity(ExternalChromeOwnership).copy(ownerToken = requireNotNull(references.token(native)))
            return StudioAccentCell(
                native,
                StudioAccentReceipts.encodeMembership(
                    identity,
                    native.id,
                    AccentValueTokens(references.token(native.before), references.token(native.planned)),
                ),
                surfaces.map { it.identity },
            )
        }

        fun peer(
            references: StudioAccentReferences,
            native: ChromePeerOverrides.PeerCheckpoint,
        ): StudioAccentCell {
            val identity =
                references
                    .identity(
                        native.component,
                    ).copy(ownerToken = requireNotNull(references.token(native)))
            return StudioAccentCell(
                native,
                StudioAccentReceipts.encodePeer(
                    identity,
                    AccentValueTokens(references.token(native.before), references.token(native.planned)),
                ),
            )
        }

        fun cache(
            references: StudioAccentReferences,
            native: ChromeBaseColors.CacheCheckpoint,
        ): StudioAccentCell {
            val identity =
                references
                    .identity(
                        ChromeBaseColors,
                    ).copy(ownerToken = requireNotNull(references.token(native)))
            return StudioAccentCell(
                native,
                StudioAccentReceipts.encodeCache(
                    identity,
                    native.slot,
                    native.key,
                    native.capturedGeneration,
                    AccentValueTokens(references.token(native.before), references.token(native.planned)),
                ),
            )
        }
    }
}
