package dev.ayuislands.studio.session

import dev.ayuislands.glow.GlowBorderRestore
import dev.ayuislands.glow.GlowFocusListener
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.PortableReceipt

/** Native focus ownership retains fresh border preparations until the outer journal acknowledges them. */
internal class StudioGlowFocusWrite(
    private val references: StudioGlowReferences,
    private val checkpoint: GlowFocusListener.Checkpoint,
) {
    private val listenerIdentity = references.identity(checkpoint.listener)
    private val componentIdentity = references.identity(checkpoint.listener.component)
    private val history =
        checkpoint.record.let { snapshot ->
            StudioGlowFocusReceipts
                .encode(
                    listenerIdentity,
                    componentIdentity.resource,
                    snapshot,
                    GlowFocusTokens(
                        references.token(snapshot.before.color),
                        references.token(snapshot.planned.color),
                        snapshot.beforeBorder?.let(references::token),
                        snapshot.plannedBorder?.let(references::token),
                        snapshot.baselineBorder?.let(references::token),
                    ),
                ).toMutableList()
        }
    private var lastRestore: GlowBorderRestore? = null
    private var restoreSequence = 0

    val receipts: StudioList<PortableReceipt>
        get() = StudioGlowFocusReceipts.refresh(history, checkpoint.record)

    fun apply() {
        checkpoint.apply()
    }

    fun prepareRestore() {
        checkpoint.prepareRestore()
        val prepared = checkpoint.record.restoration
        if (prepared == null || prepared === lastRestore) return
        history.add(
            StudioGlowFocusReceipts.encodeRestore(
                componentIdentity,
                restoreSequence,
                prepared,
                GlowBorderTokens(
                    prepared.observed?.let(references::token),
                    prepared.border?.let(references::token),
                ),
            ),
        )
        lastRestore = prepared
        restoreSequence++
    }

    fun restore(blockedBy: List<PortableReceipt>) {
        checkpoint.restore(
            StudioGlowFocusReceipts.blockedFields(listenerIdentity.resource, componentIdentity.resource, blockedBy),
        )
    }

    fun accept() {
        checkpoint.accept()
    }
}
