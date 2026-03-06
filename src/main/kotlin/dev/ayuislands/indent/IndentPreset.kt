package dev.ayuislands.indent

private const val WHISPER_ALPHA = 0x1A
private const val AMBIENT_ALPHA = 0x2E
private const val NEON_ALPHA = 0x4D
private const val CYBERPUNK_ALPHA = 0x73

enum class IndentPreset(
    val displayName: String,
    val alpha: Int?,
) {
    WHISPER("Whisper", WHISPER_ALPHA),
    AMBIENT("Ambient", AMBIENT_ALPHA),
    NEON("Neon", NEON_ALPHA),
    CYBERPUNK("Cyberpunk", CYBERPUNK_ALPHA),
    CUSTOM("Custom", null),
    ;

    companion object {
        fun fromName(name: String): IndentPreset = entries.firstOrNull { it.name == name } ?: CUSTOM

        fun detect(alpha: Int): IndentPreset =
            entries.firstOrNull { preset ->
                preset != CUSTOM && preset.alpha == alpha
            } ?: CUSTOM
    }
}
