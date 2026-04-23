package dev.ayuislands

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.diagnostic.logger
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.settings.AyuIslandsSettings

internal class AyuIslandsAppListener : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        val themeName = AyuVariant.currentThemeName()
        val variant = AyuVariant.fromThemeName(themeName) ?: return

        // Anti-flicker: if a previous session persisted the last-applied hex, use it
        // directly rather than re-resolving against a null project context (which always
        // returns the global accent and can flash Gold before per-project StartupActivity
        // runs). The live resolver chain still fires once projects settle (see
        // AyuIslandsStartupActivity.execute), so this cached hex is only authoritative
        // for the first painted frame.
        val cached = AyuIslandsSettings.getInstance().state.lastAppliedAccentHex
        val accentHex = cached ?: AccentResolver.resolve(null, variant)
        AccentApplicator.apply(accentHex)
        val source = if (cached != null) "cached" else "resolved"
        LOG.info("Ayu Islands accent pre-applied in appFrameCreated ($source): $accentHex for ${variant.name}")
    }

    companion object {
        private val LOG = logger<AyuIslandsAppListener>()
    }
}
