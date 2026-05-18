package dev.ayuislands.accent.toolbar.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAwareAction
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.color.AccentHsl
import dev.ayuislands.licensing.LicenseChecker
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
            AyuVariant.isAyuActive() &&
            LicenseChecker.isLicensedOrGrace()
    }

    override fun actionPerformed(event: AnActionEvent) {
        val variant = AyuVariant.detect() ?: return
        val project = AccentApplicator.resolveFocusedProject()
        val currentHex =
            try {
                AccentResolver.resolve(project, variant)
            } catch (exception: RuntimeException) {
                LOG.warn("Lighter: resolve failed", exception)
                return
            }
        val newHex = AccentHsl.lighten(currentHex)
        try {
            val applied = AccentApplicator.applyFromHexString(newHex)
            if (applied) {
                ProjectAccentSwapService.getInstance().notifyExternalApply(newHex)
            } else {
                LOG.warn("Lighter: applyFromHexString rejected hex=$newHex")
            }
        } catch (exception: RuntimeException) {
            LOG.warn("Lighter: apply failed hex=$newHex", exception)
        }
    }

    private companion object {
        val LOG = logger<LighterAccentAction>()
    }
}
