package dev.ayuislands.studio.session

import com.intellij.openapi.application.EDT
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.theme.AyuEditorSchemeBinder
import dev.ayuislands.theme.EditorSchemeOverrides
import dev.ayuislands.theme.EditorSchemeOwner
import dev.ayuislands.theme.SchemeSource
import dev.ayuislands.vcs.VcsColorApplier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Uses explicit variant and entitlement facts while preserving every requested VCS preference. */
internal class StudioVcsParticipant(
    private val host: StudioSchemeHost = IdeFontHost(),
    private val nativeContext: CoroutineContext = Dispatchers.EDT,
) : StudioEffectParticipant {
    override val owner: EffectOwner = EffectOwner.VCS

    override suspend fun prepare(
        input: StudioEffectInput,
        runtime: StudioEffectRuntime,
    ): StudioEffectOperation =
        withContext(nativeContext) {
            val current = checkNotNull(StudioTargets.current(input.candidate, input.target))
            val snapshot = StudioVcsProjection.snapshot(current.appearance)
            val facts = runtime.native
            val operation = StudioSchemeOperation.prepare(host, nativeContext, owner, input, facts)
            val observed = host.observe()
            val variant = observed.variant
            val active = observed.active
            val eligible = variant != null && AyuEditorSchemeBinder.matchesVariant(active.name, variant)
            for (scheme in observed.schemes) {
                if (scheme === active && eligible) {
                    val requested = VcsColorApplier.prepare(scheme, snapshot, variant, facts.isLicensed)
                    operation.capture(scheme, requested, requested.keys, SchemeSource.Active)
                } else if (EditorSchemeOverrides.hasState(scheme, EditorSchemeOwner.Vcs)) {
                    operation.capture(scheme, emptyMap(), emptySet())
                }
            }
            operation
        }

    override suspend fun recover(
        input: StudioEffectInput,
        receipts: StudioList<PortableReceipt>,
        runtime: StudioEffectRuntime,
    ): StudioEffectOperation =
        withContext(nativeContext) {
            StudioSchemeOperation(
                host,
                nativeContext,
                owner,
                input,
                receipts,
                runtime.native,
            )
        }
}
