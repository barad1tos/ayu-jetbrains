package dev.ayuislands.studio.session

import dev.ayuislands.glow.GlowOverlayAttachment
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.PortableReceipt

/** Retains a new pane's exact native membership until the outer operation settles it. */
internal class StudioGlowOverlayAdd(
    references: StudioGlowReferences,
    private val attachment: GlowOverlayAttachment,
) {
    private val identity = references.identity(attachment.entry.glassPane)
    private val original =
        StudioGlowMembershipReceipts.encodeAttachment(identity, references.overlayTokens(attachment.entry))

    val receipts: StudioList<PortableReceipt>
        get() = StudioList(listOf(StudioGlowMembershipReceipts.refreshAttachment(original, attachment.phase)))

    fun apply() {
        attachment.apply()
    }

    fun restore(blockedBy: List<PortableReceipt>) {
        attachment.restore(StudioGlowMembershipReceipts.isBlocked(setOf(identity.resource), blockedBy))
    }

    fun accept() {
        attachment.accept()
    }
}
