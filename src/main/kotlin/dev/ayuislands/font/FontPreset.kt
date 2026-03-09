package dev.ayuislands.font

/** Curated Nerd Font presets with recommended editor settings. */
enum class FontPreset(
    val displayName: String,
    val fontFamily: String,
    val fontAliases: List<String>,
    val fontSize: Float,
    val lineSpacing: Float,
    val enableLigatures: Boolean,
    val brewCask: String,
    val downloadUrl: String,
) {
    GLOW_WRITER(
        displayName = "Glow Writer",
        fontFamily = "VictorMono Nerd Font",
        fontAliases = listOf("VictorMono Nerd Font", "VictorMono NF", "Victor Mono Nerd Font"),
        fontSize = 14f,
        lineSpacing = 1.4f,
        enableLigatures = true,
        brewCask = "font-victor-mono-nerd-font",
        downloadUrl = "https://www.nerdfonts.com/font-downloads",
    ),
    CLEAN(
        displayName = "Clean",
        fontFamily = "JetBrainsMono Nerd Font",
        fontAliases = listOf("JetBrainsMono Nerd Font", "JetBrainsMono NF"),
        fontSize = 13f,
        lineSpacing = 1.2f,
        enableLigatures = true,
        brewCask = "font-jetbrains-mono-nerd-font",
        downloadUrl = "https://www.nerdfonts.com/font-downloads",
    ),
    MODERN(
        displayName = "Modern",
        fontFamily = "Maple Mono NF",
        fontAliases = listOf("Maple Mono NF", "MapleMono Nerd Font", "Maple Mono"),
        fontSize = 13f,
        lineSpacing = 1.3f,
        enableLigatures = true,
        brewCask = "font-maple-mono-nerd-font",
        downloadUrl = "https://www.nerdfonts.com/font-downloads",
    ),
    COMPACT(
        displayName = "Compact",
        fontFamily = "Iosevka Nerd Font",
        fontAliases = listOf("Iosevka Nerd Font", "Iosevka NF", "Iosevka Nerd Font Mono"),
        fontSize = 13f,
        lineSpacing = 1.1f,
        enableLigatures = true,
        brewCask = "font-iosevka-nerd-font",
        downloadUrl = "https://www.nerdfonts.com/font-downloads",
    ),
    MINIMAL(
        displayName = "Minimal",
        fontFamily = "GeistMono Nerd Font",
        fontAliases = listOf("GeistMono Nerd Font", "GeistMono NF", "GeistMono Nerd Font Mono"),
        fontSize = 13f,
        lineSpacing = 1.2f,
        enableLigatures = false,
        brewCask = "font-geist-mono-nerd-font",
        downloadUrl = "https://www.nerdfonts.com/font-downloads",
    ),
    TEXTURE(
        displayName = "Texture",
        fontFamily = "Monaspace Neon",
        fontAliases = listOf("Monaspace Neon", "Monaspace Neon Nerd Font", "MonaspaceNeon NF"),
        fontSize = 13f,
        lineSpacing = 1.3f,
        enableLigatures = true,
        brewCask = "font-monaspace-nerd-font",
        downloadUrl = "https://www.nerdfonts.com/font-downloads",
    ),
    ;

    companion object {
        fun fromName(name: String?): FontPreset = entries.firstOrNull { it.name == name } ?: CLEAN
    }
}
