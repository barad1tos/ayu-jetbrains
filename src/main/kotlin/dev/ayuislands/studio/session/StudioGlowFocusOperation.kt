package dev.ayuislands.studio.session

import dev.ayuislands.glow.FocusRingManager
import dev.ayuislands.glow.GlowFieldState
import dev.ayuislands.glow.GlowFocusAppearance
import dev.ayuislands.glow.GlowFocusField
import dev.ayuislands.glow.GlowFocusListener
import dev.ayuislands.glow.captureGlowFocus
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.StudioReceiptPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Retains same-process focus checkpoints; the outer Apply lease owns and releases all callback holds. */
internal class StudioGlowFocusOperation private constructor(
    private val checkpoints: List<FocusWrite>,
    private val nativeDispatcher: CoroutineDispatcher,
) : StudioOwnerOperation {
    private class FocusWrite(
        val checkpoint: GlowFocusListener.Checkpoint,
        val originals: Map<GlowFocusField, PortableReceipt>,
        private val evidence: StudioGlowFocusReceipts,
    ) {
        private var restorations = StudioList<PortableReceipt>(emptyList())

        fun prepareRestore(blocked: Set<GlowFocusField>) {
            if (GlowFocusField.BORDER in blocked) return
            checkpoint.prepareRestore()
            val prepared = checkpoint.record.restoration ?: return
            if (prepared.outcome != GlowFieldState.WRITING) return
            val original = originals.getValue(GlowFocusField.BORDER)
            val receipt = evidence.restoration(original, restorations.size, prepared)
            val previous = restorations.lastOrNull()
            if (previous == null || previous.before != receipt.before || previous.lastWritten != receipt.lastWritten) {
                restorations = StudioList(restorations + receipt)
            }
        }

        fun observe(): List<PortableReceipt> {
            val record = checkpoint.record
            val current =
                originals.map { (field, receipt) ->
                    receipt.copy(status = glowReceiptStatus(record.phase, record.fields.getValue(field)))
                }
            if (restorations.isEmpty()) return current
            val status = glowReceiptStatus(record.phase, record.fields.getValue(GlowFocusField.BORDER))
            return if (StudioReceiptPolicy.isTerminal(status)) {
                current + restorations.map { it.copy(status = status) }
            } else {
                current + restorations
            }
        }
    }

    override var receipts: StudioList<PortableReceipt> = StudioList(checkpoints.flatMap { it.originals.values })
        private set

    override suspend fun apply(writes: StudioNativeWrite): Boolean =
        withContext(nativeDispatcher) {
            try {
                for (focus in checkpoints) {
                    if (!writes.execute(nativeDispatcher, focus.checkpoint::apply)) return@withContext false
                }
                true
            } finally {
                observeReceipts()
            }
        }

    override suspend fun prepareRestore(blocked: List<PortableReceipt>) =
        onNativeReverse { focus -> focus.prepareRestore(blockedGlowFields(focus.originals, blocked)) }

    override suspend fun restore(blocked: List<PortableReceipt>): RestoreContinuation {
        onNativeReverse { focus -> focus.checkpoint.restore(blockedGlowFields(focus.originals, blocked)) }
        return RestoreContinuation.COMPLETE
    }

    override suspend fun accept() =
        withContext(nativeDispatcher) {
            try {
                settleGlowActions(checkpoints) { it.checkpoint.accept() }
            } finally {
                observeReceipts()
            }
        }

    private suspend fun onNativeReverse(action: (FocusWrite) -> Unit) =
        withContext(nativeDispatcher) {
            try {
                settleGlowActions(checkpoints.asReversed(), action)
            } finally {
                observeReceipts()
            }
        }

    private fun observeReceipts() {
        receipts = StudioList(checkpoints.flatMap(FocusWrite::observe))
    }

    companion object {
        /** The passed holds stay with the lease even if capture or the first journal write fails. */
        suspend fun capture(
            holds: List<FocusRingManager.CallbackHold>,
            process: StudioProcess,
            transactionId: String,
            nativeDispatcher: CoroutineDispatcher,
            transform: (FocusRingManager.CallbackHold, GlowFocusAppearance) -> GlowFocusAppearance,
        ): StudioGlowFocusOperation? =
            withContext(nativeDispatcher) {
                val captured = captureGlowFocus(holds, transform)
                if (captured.isEmpty()) return@withContext null
                val ownerToken = "glow-focus/$transactionId"
                val tokens = StudioNativeTokens(ownerToken)
                val evidence = StudioGlowFocusReceipts(ownerToken, process, tokens)
                val writes =
                    captured.map { checkpoint ->
                        val listener = checkpoint.listener
                        val component = tokens.token(listener.component)
                        val records = evidence.capture(tokens.token(listener), component, checkpoint.record)
                        FocusWrite(checkpoint, records, evidence)
                    }
                StudioGlowFocusOperation(writes, nativeDispatcher)
            }
    }
}
