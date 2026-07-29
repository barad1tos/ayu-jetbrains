package dev.ayuislands.licensing

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresEdt
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.toolbar.QuickSwitcherPopup
import dev.ayuislands.commitpanel.CommitPanelAutoFitManager
import dev.ayuislands.editor.EditorScrollbarManager
import dev.ayuislands.gitpanel.GitPanelAutoFitManager
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.projectview.ProjectViewScrollbarManager
import dev.ayuislands.rotation.AccentRotationService
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.syntax.SyntaxIntensityService
import dev.ayuislands.vcs.VcsColorApplier

/**
 * Reconciles runtime-only premium surfaces after a definitive entitlement result.
 *
 * Surface managers own their cleanup and restore semantics. This coordinator only
 * establishes ordering and failure isolation; it never rewrites persisted feature
 * preferences. [LicenseEntitlement.UNKNOWN] is deliberately a no-op so a transient
 * licensing outage cannot manufacture a loss or recovery transition.
 */
internal object EntitlementReconciler {
    @RequiresEdt
    fun reconcile(
        entitlement: LicenseEntitlement,
        projects: Iterable<Project>,
    ) {
        if (entitlement == LicenseEntitlement.UNKNOWN) return

        runSurface("close Quick Switcher popups") {
            QuickSwitcherPopup.closeOpenPopups()
        }
        if (entitlement == LicenseEntitlement.UNLICENSED) {
            runSurface("stop accent rotation") {
                AccentRotationService.getInstance().stopRotation()
            }
        }

        projects
            .filterNot { it.isDisposed }
            .forEach(::reconcileWorkspace)

        runSurface("refresh VCS colors") {
            when (entitlement) {
                LicenseEntitlement.LICENSED -> VcsColorApplier.applyAll()
                LicenseEntitlement.UNLICENSED -> VcsColorApplier.revertAll()
                LicenseEntitlement.UNKNOWN -> return@runSurface
            }
        }
        runSurface("re-apply accent") {
            AccentContext.detect()?.let(AccentApplicator::applyForFocusedProject)
        }
        runSurface("refresh glow") {
            GlowOverlayManager.syncGlowForAllProjects()
        }
        runSurface("refresh syntax") {
            SyntaxIntensityService.getInstance().reapplyForActiveLaf()
        }

        if (entitlement == LicenseEntitlement.LICENSED) {
            runSurface("resume accent rotation", ::resumeRotation)
        }
    }

    private fun reconcileWorkspace(project: Project) {
        runSurface("refresh Project view") {
            ProjectViewScrollbarManager.getInstance(project).apply()
        }
        runSurface("refresh editor scrollbars") {
            EditorScrollbarManager.getInstance(project).apply()
        }
        runSurface("refresh Commit panel") {
            CommitPanelAutoFitManager.getInstance(project).apply()
        }
        runSurface("refresh Git panel") {
            GitPanelAutoFitManager.getInstance(project).apply()
        }
    }

    private fun resumeRotation() {
        val state = AyuIslandsSettings.getInstance().state
        if (!state.accentRotationEnabled) return

        val service = AccentRotationService.getInstance()
        val intervalMs = state.accentRotationIntervalHours * MS_PER_HOUR
        val lastSwitch = state.accentRotationLastSwitchMs
        val elapsed = System.currentTimeMillis() - lastSwitch
        if (lastSwitch == 0L || elapsed >= intervalMs) {
            service.rotateNow()
        } else {
            service.startRotationWithDelay(intervalMs - elapsed)
        }
    }

    private inline fun runSurface(
        description: String,
        action: () -> Unit,
    ) {
        try {
            action()
        } catch (exception: RuntimeException) {
            LOG.warn("Ayu entitlement: failed to $description", exception)
        }
    }

    private const val MS_PER_HOUR = 3_600_000L
    private val LOG = logger<EntitlementReconciler>()
}
