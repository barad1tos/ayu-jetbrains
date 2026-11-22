package dev.ayuislands.studio.session

import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioProcess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Binds Glow writes to the Apply journal and releases holds using its durable outcome. */
internal class StudioGlowApplyLease(
    private val delegate: StudioApplyLease,
    private val plan: AcceptancePlan.Commit,
    private val process: StudioProcess,
    private val nativeDispatcher: CoroutineDispatcher,
    private val observeTargets: () -> List<Target>,
    private val refresh: (GlowOverlayManager, StudioDocument?) -> Unit,
) : StudioApplyLease by delegate {
    /** The host supplies the exact managers and already confirmed native Accent and availability. */
    data class Target(
        val manager: GlowOverlayManager,
        val accents: StudioGlowPlanning.Accents,
        val available: Boolean,
        val scope: Scope,
    )

    enum class Scope { PANES_AND_FOCUS, FOCUS_ONLY }

    private val holds = StudioGlowHolds()

    override suspend fun capture(owner: EffectOwner): StudioOwnerOperation? {
        if (owner != EffectOwner.GLOW) return delegate.capture(owner)
        val current = checkNotNull(StudioTargets.current(plan.document, plan.target))
        val settings = StudioGlowProjection.settings(current.appearance)
        val selection = plan.route.glow
        return withContext(nativeDispatcher) {
            val targets =
                observeTargets().map { target ->
                    when (target.scope) {
                        Scope.PANES_AND_FOCUS ->
                            StudioGlowPlanning.target(
                                target.manager,
                                selection,
                                settings,
                                target.accents,
                                target.available,
                            )

                        Scope.FOCUS_ONLY ->
                            StudioGlowTarget(
                                target.manager,
                                focusEdit =
                                    StudioGlowPlanning.focusEdit(
                                        selection,
                                        settings,
                                        target.accents.focus,
                                        target.available,
                                    ),
                            )
                    }
                }
            StudioGlowOperation.capture(
                targets,
                holds,
                process,
                plan.document.header.transactionId,
                nativeDispatcher,
            )
        }
    }

    override suspend fun release(accepted: StudioDocument?) {
        withContext(nativeDispatcher) { holds.release { refresh(it, accepted) } }
        delegate.release(accepted)
    }
}
