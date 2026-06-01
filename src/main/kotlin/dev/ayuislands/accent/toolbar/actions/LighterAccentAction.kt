package dev.ayuislands.accent.toolbar.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAwareAction
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.ExternalAccentSource
import dev.ayuislands.accent.color.AccentHsl
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.mappings.ProjectAccentSwapService

/**
 * Lighten the focused project's resolved accent by one HSL-lightness step
 * (`AccentHsl.STEP = 0.05`). Clamped to `[0.10, 0.95]` by [AccentHsl] so
 * repeated clicks at the ceiling are no-ops (apply still fires with the
 * unchanged hex — an "Already at maximum" balloon hint is a future
 * deliverable).
 */
class LighterAccentAction : DumbAwareAction("Lighter", "Lighten the current accent by 5%", null) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible =
            AccentContext.isAccentActive() &&
            LicenseChecker.isLicensedOrGrace()
    }

    override fun actionPerformed(event: AnActionEvent) {
        val context = AccentContext.detect() ?: return
        val project = AccentApplicator.resolveFocusedProject()
        val currentHex =
            try {
                AccentResolver.resolve(project, context)
            } catch (exception: RuntimeException) {
                LOG.warn("Lighter: resolve failed", exception)
                return
            }
        // `AccentResolver.resolve` contract guarantees a validated `#RRGGBB`;
        // wrap via `unsafeOf` to lift the contract into the type (Pattern K).
        val newHex = AccentHsl.lighten(AccentHex.unsafeOf(currentHex)).value
        try {
            val applied = AccentApplicator.applyFromHexString(newHex)
            if (applied) {
                persistExternalAccentIfNeeded(context, newHex)
                ProjectAccentSwapService.getInstance().notifyExternalApply(newHex)
            } else {
                LOG.warn("Lighter: applyFromHexString rejected hex=$newHex")
            }
        } catch (exception: RuntimeException) {
            LOG.warn("Lighter: apply failed hex=$newHex", exception)
        }
    }

    private fun persistExternalAccentIfNeeded(
        context: AccentContext,
        hex: String,
    ) {
        if (context != AccentContext.External) return
        val state = AyuIslandsSettings.getInstance().state
        state.externalThemeAccent = hex
        state.externalThemeAccentSource = ExternalAccentSource.MANUAL.name
    }

    private companion object {
        val LOG = logger<LighterAccentAction>()
    }
}
