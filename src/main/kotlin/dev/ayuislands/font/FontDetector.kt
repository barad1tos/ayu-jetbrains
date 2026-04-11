package dev.ayuislands.font

import dev.ayuislands.settings.AyuIslandsSettings
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.font.FontRenderContext
import java.io.File
import kotlin.math.abs

/**
 * Three-tier health check result returned by [FontDetector.status].
 * See [FontDetector.status] KDoc for the exact semantics of each tier.
 */
enum class FontStatus {
    NOT_INSTALLED,
    HEALTHY,
    CORRUPTED,
}

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

    /**
     * Three-state font health check (D-10).
     *
     * - [FontStatus.NOT_INSTALLED]: the plugin has no record of this family AND
     *   the JVM doesn't see it. Settings UI shows "Install automatically".
     * - [FontStatus.HEALTHY]: the plugin recorded this family AND either (a) no
     *   file paths are persisted (legacy Phase 24 installs), (b) every persisted
     *   path still exists on disk, or (c) files are missing but
     *   `availableFontFamilyNames` still registers the family (per RESEARCH A3
     *   — JVM font registration survives file deletion until next restart).
     * - [FontStatus.CORRUPTED]: the plugin recorded this family, at least one
     *   persisted file is missing from disk, AND `availableFontFamilyNames` no
     *   longer contains the family. Settings UI shows "file missing — Reinstall".
     *
     * Call [invalidateCache] before [status] if fresh disk state is needed.
     * Results are memoized across calls within a single cache epoch so the
     * Settings panel can call [status] from the EDT without blocking on
     * repeated filesystem stat syscalls (D-11, RESEARCH Pitfall 4).
     *
     * **Runtime note (macOS):** on JBR 25 / macOS, deleting a font file from
     * `~/Library/Fonts` does NOT propagate to `availableFontFamilyNames` within
     * the same JVM session. As a result, CORRUPTED is usually only reachable on
     * the next IDE launch — the "file gone but JVM still sees it" branch is
     * hit first during the current session (returns HEALTHY). Verified in
     * Phase 25 smoke test.
     *
     * **Security note (T-25-05):** this function reads paths from
     * `state.installedFontFiles` and probes them with `File.exists()`. It
     * assumes trusted state input — an attacker with write access to
     * `ayuIslands.xml` already has local file access, so probing paths outside
     * the plugin sandbox is not a privilege elevation. Never follow the paths
     * for read / execute / delete here.
     */
    fun status(preset: FontPreset): FontStatus {
        if (!isInstalled(preset)) return FontStatus.NOT_INSTALLED

        val state =
            try {
                AyuIslandsSettings.getInstance().state
            } catch (_: IllegalStateException) {
                return FontStatus.HEALTHY
            } catch (_: NullPointerException) {
                return FontStatus.HEALTHY
            }

        val family =
            preset.fontAliases.firstOrNull { alias -> state.installedFonts.contains(alias) }
                ?: return FontStatus.HEALTHY

        val encodedPaths = state.installedFontFiles[family]
        if (encodedPaths.isNullOrBlank()) return FontStatus.HEALTHY

        val paths = encodedPaths.split('\n').filter { it.isNotBlank() }
        if (paths.isEmpty()) return FontStatus.HEALTHY

        val anyMissing = paths.any { !File(it).exists() }
        if (!anyMissing) return FontStatus.HEALTHY

        val jvmSees = installedFonts().any { it.equals(family, ignoreCase = true) }
        return if (jvmSees) FontStatus.HEALTHY else FontStatus.CORRUPTED
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
