package dev.ayuislands.accent

import dev.ayuislands.glow.GlowTabMode

/** Runtime interpretation never changes the stored height or any per-style Glow width. */
internal fun resolveUnderlineHeight(
    tabMode: GlowTabMode,
    storedHeight: Int,
    synchronizeWithGlow: Boolean,
    glowEnabled: Boolean,
    glowWidth: Int,
): Int = if (tabMode != GlowTabMode.OFF && synchronizeWithGlow && glowEnabled) glowWidth else storedHeight

internal fun resolveUnderlineArc(observed: Int): Int = if (observed > 0) observed else DEFAULT_UNDERLINE_ARC

private const val DEFAULT_UNDERLINE_ARC = 8
