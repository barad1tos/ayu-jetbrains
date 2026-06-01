package dev.ayuislands

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.ProjectManager
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.font.FontPresetApplicator
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.syntax.SyntaxIntensityService
import dev.ayuislands.theme.AyuEditorSchemeBinder
import dev.ayuislands.ui.ComponentTreeRefresher

/** Re-applies accent, font, glow, and scrollbar settings on theme change. */
class AyuIslandsLafListener : LafManagerListener {
    override fun lookAndFeelChanged(source: LafManager) {
        val context = AccentContext.detect()
        if (context == null) {
            // Switched away from an active Ayu Islands context -- clean up managed overrides.
            AccentApplicator.revertAll()
            FontPresetApplicator.revert()
            GlowOverlayManager.syncGlowForAllProjects()
            return
        }

        when (context) {
            is AccentContext.Ayu -> applyAyuThemeChange(source, context)
            AccentContext.External -> applyExternalThemeChange()
        }
    }

    private fun applyAyuThemeChange(
        source: LafManager,
        context: AccentContext.Ayu,
    ) {
        val variant = context.ayuVariant
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

        val accentHex = AccentApplicator.applyForFocusedProject(context)
        LOG.info("Ayu Islands accent re-applied on theme change: $accentHex")

        // Re-apply font preset if enabled
        FontPresetApplicator.applyFromState()

        // Track manual sub-variant choices for appearance sync
        val syncService = AppearanceSyncService.getInstance()
        if (settings.state.followSystemAppearance && !syncService.programmaticSwitch) {
            val themeName = AyuLaf.currentThemeName(source)
            syncService.recordManualChoice(themeName)
        }
        syncService.clearProgrammaticSwitch()

        notifyOpenProjects()

        // Update glow overlays with new accent color
        GlowOverlayManager.syncGlowForAllProjects()

        // Re-apply syntax intensity preset after LAF switch back to Ayu (Pattern J).
        // The [AyuVariant.isAyuActive] gate is structurally redundant with the early-return
        // above (this block only executes when variant != null) but is preserved as the
        // Pattern J source-grep anchor for future audits. The listener owns lifecycle;
        // `SyntaxIntensityService.reapplyForActiveLaf` itself stays lifecycle-agnostic.
        if (AyuVariant.isAyuActive()) {
            SyntaxIntensityService.getInstance().reapplyForActiveLaf()
        }
    }

    private fun applyExternalThemeChange() {
        val accentHex = AccentApplicator.applyForFocusedProject(AccentContext.External)
        LOG.info("Ayu Islands external accent applied on theme change: $accentHex")

        // External themes only receive integration surfaces that can resolve against
        // a generic accent; editor scheme binding, font preset, and syntax intensity
        // remain Ayu-theme lifecycle features.
        GlowOverlayManager.syncGlowForAllProjects()
    }

    private fun notifyOpenProjects() {
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
    }

    companion object {
        private val LOG = logger<AyuIslandsLafListener>()
    }
}
