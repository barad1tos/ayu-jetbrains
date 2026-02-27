package dev.ayuislands.accent

import com.intellij.ide.ui.LafManager

enum class AyuVariant(val defaultAccent: String, val themeNames: Set<String>) {
    MIRAGE("#FFCC66", setOf("Ayu Islands Mirage", "Ayu Islands Mirage (Islands UI)")),
    DARK("#E6B450", setOf("Ayu Islands Dark", "Ayu Islands Dark (Islands UI)")),
    LIGHT("#F29718", setOf("Ayu Islands Light", "Ayu Islands Light (Islands UI)"));

    companion object {
        fun fromThemeName(name: String): AyuVariant? =
            entries.firstOrNull { name in it.themeNames }

        fun detect(): AyuVariant? {
            val themeName = LafManager.getInstance().currentUIThemeLookAndFeel.name
            return fromThemeName(themeName)
        }
    }
}
