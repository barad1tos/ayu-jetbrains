package dev.ayuislands.studio.session

import dev.ayuislands.glow.GlowFocusField
import dev.ayuislands.glow.GlowPaneField
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.model.StudioProcessRelation
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.PreparedToken
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.persistence.StudioReceiptPolicy

/** Settles Glow's old process-local records without reconnecting native widgets or replaying Apply. */
internal object StudioGlowRecovery {
    fun reattach(
        token: PreparedToken,
        receipts: StudioList<PortableReceipt>,
        currentProcess: StudioProcess?,
    ): StudioOwnerOperation? {
        if (receipts.isEmpty()) return null
        val ids = receipts.map(PortableReceipt::id).toSet()
        require(receipts.all { belongsTo(token, it) && hasOwnId(it, ids) }) {
            "Glow receipt identity does not match the transaction"
        }
        val origin = recordedProcess(receipts.first())
        require(receipts.drop(1).all { recordedProcess(it) == origin }) { "Glow receipts disagree on their process" }
        if (receipts.all(StudioReceiptPolicy::isTerminal)) return SettledGlow(receipts)
        if (StudioProcessPolicy.compare(origin, currentProcess) != StudioProcessRelation.DIFFERENT) return null
        val recovered =
            receipts.map { receipt ->
                if (StudioReceiptPolicy.isTerminal(receipt)) receipt else receipt.copy(status = RestoreStatus.RESTORED)
            }
        return SettledGlow(StudioList(recovered))
    }

    private fun belongsTo(
        token: PreparedToken,
        receipt: PortableReceipt,
    ): Boolean {
        val (resource, field, ownerToken) = receipt.identity
        if (receipt.owner != EffectOwner.GLOW) return false
        val index = resource.substringAfterLast('/').toIntOrNull() ?: return false
        if (index < 0 || resource != "$ownerToken/$index") return false
        val namespace =
            when {
                field == GLOW_MEMBERSHIP_FIELD -> "glow-membership"
                GlowPaneField.entries.any { field == GLOW_PANE_PREFIX + it.name } -> "glow"
                GlowFocusField.entries.any { field == GLOW_FOCUS_PREFIX + it.name } -> "glow-focus"
                else -> return false
            }
        return ownerToken == "$namespace/${token.transactionId}"
    }

    private fun hasOwnId(
        receipt: PortableReceipt,
        ids: Set<String>,
    ): Boolean {
        val field = receipt.identity.field
        if (field == GLOW_MEMBERSHIP_FIELD) return receipt.id == glowMembershipReceiptId(receipt.identity.resource)
        val originalId = glowFieldReceiptId(receipt.identity)
        if (receipt.id == originalId) return true
        if (field != GLOW_FOCUS_PREFIX + GlowFocusField.BORDER.name || originalId !in ids) return false
        val sequence = receipt.id.substringAfterLast('/').toIntOrNull() ?: return false
        if (sequence < 0 || receipt.id != glowRestoreReceiptId(originalId, sequence)) return false
        val before = StudioNativeEvidence.fields(StudioNativeEvidence.readValue(receipt.before))
        return before["source"] == RawValue.Text(originalId)
    }

    private fun recordedProcess(receipt: PortableReceipt): StudioProcess {
        val recorded = StudioNativeEvidence.readProcess(receipt.before)
        val planned = StudioNativeEvidence.readProcess(receipt.lastWritten)
        require(recorded == planned) { "Glow receipt process provenance differs" }
        return recorded
    }

    private class SettledGlow(
        override val receipts: StudioList<PortableReceipt>,
    ) : StudioOwnerOperation {
        override suspend fun apply(writes: StudioNativeWrite): Boolean = error("Cannot apply during Glow recovery")

        override suspend fun restore(blocked: List<PortableReceipt>): RestoreContinuation = RestoreContinuation.COMPLETE

        override suspend fun accept() = Unit
    }
}
