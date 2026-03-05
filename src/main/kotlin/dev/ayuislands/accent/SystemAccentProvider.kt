package dev.ayuislands.accent

import java.util.concurrent.TimeUnit

/**
 * Reads the macOS system accent color and maps it to the nearest Ayu preset hex.
 *
 * Returns null on non-macOS platforms or when the accent cannot be determined.
 */
object SystemAccentProvider {
    // macOS AppleAccentColor integer → Ayu preset hex
    private const val BLUE_ACCENT = 4
    private const val PURPLE_ACCENT = 5
    private const val PINK_ACCENT = 6
    private const val RED_ACCENT = 0
    private const val ORANGE_ACCENT = 1
    private const val YELLOW_ACCENT = 2
    private const val GREEN_ACCENT = 3
    private const val GRAPHITE_ACCENT = -1

    private val MACOS_ACCENT_MAP: Map<Int, String> =
        mapOf(
            BLUE_ACCENT to "#73D0FF", // Sky
            PURPLE_ACCENT to "#DFBFFF", // Lavender
            PINK_ACCENT to "#F27983", // Rose
            RED_ACCENT to "#F28779", // Coral
            ORANGE_ACCENT to "#FFA659", // Orange
            YELLOW_ACCENT to "#FFCD66", // Gold
            GREEN_ACCENT to "#95E6CB", // Mint
            GRAPHITE_ACCENT to "#8A9199", // Slate
        )

    private const val DEFAULT_ACCENT_HEX = "#73D0FF" // Blue (macOS default)
    private const val PROCESS_TIMEOUT_MS = 2_000L

    private val cache = CachedMacReader { readFromSystem() }

    fun resolve(): String? = cache.read()

    private fun readFromSystem(): String? {
        return try {
            val process =
                ProcessBuilder("defaults", "read", "-g", "AppleAccentColor")
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
                DEFAULT_ACCENT_HEX
            } else {
                val accentValue =
                    output.toIntOrNull()
                        ?: return DEFAULT_ACCENT_HEX
                MACOS_ACCENT_MAP[accentValue] ?: DEFAULT_ACCENT_HEX
            }
        } catch (_: Exception) {
            null
        }
    }
}
