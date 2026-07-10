package dev.ayuislands.glow

/**
 * Where a glow overlay renders: the full island frame or just its left and
 * right edges, clipped out of the same cached frame. Values persist by name;
 * [fromName] normalizes unknown or retired names (for example the removed
 * `TAB_BAR`) back to [ISLAND].
 */
enum class GlowPlacement(
    val displayName: String,
) {
    ISLAND("Island"),
    SIDE_EDGES("Side edges"),
    ;

    companion object {
        fun fromName(name: String?): GlowPlacement = entries.firstOrNull { it.name == name } ?: ISLAND
    }
}
