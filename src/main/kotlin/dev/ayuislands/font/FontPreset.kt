package dev.ayuislands.font

/** Curated Nerd Font presets with recommended editor settings. */
enum class FontPreset(
    val displayName: String,
    val fontFamily: String,
    val fontAliases: List<String>,
    val fontSize: Float,
    val lineSpacing: Float,
    val enableLigatures: Boolean,
    val downloadUrl: String = NERD_FONTS_DOWNLOAD_URL,
) {
    GLOW_WRITER(
        displayName = "Glow Writer",
        fontFamily = "VictorMono Nerd Font",
        fontAliases = listOf("VictorMono Nerd Font", "VictorMono NF", "Victor Mono Nerd Font"),
        fontSize = 14f,
        lineSpacing = 1.4f,
        enableLigatures = true,
    ),
    CLEAN(
        displayName = "Clean",
        fontFamily = "JetBrainsMono Nerd Font",
        fontAliases = listOf("JetBrainsMono Nerd Font", "JetBrainsMono NF"),
        fontSize = 13f,
        lineSpacing = 1.2f,
        enableLigatures = true,
    ),
    MODERN(
        displayName = "Modern",
        fontFamily = "Maple Mono NF",
        fontAliases = listOf("Maple Mono NF", "MapleMono Nerd Font", "Maple Mono"),
        fontSize = 13f,
        lineSpacing = 1.3f,
        enableLigatures = true,
    ),
    COMPACT(
        displayName = "Compact",
        fontFamily = "Iosevka Nerd Font",
        fontAliases = listOf("Iosevka Nerd Font", "Iosevka NF", "Iosevka Nerd Font Mono"),
        fontSize = 13f,
        lineSpacing = 1.1f,
        enableLigatures = true,
    ),
    MINIMAL(
        displayName = "Minimal",
        fontFamily = "GeistMono Nerd Font",
        fontAliases = listOf("GeistMono Nerd Font", "GeistMono NF", "GeistMono Nerd Font Mono"),
        fontSize = 13f,
        lineSpacing = 1.2f,
        enableLigatures = false,
    ),
    TEXTURE(
        displayName = "Texture",
        fontFamily = "Monaspace Neon",
        fontAliases = listOf("Monaspace Neon", "Monaspace Neon Nerd Font", "MonaspaceNeon NF"),
        fontSize = 13f,
        lineSpacing = 1.3f,
        enableLigatures = true,
    ),
    ;

    companion object {
        const val NERD_FONTS_DOWNLOAD_URL = "https://www.nerdfonts.com/font-downloads"

        fun fromName(name: String?): FontPreset = entries.firstOrNull { it.name == name } ?: CLEAN
    }
}
