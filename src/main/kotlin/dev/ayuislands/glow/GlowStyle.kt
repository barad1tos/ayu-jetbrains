package dev.ayuislands.glow

private const val DEFAULT_SOFT_INTENSITY = 35
private const val DEFAULT_SOFT_WIDTH = 8
private const val DEFAULT_NEON_INTENSITY = 65
private const val DEFAULT_NEON_WIDTH = 8
private const val DEFAULT_GRADIENT_INTENSITY = 45
private const val DEFAULT_GRADIENT_WIDTH = 10

enum class GlowStyle(
    val displayName: String,
    val defaultIntensity: Int,
    val defaultWidth: Int,
) {
    SOFT("Soft", DEFAULT_SOFT_INTENSITY, DEFAULT_SOFT_WIDTH),
    SHARP_NEON("Sharp Neon", DEFAULT_NEON_INTENSITY, DEFAULT_NEON_WIDTH),
    GRADIENT("Gradient", DEFAULT_GRADIENT_INTENSITY, DEFAULT_GRADIENT_WIDTH),
    ;

    companion object {
        fun fromName(name: String): GlowStyle = entries.firstOrNull { it.name == name } ?: SOFT
    }
}
