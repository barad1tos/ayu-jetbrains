package dev.ayuislands.accent.toolbar.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAwareAction
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ExternalAccentSource
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.rotation.ContrastAwareColorGenerator
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.mappings.ProjectAccentSwapService

/**
 * Pick a random readable accent for the focused variant via
 * [ContrastAwareColorGenerator] and apply it. Reuses the existing
 * rotation-surface generator — do NOT introduce a parallel palette.
 *
 * Pattern J premium gate on `update`; Pattern B catches RuntimeException only
 * around the generator + apply chain; Pattern D Boolean gate on
 * `applyFromHexString` before publishing `notifyExternalApply`.
 */
class RandomAccentAction : DumbAwareAction("Random Accent", "Pick a random readable accent", null) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible =
            AccentContext.isQuickSwitcherActive() &&
            LicenseChecker.isLicensedOrGrace()
    }

    override fun actionPerformed(event: AnActionEvent) {
        val context = AccentContext.detectQuickSwitcher() ?: return
        val hex =
            try {
                when (context) {
                    is AccentContext.Ayu -> ContrastAwareColorGenerator.generate(context.ayuVariant)
                    AccentContext.External -> ContrastAwareColorGenerator.generate(AyuVariant.MIRAGE)
                }
            } catch (exception: RuntimeException) {
                LOG.warn("Random: generate failed", exception)
                return
            }
        try {
            val applied = AccentApplicator.applyFromHexString(hex)
            if (applied) {
                persistExternalAccentIfNeeded(context, hex)
                ProjectAccentSwapService.getInstance().notifyExternalApply(hex)
            } else {
                LOG.warn("Random: applyFromHexString rejected hex=$hex")
            }
        } catch (exception: RuntimeException) {
            LOG.warn("Random: apply failed hex=$hex", exception)
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
        val LOG = logger<RandomAccentAction>()
    }
}
