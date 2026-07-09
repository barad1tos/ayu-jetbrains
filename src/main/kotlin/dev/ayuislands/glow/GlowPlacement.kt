package dev.ayuislands.glow

/**
 * Where a glow overlay renders: the full island frame or a partial strip
 * clipped out of it. Editor surfaces support [ISLAND] and [TAB_BAR]; tool
 * windows support [ISLAND] and [SIDE_EDGES] — the per-surface companion
 * factories normalize persisted values that name the wrong surface's
 * placement (hand-edited XML) back to [ISLAND].
 */
enum class GlowPlacement(
    val displayName: String,
) {
    ISLAND("Island"),
    TAB_BAR("Under tabs"),
    SIDE_EDGES("Side edges"),
    ;

    companion object {
        fun fromName(name: String?): GlowPlacement = entries.firstOrNull { it.name == name } ?: ISLAND

        /** Editor placement: [SIDE_EDGES] has no editor meaning and normalizes to [ISLAND]. */
        fun forEditor(name: String?): GlowPlacement = fromName(name).takeIf { it != SIDE_EDGES } ?: ISLAND

        /** Tool-window placement: [TAB_BAR] has no tool-window meaning and normalizes to [ISLAND]. */
        fun forToolWindow(name: String?): GlowPlacement = fromName(name).takeIf { it != TAB_BAR } ?: ISLAND
    }
}
