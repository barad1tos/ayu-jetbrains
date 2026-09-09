package dev.ayuislands.accent

/**
 * Reads the macOS system appearance (Light / Dark mode).
 *
 * Returns null on non-macOS platforms or when detection fails.
 */
object SystemAppearanceProvider {
    enum class Appearance { LIGHT, DARK }

    private val cache = CachedMacReader { readFromSystem() }

    fun resolve(): Appearance? = cache.read()

    internal fun readFromSystem(
        startProcess: () -> Process = {
            ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle")
                .redirectErrorStream(true)
                .start()
        },
    ): Appearance? {
        val result = MacPreferenceReader.read(startProcess) ?: return null
        return appearanceFor(result)
    }

    private fun appearanceFor(result: MacPreferenceResult): Appearance {
        if (result.exitCode != 0) return Appearance.LIGHT
        return if (result.output.trim().equals("Dark", ignoreCase = true)) Appearance.DARK else Appearance.LIGHT
    }
}
