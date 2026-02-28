package dev.ayuislands

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.diagnostic.logger
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.settings.AyuIslandsSettings

/** Re-applies accent color on theme change and syncs CodeGlance Pro viewport color. */
class AyuIslandsLafListener : LafManagerListener {

    override fun lookAndFeelChanged(source: LafManager) {
        val variant = AyuVariant.detect() ?: return
        val settings = AyuIslandsSettings.getInstance()
        val accentHex = settings.getAccentForVariant(variant)
        AccentApplicator.apply(accentHex)
        LOG.info("Ayu Islands accent re-applied on theme change: $accentHex")

        if (settings.state.cgpIntegrationEnabled) {
            applyCgpViewportColor(accentHex)
        }
    }

    fun applyCgpViewportColor(accentHex: String) {
        try {
            val hexWithoutHash = accentHex.removePrefix("#")
            val serviceClass = Class.forName("com.nasller.codeglance.config.CodeGlanceConfigService")
            val companionField = serviceClass.getDeclaredField("Companion")
            val companion = companionField.get(null)
            val getConfig = companion.javaClass.getMethod("getConfig")
            val configService = getConfig.invoke(companion)
            val getState = configService.javaClass.getMethod("getState")
            val state = getState.invoke(configService)
            val setViewportColor = state.javaClass.getMethod("setViewportColor", String::class.java)
            setViewportColor.invoke(state, hexWithoutHash)
            LOG.info("CodeGlance Pro viewport color set to $hexWithoutHash")
        } catch (exception: Exception) {
            LOG.info("CodeGlance Pro integration unavailable: ${exception.message}")
        }
    }

    companion object {
        private val LOG = logger<AyuIslandsLafListener>()
    }
}
