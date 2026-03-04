package dev.ayuislands.glow

private const val WHISPER_INTENSITY = 35
private const val WHISPER_WIDTH = 8
private const val AMBIENT_INTENSITY = 45
private const val AMBIENT_WIDTH = 10
private const val NEON_INTENSITY = 65
private const val NEON_WIDTH = 8
private const val CYBERPUNK_INTENSITY = 85
private const val CYBERPUNK_WIDTH = 10

enum class GlowPreset(
    val displayName: String,
    val style: GlowStyle?,
    val intensity: Int?,
    val width: Int?,
    val animation: GlowAnimation?,
) {
    WHISPER("Whisper", GlowStyle.SOFT, WHISPER_INTENSITY, WHISPER_WIDTH, GlowAnimation.NONE),
    AMBIENT("Ambient", GlowStyle.GRADIENT, AMBIENT_INTENSITY, AMBIENT_WIDTH, GlowAnimation.BREATHE),
    NEON("Neon", GlowStyle.SHARP_NEON, NEON_INTENSITY, NEON_WIDTH, GlowAnimation.NONE),
    CYBERPUNK("Cyberpunk", GlowStyle.SHARP_NEON, CYBERPUNK_INTENSITY, CYBERPUNK_WIDTH, GlowAnimation.PULSE),
    CUSTOM("Custom", null, null, null, null),
    ;

    companion object {
        fun fromName(name: String): GlowPreset = entries.firstOrNull { it.name == name } ?: CUSTOM

        fun detect(
            style: GlowStyle,
            intensity: Int,
            width: Int,
            animation: GlowAnimation,
        ): GlowPreset =
            entries.firstOrNull { preset ->
                preset != CUSTOM &&
                    preset.style == style &&
                    preset.intensity == intensity &&
                    preset.width == width &&
                    preset.animation == animation
            } ?: CUSTOM
    }
}
