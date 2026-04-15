package dev.ayuislands

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.diagnostic.logger
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant

internal class AyuIslandsAppListener : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        val themeName = AyuVariant.currentThemeName()
        val variant = AyuVariant.fromThemeName(themeName) ?: return

        // No project context yet; resolver falls through to the global accent
        // (or language override if one is configured and we later re-apply in StartupActivity).
        val accentHex = AccentResolver.resolve(null, variant)
        AccentApplicator.apply(accentHex)
        LOG.info("Ayu Islands accent pre-applied in appFrameCreated: $accentHex for ${variant.name}")
    }

    companion object {
        private val LOG = logger<AyuIslandsAppListener>()
    }
}
