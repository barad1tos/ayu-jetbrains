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

        /**
         * Canonical lifecycle predicate — every integration and revert path funnels
         * through this helper instead of inlining `detect() != null`. Single grep
         * target for "is the plugin's managed state authoritative right now" per
         * RECURRING_PITFALLS Pattern J (lifecycle gating).
         *
         * Safe on and off EDT — delegates to [detect], which reads `LafManager`
         * (a ServiceManager lookup + property read, thread-safe per IntelliJ docs).
         */
        fun isAyuActive(): Boolean = detect() != null

        fun isIslandsUi(): Boolean = currentThemeName().contains(ISLANDS_UI_SUFFIX)
    }
}
