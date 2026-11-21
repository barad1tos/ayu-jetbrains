package dev.ayuislands.studio.session

import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.glow.GlowPaneAppearance
import dev.ayuislands.glow.GlowPaneCheckpoint
import dev.ayuislands.glow.GlowPaneField
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.PortableReceipt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Journals selected pane settings; the enclosing Glow owner manages membership, bounds and playback topology. */
internal class StudioGlowPaneOperation private constructor(
    private val panes: List<PaneWrite>,
    private val holds: StudioGlowHolds,
    private val nativeDispatcher: CoroutineDispatcher,
) : StudioOwnerOperation {
    private data class PaneWrite(
        val manager: GlowOverlayManager,
        val checkpoint: GlowPaneCheckpoint,
        val captured: Map<GlowPaneField, PortableReceipt>,
    )

    data class Edit(
        val appearance: GlowPaneAppearance,
        val fields: Set<GlowPaneField>,
    )

    override var receipts: StudioList<PortableReceipt> = StudioList(panes.flatMap { it.captured.values })
        private set

    override suspend fun apply(writes: StudioNativeWrite): Boolean =
        withContext(nativeDispatcher) {
            try {
                for ((manager, checkpoint) in panes) {
                    val applied =
                        writes.execute(nativeDispatcher) {
                            checkpoint.apply {
                                holds.hold(manager).pausePlayback()
                            }
                        }
                    if (!applied) return@withContext false
                }
                true
            } finally {
                observeReceipts()
            }
        }

    override suspend fun restore(blocked: List<PortableReceipt>): RestoreContinuation =
        withContext(nativeDispatcher) {
            try {
                settleGlowActions(panes.asReversed()) { pane ->
                    pane.checkpoint.restore(
                        blockedBy = blockedGlowFields(pane.captured, blocked),
                        targetRetired = pane.manager.isDisposed,
                    )
                }
                RestoreContinuation.COMPLETE
            } finally {
                observeReceipts()
            }
        }

    override suspend fun accept() =
        withContext(nativeDispatcher) {
            panes.forEach { it.checkpoint.accept() }
        }

    private fun observeReceipts() {
        receipts = StudioList(panes.flatMap { StudioGlowPaneReceipts.observe(it.captured, it.checkpoint.record) })
    }

    companion object {
        suspend fun capture(
            targets: List<StudioGlowTarget>,
            holds: StudioGlowHolds,
            process: StudioProcess,
            transactionId: String,
            nativeDispatcher: CoroutineDispatcher,
        ): StudioGlowPaneOperation? =
            withContext(nativeDispatcher) {
                require(targets.map { it.manager }.distinct().size == targets.size)
                val ownerToken = "glow/$transactionId"
                val tokens = StudioNativeTokens(ownerToken)
                val panes =
                    targets.flatMap { target ->
                        val editPane = target.paneEdit ?: return@flatMap emptyList()
                        target.manager.paneTargets.mapNotNull { mounted ->
                            val pane = mounted.pane
                            val edit = editPane(pane.isEditorOverlay, pane.captureAppearance())
                            val checkpoint = GlowPaneCheckpoint(pane, edit.appearance, edit.fields, mounted.isCurrent)
                            val record = checkpoint.record
                            if (record.fields.isEmpty()) return@mapNotNull null
                            val resource = tokens.token(pane)
                            val captured = StudioGlowPaneReceipts.capture(resource, ownerToken, process, record, tokens)
                            PaneWrite(target.manager, checkpoint, captured)
                        }
                    }
                panes.takeIf { it.isNotEmpty() }?.let { StudioGlowPaneOperation(it, holds, nativeDispatcher) }
            }
    }
}
