package dev.ayuislands.glow

/**
 * Where a glow overlay renders: the full island frame or a partial strip
 * clipped out of it. Editors support all three placements; tool windows
 * support [ISLAND] and [SIDE_EDGES] — the tool-window companion factory
 * normalizes persisted values that name the wrong surface's placement
 * (hand-edited XML) back to [ISLAND].
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

        /** Editor placement: every variant applies (side strips flank the editor island). */
        fun forEditor(name: String?): GlowPlacement = fromName(name)

        /** Tool-window placement: [TAB_BAR] has no tool-window meaning and normalizes to [ISLAND]. */
        fun forToolWindow(name: String?): GlowPlacement = fromName(name).takeIf { it != TAB_BAR } ?: ISLAND
    }
}
