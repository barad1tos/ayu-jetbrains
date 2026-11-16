package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.model.StudioProcessRelation
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.persistence.StudioReceiptPolicy.canRetain
import dev.ayuislands.studio.persistence.StudioReceiptPolicy.isTerminal
import dev.ayuislands.studio.session.StudioNativeEvidence.envelope
import dev.ayuislands.studio.session.StudioNativeEvidence.readProcess

/** Native fields have separate lifetimes from the user's persisted Workspace preferences. */
internal enum class WorkspaceNativeField {
    EDITOR_SIZE,
    EDITOR_BASELINE,
    PROJECT_SCROLLBAR_POLICY,
    PROJECT_SCROLLBAR_BASELINE,
    PROJECT_RENDERER,
    COMMIT_RENDERER,
    WIDTH_FIRST_SIZE,
    WIDTH_LAST_SIZE,
    WIDTH_BOUNDS,
    WIDTH_LISTENER_ATTACHMENT,
    WIDTH_LISTENER_ENABLED,
    GIT_PROPORTION,
    GIT_LISTENER,
    ROOT_PATH,
    GIT_PROPERTY,
}

/** Encodes captured evidence without resolving native objects or changing user preferences. */
internal object StudioWorkspaceReceipts {
    fun create(
        identity: NativeFieldIdentity,
        process: StudioProcess,
        beforeRaw: RawValue,
        plannedRaw: RawValue,
    ): PortableReceipt {
        require(identity.resource.isNotBlank() && identity.ownerToken.isNotBlank())
        WorkspaceNativeField.valueOf(identity.field)
        return PortableReceipt(
            "${identity.ownerToken}/${identity.resource}/${identity.field}",
            EffectOwner.WORKSPACE,
            identity,
            envelope(process, beforeRaw),
            envelope(process, plannedRaw),
            RestoreStatus.PENDING,
        )
    }

    /** Only proven process loss retires ephemeral effects or permits retaining ToolWindow geometry. */
    fun recoverLost(
        receipts: List<PortableReceipt>,
        currentProcess: StudioProcess?,
    ): StudioList<PortableReceipt> =
        StudioList(
            receipts.map { receipt ->
                require(receipt.owner == EffectOwner.WORKSPACE)
                require(
                    receipt.status != RestoreStatus.KEPT_AFTER_RESTART ||
                        canRetain(receipt.owner, receipt.identity.field),
                ) {
                    "Retained geometry receipt has an unsupported native field"
                }
                if (isTerminal(receipt)) {
                    receipt
                } else {
                    val origin = readProcess(receipt.before)
                    require(
                        origin == readProcess(receipt.lastWritten),
                    ) { "Workspace receipt process provenance differs" }
                    val status =
                        if (StudioProcessPolicy.compare(origin, currentProcess) == StudioProcessRelation.DIFFERENT) {
                            lostStatus(receipt)
                        } else {
                            RestoreStatus.UNAVAILABLE
                        }
                    receipt.copy(status = status)
                }
            },
        )

    fun isBlocked(
        resource: String,
        field: String,
        newer: List<PortableReceipt>,
    ): Boolean =
        newer.any {
            it.identity.resource == resource && it.identity.field == field && !isTerminal(it)
        }

    private fun lostStatus(receipt: PortableReceipt): RestoreStatus {
        if (canRetain(receipt.owner, receipt.identity.field)) return RestoreStatus.KEPT_AFTER_RESTART
        return when (WorkspaceNativeField.entries.singleOrNull { it.name == receipt.identity.field }) {
            WorkspaceNativeField.EDITOR_SIZE,
            WorkspaceNativeField.EDITOR_BASELINE,
            WorkspaceNativeField.PROJECT_SCROLLBAR_POLICY,
            WorkspaceNativeField.PROJECT_SCROLLBAR_BASELINE,
            WorkspaceNativeField.PROJECT_RENDERER,
            WorkspaceNativeField.COMMIT_RENDERER,
            WorkspaceNativeField.WIDTH_LISTENER_ATTACHMENT,
            WorkspaceNativeField.WIDTH_LISTENER_ENABLED,
            WorkspaceNativeField.GIT_PROPORTION,
            WorkspaceNativeField.GIT_LISTENER,
            -> RestoreStatus.RESTORED
            else -> RestoreStatus.UNAVAILABLE
        }
    }
}
