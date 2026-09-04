package dev.ayuislands.licensing

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresEdt
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.CodeGlanceProIntegration
import dev.ayuislands.accent.toolbar.QuickSwitcherPopup
import dev.ayuislands.commitpanel.CommitPanelAutoFitManager
import dev.ayuislands.editor.EditorScrollbarManager
import dev.ayuislands.gitpanel.GitPanelAutoFitManager
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.indent.IndentRainbowSync
import dev.ayuislands.integration.propagateFailure
import dev.ayuislands.projectview.ProjectViewScrollbarManager
import dev.ayuislands.rotation.AccentRotationService
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.syntax.SyntaxIntensityService
import dev.ayuislands.vcs.VcsColorApplier

internal data class ReconciliationFailure(
    val operation: String,
    val error: Throwable,
)

internal data class ReconciliationResult(
    val failures: List<ReconciliationFailure>,
) {
    val isSuccess: Boolean
        get() = failures.isEmpty()

    companion object {
        val Success = ReconciliationResult(emptyList())
    }
}

internal fun reconciliationError(failures: List<ReconciliationFailure>): IllegalStateException {
    val error =
        IllegalStateException(
            "Ayu entitlement reconciliation incomplete: " +
                failures.joinToString { it.operation },
        )
    failures.forEach { failure -> error.addSuppressed(failure.error) }
    return error
}

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
    ): ReconciliationResult {
        if (entitlement == LicenseEntitlement.UNKNOWN) return ReconciliationResult.Success
        return LicenseChecker.withConfirmedEntitlement(entitlement) {
            reconcileConfirmed(entitlement, projects)
        }
    }

    private fun reconcileConfirmed(
        entitlement: LicenseEntitlement,
        projects: Iterable<Project>,
    ): ReconciliationResult {
        val failures = mutableListOf<ReconciliationFailure>()

        runSurface(failures, "close Quick Switcher popups") {
            QuickSwitcherPopup.closeOpenPopups()
        }
        if (entitlement == LicenseEntitlement.UNLICENSED) {
            runSurface(failures, "stop accent rotation") {
                AccentRotationService.getInstance().stopRotation()
            }
            runSurface(failures, "restore CodeGlance Pro") {
                CodeGlanceProIntegration.restoreOwnedState().propagateFailure()
            }
            runSurface(failures, "restore Indent Rainbow") {
                IndentRainbowSync.restoreOwnedState().propagateFailure()
            }
        }

        projects
            .filterNot { it.isDisposed }
            .forEach { reconcileWorkspace(it, failures) }

        runSurface(failures, "refresh VCS colors") {
            when (entitlement) {
                LicenseEntitlement.LICENSED -> VcsColorApplier.applyAll()
                LicenseEntitlement.UNLICENSED -> VcsColorApplier.revertAll()
                LicenseEntitlement.UNKNOWN -> return@runSurface
            }
        }
        runSurface(failures, "re-apply accent") {
            AccentContext.detect()?.let { context ->
                AccentApplicator.applyForFocusedProject(context).requireClean()
            }
        }
        runSurface(failures, "refresh glow") {
            GlowOverlayManager.syncGlowForAllProjects()
        }
        runSurface(failures, "refresh syntax") {
            SyntaxIntensityService.getInstance().reapplyForActiveLaf()
        }

        if (entitlement == LicenseEntitlement.LICENSED) {
            runSurface(failures, "resume accent rotation", ::resumeRotation)
        }
        if (failures.isNotEmpty()) {
            val error = reconciliationError(failures)
            LOG.warn(error.message.orEmpty(), error)
        }
        return ReconciliationResult(failures.toList())
    }

    private fun reconcileWorkspace(
        project: Project,
        failures: MutableList<ReconciliationFailure>,
    ) {
        runSurface(failures, "refresh Project view") {
            ProjectViewScrollbarManager.getInstance(project).apply()
        }
        runSurface(failures, "refresh editor scrollbars") {
            EditorScrollbarManager.getInstance(project).apply()
        }
        runSurface(failures, "refresh Commit panel") {
            CommitPanelAutoFitManager.getInstance(project).apply()
        }
        runSurface(failures, "refresh Git panel") {
            GitPanelAutoFitManager.getInstance(project).apply()
        }
    }

    private fun resumeRotation() {
        val state = AyuIslandsSettings.getInstance().state
        if (!state.accentRotationEnabled || state.followSystemAccent) return

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

    @Suppress("TooGenericExceptionCaught") // Surface isolation records checked reflection failures too.
    private inline fun runSurface(
        failures: MutableList<ReconciliationFailure>,
        description: String,
        action: () -> Unit,
    ) {
        try {
            action()
        } catch (exception: Exception) {
            failures += ReconciliationFailure(description, exception)
        }
    }

    private const val MS_PER_HOUR = 3_600_000L
    private val LOG = logger<EntitlementReconciler>()
}
