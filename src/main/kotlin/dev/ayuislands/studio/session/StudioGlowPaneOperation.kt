package dev.ayuislands.studio.session

import dev.ayuislands.accent.combineUiFailures
import dev.ayuislands.accent.runCatchingPreservingCancellation
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
    private val nativeDispatcher: CoroutineDispatcher,
) : StudioOwnerOperation {
    private data class PaneWrite(
        val request: Request,
        val checkpoint: GlowPaneCheckpoint,
        val captured: Map<GlowPaneField, PortableReceipt>,
    )

    data class Edit(
        val appearance: GlowPaneAppearance,
        val fields: Set<GlowPaneField>,
    )

    /** A pure edit plus a signal to reread the environment once the manager's hold ends. */
    class Request(
        val manager: GlowOverlayManager,
        val requestRefresh: () -> Unit,
        val edit: (isEditor: Boolean, current: GlowPaneAppearance) -> Edit,
    )

    private val holds = linkedMapOf<Request, GlowOverlayManager.UpdateHold>()
    private var panesRestored = false
    override var receipts: StudioList<PortableReceipt> = StudioList(panes.flatMap { it.captured.values })
        private set

    override suspend fun apply(writes: StudioNativeWrite): Boolean =
        withContext(nativeDispatcher) {
            try {
                for ((request, checkpoint) in panes) {
                    val applied =
                        writes.execute(nativeDispatcher) {
                            checkpoint.apply {
                                holds.getOrPut(request) { request.manager.holdUpdates() }.pausePlayback()
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
                if (!panesRestored) {
                    settle(panes.asReversed()) { pane ->
                        pane.checkpoint.restore(
                            blockedBy = StudioGlowPaneReceipts.blockedFields(pane.captured, blocked),
                            targetRetired = pane.request.manager.isDisposed,
                        )
                    }
                    panesRestored = true
                    // The journal acknowledges restored fields before it calls back to release playback.
                    if (holds.isNotEmpty()) return@withContext RestoreContinuation.REQUIRED
                }
                releaseHolds()
                RestoreContinuation.COMPLETE
            } finally {
                observeReceipts()
            }
        }

    override suspend fun accept() =
        withContext(nativeDispatcher) {
            panes.forEach { it.checkpoint.accept() }
            releaseHolds()
        }

    private fun releaseHolds() {
        settle(holds.keys.toList().asReversed()) { request ->
            if (holds.getValue(request).release()) request.requestRefresh()
            holds.remove(request)
        }
    }

    private fun observeReceipts() {
        receipts = StudioList(panes.flatMap { StudioGlowPaneReceipts.observe(it.captured, it.checkpoint.record) })
    }

    private fun <T> settle(
        values: List<T>,
        action: (T) -> Unit,
    ) {
        var failure: Throwable? = null
        for (value in values) {
            val problem = runCatchingPreservingCancellation { action(value) }.exceptionOrNull()
            if (problem != null) failure = combineUiFailures(failure, problem)
        }
        failure?.let { throw it }
    }

    companion object {
        suspend fun capture(
            requests: List<Request>,
            process: StudioProcess,
            transactionId: String,
            nativeDispatcher: CoroutineDispatcher,
        ): StudioGlowPaneOperation? =
            withContext(nativeDispatcher) {
                require(requests.map { it.manager }.distinct().size == requests.size)
                val ownerToken = "glow/$transactionId"
                val tokens = StudioNativeTokens(ownerToken)
                val panes =
                    requests.flatMap { request ->
                        request.manager.paneTargets.mapNotNull { target ->
                            val pane = target.pane
                            val edit = request.edit(pane.isEditorOverlay, pane.captureAppearance())
                            val checkpoint = GlowPaneCheckpoint(pane, edit.appearance, edit.fields, target.isCurrent)
                            val record = checkpoint.record
                            if (record.fields.isEmpty()) return@mapNotNull null
                            val resource = tokens.token(pane)
                            val captured = StudioGlowPaneReceipts.capture(resource, ownerToken, process, record, tokens)
                            PaneWrite(request, checkpoint, captured)
                        }
                    }
                panes.takeIf { it.isNotEmpty() }?.let { StudioGlowPaneOperation(it, nativeDispatcher) }
            }
    }
}
