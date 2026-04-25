package dev.ayuislands

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.ProjectManager
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.font.FontPresetApplicator
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.theme.AyuEditorSchemeBinder
import dev.ayuislands.ui.ComponentTreeRefresher

/** Re-applies accent, font, glow, and scrollbar settings on theme change. */
class AyuIslandsLafListener : LafManagerListener {
    @Suppress("UnstableApiUsage")
    override fun lookAndFeelChanged(source: LafManager) {
        val variant = AyuVariant.detect()
        if (variant == null) {
            // Switched away from the Ayu theme -- clean up accent, font, and glow overrides
            AccentApplicator.revertAll()
            FontPresetApplicator.revert()
            GlowOverlayManager.syncGlowForAllProjects()
            return
        }

        val settings = AyuIslandsSettings.getInstance()

        // Bind matching editor color scheme BEFORE `AccentApplicator` mutates
        // the global scheme. `AccentApplicator.applyAlwaysOnEditorKeys` writes
        // to `EditorColorsManager.globalScheme` in-place; if the bind happened
        // AFTER, the prior scheme (`Default` / `Darcula` / another Ayu) would
        // be silently polluted with accent overrides on `TAB_UNDERLINE` /
        // `BUTTON_BACKGROUND` / `BOOKMARKS_ATTRIBUTES`, while the
        // freshly-swapped Ayu scheme would lack the user's accent. Pattern G
        // — apply path symmetry: `revertAll` on LAF-back operates on the
        // same scheme this mutation lands on. Boolean return ignored: all
        // three return-false branches (already-matched, user-custom skip,
        // target-missing) are safe to fall through; binder logs internally.
        if (settings.state.syncEditorScheme) {
            AyuEditorSchemeBinder.bindForVariant(variant)
        }

        val accentHex = AccentApplicator.applyForFocusedProject(variant)
        LOG.info("Ayu Islands accent re-applied on theme change: $accentHex")

        // Re-apply font preset if enabled
        FontPresetApplicator.applyFromState()

        // Track manual sub-variant choices for appearance sync
        val syncService = AppearanceSyncService.getInstance()
        if (settings.state.followSystemAppearance && !syncService.programmaticSwitch) {
            val themeName = source.currentUIThemeLookAndFeel.name
            syncService.recordManualChoice(themeName)
        }
        syncService.clearProgrammaticSwitch()

        // Platform already walked the component tree during the LAF change, resetting component-level
        // overrides (scrollbar preferredSize, horizontal policy, rendering wrappers). Publish the
        // refresh event per open project so subscribed managers reapply. No tree walk needed here.
        //
        // Load-bearing platform-behavior assumption — verified against IntelliJ Platform 2025.1
        // (`LafManagerImpl.updateLafNoSave` walks frames before firing `lookAndFeelChanged`).
        // If a future platform bump changes the order, scrollbar hides will regress after theme
        // switches and we'll need to switch this back to `ComponentTreeRefresher.walkAndNotify`.
        for (openProject in ProjectManager.getInstance().openProjects) {
            if (openProject.isDefault || openProject.isDisposed) continue
            ComponentTreeRefresher.notifyOnly(openProject)
        }

        // Update glow overlays with new accent color
        GlowOverlayManager.syncGlowForAllProjects()
    }

    companion object {
        private val LOG = logger<AyuIslandsLafListener>()
    }
}
