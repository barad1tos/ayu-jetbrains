package dev.ayuislands.accent

import com.intellij.ide.ui.LafManager

enum class AyuVariant(
    val defaultAccent: String,
    val neutralGray: String,
    val parentSchemeName: String,
    val themeNames: Set<String>,
) {
    MIRAGE("#FFCC66", "#445066", "Darcula", setOf("Ayu Mirage", "Ayu Mirage (Islands UI)")),
    DARK("#E6B450", "#2C3342", "Darcula", setOf("Ayu Dark", "Ayu Dark (Islands UI)")),
    LIGHT("#F29718", "#CCC8B8", "Default", setOf("Ayu Light", "Ayu Light (Islands UI)")),
    ;

    companion object {
        private const val ISLANDS_UI_SUFFIX = "(Islands UI)"

        fun fromThemeName(name: String): AyuVariant? = entries.firstOrNull { name in it.themeNames }

        @Suppress("UnstableApiUsage")
        fun currentThemeName(): String = LafManager.getInstance().currentUIThemeLookAndFeel.name

        fun detect(): AyuVariant? = fromThemeName(currentThemeName())

        fun isIslandsUi(): Boolean = currentThemeName().contains(ISLANDS_UI_SUFFIX)
    }
}
