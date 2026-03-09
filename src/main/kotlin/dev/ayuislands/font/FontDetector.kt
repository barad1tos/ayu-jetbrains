package dev.ayuislands.font

import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.font.FontRenderContext

/** Detects installed Nerd Fonts via GraphicsEnvironment. Caches results for the session. */
object FontDetector {
    private const val MONOSPACE_PROBE_SIZE = 12
    private const val MONOSPACE_WIDTH_TOLERANCE = 0.01

    private var cache: Set<String>? = null
    private var monospaceCache: List<String>? = null

    private fun installedFonts(): Set<String> {
        if (cache == null) {
            val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
            val names = mutableSetOf<String>()
            for (name in environment.availableFontFamilyNames) {
                names.add(name.lowercase())
            }
            cache = names
        }
        return cache!!
    }

    /** Return all monospace font families installed on the system (sorted). */
    fun listMonospaceFonts(): List<String> {
        monospaceCache?.let { return it }
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val frc = FontRenderContext(null, true, true)
        val result =
            environment.availableFontFamilyNames
                .filter { name ->
                    val font = Font(name, Font.PLAIN, MONOSPACE_PROBE_SIZE)
                    val narrowWidth = font.getStringBounds("i", frc).width
                    val wideWidth = font.getStringBounds("M", frc).width
                    narrowWidth > 0 && Math.abs(narrowWidth - wideWidth) < MONOSPACE_WIDTH_TOLERANCE
                }.sorted()
        monospaceCache = result
        return result
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

    /** Clear the cached font list so the next check re-queries the system. */
    fun invalidateCache() {
        cache = null
        monospaceCache = null
    }

    /** Check all presets at once — used by the settings panel. */
    fun detectAll(): Map<FontPreset, Boolean> = FontPreset.entries.associateWith { !it.isCurated || isInstalled(it) }
}
