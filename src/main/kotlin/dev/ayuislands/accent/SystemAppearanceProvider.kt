package dev.ayuislands.accent

import com.intellij.openapi.util.SystemInfo
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * Reads the macOS system appearance (Light / Dark mode).
 *
 * Returns null on non-macOS platforms or when detection fails.
 */
object SystemAppearanceProvider {
    enum class Appearance { LIGHT, DARK }

    private const val CACHE_TTL_MS = 5_000L
    private const val PROCESS_TIMEOUT_MS = 2_000L

    @Volatile
    private var cachedAppearance: Appearance? = null

    @Volatile
    private var cacheTimestamp: Long = 0L

    fun resolve(): Appearance? {
        if (!SystemInfo.isMac) return null
        val now = System.currentTimeMillis()
        if (now - cacheTimestamp < CACHE_TTL_MS) return cachedAppearance
        if (SwingUtilities.isEventDispatchThread()) return cachedAppearance
        val result = readFromSystem()
        cachedAppearance = result
        cacheTimestamp = now
        return result
    }

    private fun readFromSystem(): Appearance? =
        try {
            val process =
                ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle")
                    .redirectErrorStream(true)
                    .start()
            val output =
                process.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            val finished = process.waitFor(PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return null
            }
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                // Key absent means Light mode
                Appearance.LIGHT
            } else if (output.equals("Dark", ignoreCase = true)) {
                Appearance.DARK
            } else {
                Appearance.LIGHT
            }
        } catch (_: Exception) {
            null
        }
}
