package dev.ayuislands.accent

import com.intellij.openapi.util.SystemInfo

/**
 * Reads the macOS system appearance (Light / Dark mode).
 *
 * Returns null on non-macOS platforms or when detection fails.
 */
object SystemAppearanceProvider {
    enum class Appearance { LIGHT, DARK }

    fun resolve(): Appearance? {
        if (!SystemInfo.isMac) return null
        return try {
            val process =
                ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle")
                    .redirectErrorStream(true)
                    .start()
            val output =
                process.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            val exitCode = process.waitFor()
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
}
