package dev.ayuislands.font

private const val NERD_FONTS_GITHUB = "https://github.com/ryanoasis/nerd-fonts/releases/latest/download"
private const val DEFAULT_CUSTOM_FONT = "JetBrains Mono"

/** Download and install metadata for a curated font preset. */
data class InstallInfo(
    val downloadUrl: String,
    val brewCask: String,
)

/** Curated Nerd Font presets aligned with the Ayu Islands aesthetic system. */
enum class FontPreset(
    val displayName: String,
    val fontFamily: String,
    val fontAliases: List<String>,
    val fontSize: Float,
    val lineSpacing: Float,
    val enableLigatures: Boolean,
    val defaultWeight: FontWeight,
    val installInfo: InstallInfo?,
) {
    WHISPER(
        displayName = "Whisper",
        fontFamily = "VictorMono Nerd Font",
        fontAliases = listOf("VictorMono Nerd Font", "VictorMono NF", "Victor Mono Nerd Font"),
        fontSize = 14f,
        lineSpacing = 1.4f,
        enableLigatures = true,
        defaultWeight = FontWeight.LIGHT,
        installInfo = InstallInfo("$NERD_FONTS_GITHUB/VictorMono.zip", "font-victor-mono-nerd-font"),
    ),
    AMBIENT(
        displayName = "Ambient",
        fontFamily = "Maple Mono NF",
        fontAliases = listOf("Maple Mono NF", "MapleMono Nerd Font", "Maple Mono"),
        fontSize = 13f,
        lineSpacing = 1.3f,
        enableLigatures = true,
        defaultWeight = FontWeight.REGULAR,
        installInfo = InstallInfo("$NERD_FONTS_GITHUB/MapleMono.zip", "font-maple-mono-nf"),
    ),
    NEON(
        displayName = "Neon",
        fontFamily = "MonaspiceNe Nerd Font",
        fontAliases = listOf("MonaspiceNe Nerd Font", "MonaspiceNe NF", "Monaspace Neon"),
        fontSize = 13f,
        lineSpacing = 1.2f,
        enableLigatures = true,
        defaultWeight = FontWeight.REGULAR,
        installInfo = InstallInfo("$NERD_FONTS_GITHUB/Monaspace.zip", "font-monaspice-nerd-font"),
    ),
    CYBERPUNK(
        displayName = "Cyberpunk",
        fontFamily = "MonaspiceXe Nerd Font",
        fontAliases = listOf("MonaspiceXe Nerd Font", "MonaspiceXe NF", "Monaspace Xenon"),
        fontSize = 13f,
        lineSpacing = 1.2f,
        enableLigatures = true,
        defaultWeight = FontWeight.MEDIUM,
        installInfo = InstallInfo("$NERD_FONTS_GITHUB/Monaspace.zip", "font-monaspice-nerd-font"),
    ),
    CUSTOM(
        displayName = "Custom",
        fontFamily = DEFAULT_CUSTOM_FONT,
        fontAliases = emptyList(),
        fontSize = 13f,
        lineSpacing = 1.2f,
        enableLigatures = true,
        defaultWeight = FontWeight.REGULAR,
        installInfo = null,
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
