package dev.ayuislands.studio.session

import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentFallback
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ResolvedAccent
import dev.ayuislands.rotation.RuntimeOwner
import dev.ayuislands.studio.model.StudioTarget

/** Runtime outputs remain owner-qualified and never replace the stored accent preference. */
internal object StudioEffectiveAccent {
    fun held(
        input: StudioEffectInput,
        variant: AyuVariant?,
    ): ResolvedAccent? {
        if (input.target != StudioTarget.Global || input.rotationStep != null) return null
        return input.candidate.runtime.effectiveContinuation
            ?.accent
            ?.takeIf { it.variant == variant }
            ?.resolved
    }

    fun fallback(
        input: StudioEffectInput,
        context: AccentContext,
        systemAccent: String?,
    ): AccentFallback {
        val current = requireNotNull(StudioTargets.current(input.candidate, input.target))
        val appearance = current.appearance
        val shared = input.candidate.shared
        val ordinary = StudioAccentProjection.fallback(appearance, shared, context, systemAccent)
        if (context !is AccentContext.Ayu || StudioAccentPolicy.followsSystem(appearance, shared.systemAccentPolicy)) {
            return ordinary
        }
        val owner = RuntimeOwner(input.target, current.revision)
        val prepared = input.rotationStep?.takeIf { it.owner == owner && it.variant == context.ayuVariant }
        val accepted =
            input.candidate.runtime.rotation
                ?.output
                ?.takeIf { it.owner == owner && it.variant == context.ayuVariant }
        return (prepared?.hex ?: accepted?.hex)?.let(AccentFallback::Global) ?: ordinary
    }
}
