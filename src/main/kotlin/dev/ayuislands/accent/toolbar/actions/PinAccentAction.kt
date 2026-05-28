package dev.ayuislands.accent.toolbar.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAwareAction
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.mappings.AccentMappingsSettings
import dev.ayuislands.settings.mappings.ProjectAccentSwapService

/**
 * Pin the focused project's current accent into [AccentMappingsSettings].
 *
 * Writes the app-level personal-pin map:
 * `AccentMappingsSettings.state.projectAccents[key] = hex`. A future split
 * into a Shared (`.idea/`) vs Personal (app-level) lane is planned — the
 * follow-up marker below keeps the integration point discoverable.
 *
 * Premium gate via Pattern J two-level predicate
 * (`AyuVariant.isAyuActive() && LicenseChecker.isLicensedOrGrace()`) on every
 * `update` tick — both the right-click context menu and the popup quick-action
 * row must go through this gate.
 */
class PinAccentAction : DumbAwareAction("Pin Accent", "Pin the current accent to the focused project", null) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible =
            AyuVariant.isAyuActive() &&
            LicenseChecker.isLicensedOrGrace()
    }

    override fun actionPerformed(event: AnActionEvent) {
        val variant = AyuVariant.detect() ?: return
        val project = AccentApplicator.resolveFocusedProject() ?: return
        val hex =
            try {
                AccentResolver.resolve(project, variant)
            } catch (exception: RuntimeException) {
                LOG.warn("Pin: resolve failed", exception)
                return
            }
        val key =
            AccentResolver.projectKey(project) ?: run {
                LOG.warn("Pin: projectKey null for ${project.name}")
                return
            }
        // Follow-up: split shared vs personal pin lanes — write to .idea/
        // for shared pins, app-level state for personal. Today everything
        // goes through the app-level map below (FOLLOWUP-PIN-LANE-SPLIT).
        AccentMappingsSettings.getInstance().state.projectAccents[key] = hex
        try {
            val applied = AccentApplicator.applyFromHexString(hex)
            if (applied) {
                ProjectAccentSwapService.getInstance().notifyExternalApply(hex)
            } else {
                LOG.warn("Pin: applyFromHexString rejected hex=$hex")
            }
        } catch (exception: RuntimeException) {
            LOG.warn("Pin: apply failed hex=$hex", exception)
        }
    }

    private companion object {
        val LOG = logger<PinAccentAction>()
    }
}
