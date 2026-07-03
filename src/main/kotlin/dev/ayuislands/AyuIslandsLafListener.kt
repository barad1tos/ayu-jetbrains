package dev.ayuislands

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.diagnostic.logger
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.reapply.ReapplyReason
import dev.ayuislands.reapply.ThemeReapplication
import dev.ayuislands.settings.AyuIslandsSettings

/** Re-applies accent, font, glow, and scrollbar settings on theme change. */
class AyuIslandsLafListener : LafManagerListener {
    override fun lookAndFeelChanged(source: LafManager) {
        val context = AccentContext.detect()

        // Appearance-sync bookkeeping needs the LafManager source and is UI-order-independent,
        // so it stays here (not a reapply step). Only runs for a live Ayu context.
        if (context is AccentContext.Ayu) {
            recordAppearanceChoice(source)
        }

        ThemeReapplication.reapply(ReapplyReason.ThemeSwitched(context)) { result ->
            if (!result.isClean) {
                LOG.warn(
                    "Theme reapplication had ${result.failures.size} failed step(s): " +
                        result.failures.joinToString { it.step.name },
                )
            } else {
                LOG.info("Ayu Islands theme reapplied (${context ?: "switched-away"})")
            }
        }
    }

    private fun recordAppearanceChoice(source: LafManager) {
        val settings = AyuIslandsSettings.getInstance()
        val syncService = AppearanceSyncService.getInstance()
        if (settings.state.followSystemAppearance && !syncService.programmaticSwitch) {
            syncService.recordManualChoice(AyuLaf.currentThemeName(source))
        }
        syncService.clearProgrammaticSwitch()
    }

    companion object {
        private val LOG = logger<AyuIslandsLafListener>()
    }
}
