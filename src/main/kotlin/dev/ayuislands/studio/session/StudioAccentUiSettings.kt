package dev.ayuislands.studio.session

import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentUiPolicy
import dev.ayuislands.glow.GlowTabMode

internal data class StudioAccentUiSettings(
    val context: AccentContext?,
    val tabMode: GlowTabMode,
    val policy: AccentUiPolicy,
    val geometry: StudioTabGeometry,
)

/** A missing context leaves geometry alone; releasing ownership and writing a height are different operations. */
internal sealed interface StudioTabGeometry {
    data object Unchanged : StudioTabGeometry

    data object Release : StudioTabGeometry

    data class Set(
        val height: Int,
    ) : StudioTabGeometry
}
