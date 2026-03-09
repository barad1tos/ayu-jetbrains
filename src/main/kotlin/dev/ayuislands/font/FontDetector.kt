package dev.ayuislands.font

import java.awt.GraphicsEnvironment

/** Detects installed Nerd Fonts via GraphicsEnvironment. Caches results for the session. */
object FontDetector {
    private var cache: Set<String>? = null

    private fun installedFonts(): Set<String> {
        if (cache == null) {
            cache =
                GraphicsEnvironment
                    .getLocalGraphicsEnvironment()
                    .availableFontFamilyNames
                    .map { it.lowercase() }
                    .toSet()
        }
        return cache!!
    }

    /** Check if any alias of the preset's font is installed on the system. */
    fun isInstalled(preset: FontPreset): Boolean =
        preset.fontAliases.any {
            installedFonts().contains(it.lowercase())
        }

    /** Return the actual installed font family name (first matching alias), or null. */
    fun resolveFamily(preset: FontPreset): String? =
        preset.fontAliases.firstOrNull {
            installedFonts().contains(it.lowercase())
        }

    /** Check all presets at once — used by the settings panel. */
    fun detectAll(): Map<FontPreset, Boolean> = FontPreset.entries.associateWith { isInstalled(it) }
}
