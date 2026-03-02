package dev.ayuislands.accent

import com.intellij.ide.ui.LafManager

enum class AyuVariant(
    val defaultAccent: String,
    val neutralGray: String,
    val parentSchemeName: String,
    val themeNames: Set<String>,
) {
    MIRAGE("#FFCC66", "#445066", "Darcula", setOf("Ayu Islands Mirage", "Ayu Islands Mirage (Islands UI)")),
    DARK("#E6B450", "#2C3342", "Darcula", setOf("Ayu Islands Dark", "Ayu Islands Dark (Islands UI)")),
    LIGHT("#F29718", "#CCC8B8", "Default", setOf("Ayu Islands Light", "Ayu Islands Light (Islands UI)"));

    companion object {
        fun fromThemeName(name: String): AyuVariant? =
            entries.firstOrNull { name in it.themeNames }

        fun detect(): AyuVariant? {
            val themeName = LafManager.getInstance().currentUIThemeLookAndFeel.name
            return fromThemeName(themeName)
        }
    }
}
