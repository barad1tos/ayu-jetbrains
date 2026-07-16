package dev.ayuislands.glow

/**
 * Where a glow overlay renders: the full island frame or just its left and
 * right edges, clipped out of the same cached frame. Values persist by name;
 * [fromName] migrates the retired `TAB_BAR` to its successor [SIDE_EDGES]
 * (both mean "partial glow, not the full frame") and falls back to [ISLAND]
 * for unknown names.
 */
enum class GlowPlacement(
    val displayName: String,
) {
    ISLAND("Island"),
    SIDE_EDGES("Side edges"),
    ;

    companion object {
        private const val RETIRED_TAB_BAR = "TAB_BAR"

        fun fromName(name: String?): GlowPlacement =
            when (name) {
                RETIRED_TAB_BAR -> SIDE_EDGES
                else -> entries.firstOrNull { it.name == name } ?: ISLAND
            }
    }
}
