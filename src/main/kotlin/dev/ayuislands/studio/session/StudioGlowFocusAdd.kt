package dev.ayuislands.studio.session

import dev.ayuislands.glow.GlowFocusAttachment
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.PortableReceipt

/** Registration and initial border settle independently, while cleanup respects both resources. */
internal class StudioGlowFocusAdd(
    references: StudioGlowReferences,
    private val attachment: GlowFocusAttachment,
) {
    private val listenerIdentity = references.identity(attachment.listener)
    private val componentIdentity = references.identity(attachment.component)
    private val membership =
        StudioGlowMembershipReceipts.encodeAttachment(
            listenerIdentity,
            GlowMemberTokens.Focus(
                listenerIdentity.resource,
                componentIdentity.resource,
                attachment.parent?.let(references::token),
                attachment.window?.let(references::token),
            ),
        )
    private val border =
        attachment.border.record.let { snapshot ->
            StudioGlowFocusReceipts.encodeInitial(
                componentIdentity,
                snapshot,
                GlowBorderTokens(snapshot.before?.let(references::token), snapshot.planned?.let(references::token)),
            )
        }

    val receipts: StudioList<PortableReceipt>
        get() =
            StudioList(
                listOfNotNull(
                    StudioGlowMembershipReceipts.refreshAttachment(membership, attachment.phase),
                    border?.let { original ->
                        StudioGlowFocusReceipts.refreshInitial(original, attachment.border.record, attachment.phase)
                    },
                ),
            )

    fun apply() {
        attachment.apply()
    }

    fun restore(blockedBy: List<PortableReceipt>) {
        val resources = setOf(listenerIdentity.resource, componentIdentity.resource)
        attachment.restore(StudioGlowMembershipReceipts.isBlocked(resources, blockedBy))
    }

    fun accept() {
        attachment.accept()
    }
}
