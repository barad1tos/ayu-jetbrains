package dev.ayuislands.font

private const val DEFAULT_CUSTOM_FONT = "JetBrains Mono"

/**
 * Curated font presets aligned with the Ayu Islands aesthetic system.
 *
 * Installable presets (Whisper/Ambient/Neon/Cyberpunk) have no hard-coded download
 * metadata here — runtime install URLs live in [FontCatalog] and are resolved by
 * [FontInstaller]. Keep this enum focused on pure preset identity + typography.
 */
enum class FontPreset(
    val displayName: String,
    val fontFamily: String,
    val fontAliases: List<String>,
    val fontSize: Float,
    val lineSpacing: Float,
    val enableLigatures: Boolean,
    val defaultWeight: FontWeight,
) {
    WHISPER(
        displayName = "Whisper",
        fontFamily = "Victor Mono",
        fontAliases = listOf("Victor Mono", "VictorMono Nerd Font", "VictorMono NF"),
        fontSize = 14f,
        lineSpacing = 1.4f,
        enableLigatures = true,
        defaultWeight = FontWeight.LIGHT,
    ),
    AMBIENT(
        displayName = "Ambient",
        fontFamily = "Maple Mono",
        fontAliases = listOf("Maple Mono", "Maple Mono NF", "MapleMono Nerd Font"),
        fontSize = 13f,
        lineSpacing = 1.3f,
        enableLigatures = true,
        defaultWeight = FontWeight.REGULAR,
    ),
    NEON(
        displayName = "Neon",
        fontFamily = "Monaspace Neon",
        fontAliases = listOf("Monaspace Neon", "MonaspiceNe Nerd Font", "MonaspiceNe NF"),
        fontSize = 13f,
        lineSpacing = 1.2f,
        enableLigatures = true,
        defaultWeight = FontWeight.REGULAR,
    ),
    CYBERPUNK(
        displayName = "Cyberpunk",
        fontFamily = "Monaspace Xenon",
        fontAliases = listOf("Monaspace Xenon", "MonaspiceXe Nerd Font", "MonaspiceXe NF"),
        fontSize = 13f,
        lineSpacing = 1.2f,
        enableLigatures = true,
        defaultWeight = FontWeight.MEDIUM,
    ),
    CUSTOM(
        displayName = "Custom",
        fontFamily = DEFAULT_CUSTOM_FONT,
        fontAliases = emptyList(),
        fontSize = 13f,
        lineSpacing = 1.2f,
        enableLigatures = true,
        defaultWeight = FontWeight.REGULAR,
    ),
    ;

    val isCurated: Boolean get() = this != CUSTOM

    companion object {
        private val LEGACY_NAMES =
            mapOf(
                "GLOW_WRITER" to "WHISPER",
                "CLEAN" to "AMBIENT",
                "MODERN" to "AMBIENT",
                "COMPACT" to "NEON",
            )

        fun fromName(name: String?): FontPreset {
            val migrated = LEGACY_NAMES[name] ?: name
            return entries.firstOrNull { it.name == migrated } ?: AMBIENT
        }

        fun migrateCustomizations(map: MutableMap<String, String>) {
            for ((oldKey, newKey) in LEGACY_NAMES) {
                val value = map.remove(oldKey) ?: continue
                if (!map.containsKey(newKey)) map[newKey] = value
            }
        }
    }
}
