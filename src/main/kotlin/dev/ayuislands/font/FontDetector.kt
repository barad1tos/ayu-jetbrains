package dev.ayuislands.font

import dev.ayuislands.settings.AyuIslandsSettings
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.font.FontRenderContext
import kotlin.math.abs

/** Detects installed Nerd Fonts via GraphicsEnvironment. Caches results for the session. */
object FontDetector {
    private const val MONOSPACE_PROBE_SIZE = 12
    private const val MONOSPACE_WIDTH_TOLERANCE = 0.01

    @Volatile
    private var cache: Set<String>? = null

    @Volatile
    private var monospaceCache: List<String>? = null

    @Synchronized
    private fun installedFonts(): Set<String> {
        cache?.let { return it }
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val names = environment.availableFontFamilyNames.mapTo(mutableSetOf()) { it.lowercase() }
        cache = names
        return names
    }

    /** Return all monospace font families installed on the system (sorted). */
    @Synchronized
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
                    narrowWidth > 0 && abs(narrowWidth - wideWidth) < MONOSPACE_WIDTH_TOLERANCE
                }.sorted()
        monospaceCache = result
        return result
    }

    /**
     * Check if any alias of the preset's font is installed on the system OR has been
     * recorded as installed via the runtime [FontInstaller] (state.installedFonts).
     *
     * The state-side check covers the window between FontInstaller registering a font
     * and `GraphicsEnvironment.availableFontFamilyNames` reflecting the new family —
     * the JVM occasionally lags here, especially on the first IDE launch after install.
     */
    fun isInstalled(preset: FontPreset): Boolean {
        if (preset.fontAliases.any { installedFonts().contains(it.lowercase()) }) return true
        val recorded =
            try {
                AyuIslandsSettings.getInstance().state.installedFonts
            } catch (_: IllegalStateException) {
                return false
            } catch (_: NullPointerException) {
                // Unit test / no Application — fall through to "not installed" rather than crash.
                return false
            }
        return preset.fontAliases.any { recorded.contains(it) }
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
