package dev.ayuislands.studio.model

import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ResolvedAccent
import dev.ayuislands.rotation.RuntimeOwner

/** Effective values retained after the last project closes, separate from requested preferences. */
internal data class StudioEffectiveContinuation(
    val source: RuntimeOwner,
    val requested: AppearanceSnapshot,
    val accent: StudioAccentContinuation?,
)

internal data class StudioAccentContinuation(
    val variant: AyuVariant?,
    val resolved: ResolvedAccent,
    val dependencies: StudioList<StudioAcceptedInput>,
)

/** One accepted input that still owns the retained effective value. */
internal data class StudioAcceptedInput(
    val address: FieldAddress,
    val value: FieldValue,
)
