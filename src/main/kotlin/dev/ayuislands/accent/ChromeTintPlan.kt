package dev.ayuislands.accent

import java.awt.Color

/** Ordered final values, separate from platform observations, ownership and publication. */
internal data class ChromeTintPlan(
    val backgrounds: Map<String, Color> = emptyMap(),
    val overlays: List<String> = emptyList(),
    val foregrounds: Map<String, Color> = emptyMap(),
    val peer: ChromePeerTint? = null,
)

internal data class ChromePeerTint(
    val target: ChromeTarget,
    val color: Color,
)

/** Revert retains the legacy background/cache, foreground, peer, then overlay order. */
internal data class ChromeClearPlan(
    val backgrounds: List<String>,
    val foregrounds: List<String>,
    val overlays: List<String>,
    val peer: ChromeTarget?,
)
