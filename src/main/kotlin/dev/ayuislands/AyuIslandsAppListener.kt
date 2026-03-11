package dev.ayuislands

import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.ui.LafManager
import com.intellij.openapi.diagnostic.logger
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.settings.AyuIslandsSettings

internal class AyuIslandsAppListener : AppLifecycleListener {
    @Suppress("UnstableApiUsage")
    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        val themeName = LafManager.getInstance().currentUIThemeLookAndFeel.name
        val variant = AyuVariant.fromThemeName(themeName) ?: return

        val accentHex = AyuIslandsSettings.getInstance().getAccentForVariant(variant)
        AccentApplicator.apply(accentHex)
        LOG.info("Ayu Islands accent pre-applied in appFrameCreated: $accentHex for ${variant.name}")
    }

    companion object {
        private val LOG = logger<AyuIslandsAppListener>()
    }
}
