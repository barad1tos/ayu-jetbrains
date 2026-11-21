package dev.ayuislands.studio.session

import dev.ayuislands.glow.GlowFocusAppearance
import dev.ayuislands.glow.GlowOverlayAddition
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.glow.GlowPaneAppearance
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.PortableReceipt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** A manager can participate in shared focus without an edit to its panes. */
internal data class StudioGlowTarget(
    val manager: GlowOverlayManager,
    val focusEdit: ((GlowFocusAppearance) -> GlowFocusAppearance)? = null,
    val additions: List<GlowOverlayAddition> = emptyList(),
    val paneEdit: ((isEditor: Boolean, current: GlowPaneAppearance) -> StudioGlowPaneOperation.Edit)? = null,
)

/** One journal owner for retained Glow surfaces. The Apply lease alone releases the manager holds. */
internal class StudioGlowOperation private constructor(
    private val surfaces: List<StudioOwnerOperation>,
) : StudioOwnerOperation {
    override val receipts: StudioList<PortableReceipt>
        get() = StudioList(surfaces.flatMap { it.receipts })

    override suspend fun apply(writes: StudioNativeWrite): Boolean = surfaces.all { it.apply(writes) }

    override suspend fun prepareRestore(blocked: List<PortableReceipt>) {
        surfaces.asReversed().forEach { it.prepareRestore(blocked) }
    }

    override suspend fun restore(blocked: List<PortableReceipt>): RestoreContinuation {
        for (surface in surfaces.asReversed()) {
            if (surface.restore(blocked) == RestoreContinuation.REQUIRED) return RestoreContinuation.REQUIRED
        }
        return RestoreContinuation.COMPLETE
    }

    override suspend fun accept() {
        surfaces.forEach { it.accept() }
    }

    companion object {
        suspend fun capture(
            targets: List<StudioGlowTarget>,
            holds: StudioGlowHolds,
            process: StudioProcess,
            transactionId: String,
            nativeDispatcher: CoroutineDispatcher,
        ): StudioGlowOperation? =
            withContext(nativeDispatcher) {
                val panes = StudioGlowPaneOperation.capture(targets, holds, process, transactionId, nativeDispatcher)
                val focus =
                    if (targets.any { it.focusEdit != null }) {
                        val participants = targets.associateBy { holds.hold(it.manager).focusCallbacks }
                        StudioGlowFocusOperation.capture(
                            participants.keys.toList(),
                            process,
                            transactionId,
                            nativeDispatcher,
                        ) { hold, actual ->
                            participants.getValue(hold).focusEdit?.invoke(actual) ?: actual
                        }
                    } else {
                        null
                    }
                val mounts = StudioGlowMounts.capture(targets, holds, process, transactionId, nativeDispatcher)
                val surfaces = listOfNotNull(panes, focus, mounts)
                surfaces.takeIf { it.isNotEmpty() }?.let(::StudioGlowOperation)
            }
    }
}
