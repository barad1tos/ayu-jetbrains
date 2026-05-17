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
 * Wave 3 (Plan 48-03) ships the app-level personal-pin path per RESEARCH §6:
 * `AccentMappingsSettings.state.projectAccents[key] = hex`. Phase 41 will split
 * this into a Shared (`.idea/`) vs Personal (app-level) lane — the `TODO Phase
 * 41` marker below tells the Phase 41 planner the integration point. The
 * marker is locked by `PinAccentActionTest` so it cannot be removed without a
 * conscious decision.
 *
 * Premium gate via Pattern J two-level predicate
 * (`AyuVariant.isAyuActive() && LicenseChecker.isLicensedOrGrace()`) on every
 * `update` tick — the right-click context menu (Wave 3) and popup quick-action
 * row (Wave 4) must both go through this gate.
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
        // TODO Phase 41 — split into Shared (.idea/) vs Personal (app-level).
        // For now we write to the app-level personal-pin map per RESEARCH §6.
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
