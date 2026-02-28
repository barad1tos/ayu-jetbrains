package dev.ayuislands.glow

enum class GlowStyle(
    val displayName: String,
    val defaultIntensity: Int,
    val defaultWidth: Int,
) {
    SOFT("Soft", 40, 10),
    SHARP_NEON("Sharp Neon", 85, 20),
    GRADIENT("Gradient", 50, 12),
    ;

    companion object {
        fun fromName(name: String): GlowStyle =
            entries.firstOrNull { it.name == name } ?: SOFT
    }
}
