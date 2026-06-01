package dev.ayuislands.accent

import dev.ayuislands.settings.AyuIslandsSettings

sealed interface AccentContext {
    val variant: AyuVariant?

    data class Ayu(
        val ayuVariant: AyuVariant,
    ) : AccentContext {
        override val variant: AyuVariant
            get() = ayuVariant
    }

    data object External : AccentContext {
        override val variant: AyuVariant? = null
    }

    companion object {
        fun detect(): AccentContext? {
            val ayuVariant = AyuVariant.detect()
            if (ayuVariant != null) return Ayu(ayuVariant)

            val state = AyuIslandsSettings.getInstance().state
            return if (state.externalThemeEnhancementsEnabled) External else null
        }

        /** Returns true for both native Ayu themes and opted-in external theme compatibility. */
        fun isAccentActive(): Boolean = detect() != null
    }
}
