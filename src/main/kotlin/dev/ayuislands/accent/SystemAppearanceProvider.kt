package dev.ayuislands.accent

import java.util.concurrent.TimeUnit

/**
 * Reads the macOS system appearance (Light / Dark mode).
 *
 * Returns null on non-macOS platforms or when detection fails.
 */
object SystemAppearanceProvider {
    enum class Appearance { LIGHT, DARK }

    private const val PROCESS_TIMEOUT_MS = 2_000L

    private val cache = CachedMacReader { readFromSystem() }

    fun resolve(): Appearance? = cache.read()

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
