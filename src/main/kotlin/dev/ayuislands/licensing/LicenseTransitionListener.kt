package dev.ayuislands.licensing

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.LicensingFacade
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.commitpanel.CommitPanelAutoFitManager
import dev.ayuislands.settings.AyuIslandsSettings

/**
 * Listens for license-state transitions and reacts on three axes:
 *
 *  1. **Premium wizard re-arm** — on unlicensed → licensed transitions only, resets
 *     [dev.ayuislands.settings.AyuIslandsState.premiumOnboardingShown] to `false` so
 *     the next IDE startup surfaces the premium wizard via OnboardingOrchestrator.
 *     No mid-session UI is shown — the re-armed wizard appears at next launch only.
 *
 *  2. **Chrome accent re-apply** — on EITHER transition direction (licensed↔unlicensed),
 *     re-applies the accent so chrome tinting picks up the fresh license state.
 *     [dev.ayuislands.accent.AccentResolver] short-circuits overrides when unlicensed
 *     (chrome falls back to global accent) and honors them when licensed. External
 *     chrome surfaces also re-evaluate their runtime entitlement without rewriting
 *     the stored preference.
 *
 *  3. **Workspace renderer cleanup** — on licensed → unlicensed transitions,
 *     re-applies Commit panel workspace management for open projects so paid
 *     path-display renderers are removed immediately, not only after restart.
 *
 * Tracks the previous license state to filter duplicate notifications. An initial
 * licensed callback records state without repainting; an initial unlicensed callback
 * performs one chrome reconciliation because startup may have optimistically rendered
 * while the licensing facade was still unavailable.
 *
 * State mutation and [AccentApplicator.applyForFocusedProject] (which is `@RequiresEdt`)
 * are dispatched to EDT via `invokeLater` because Topic listeners may fire on
 * arbitrary threads.
 */
internal class LicenseTransitionListener : LicensingFacade.LicenseStateListener {
    @Volatile
    private var wasLicensed: Boolean? = null

    override fun licenseStateChanged(facade: LicensingFacade?) {
        try {
            val isNowLicensed = LicenseChecker.isLicensedOrGrace()
            val previous = wasLicensed
            wasLicensed = isNowLicensed

            // Re-arm premium wizard only on unlicensed → licensed transition.
            // Skip initial notification (previous == null) and licensed → licensed refreshes.
            if (previous == false && isNowLicensed) {
                val state = AyuIslandsSettings.getInstance().state
                if (state.premiumOnboardingShown) {
                    ApplicationManager.getApplication().invokeLater {
                        state.premiumOnboardingShown = false
                        LOG.info("Ayu license: unlicensed->licensed transition; premium wizard re-armed")
                    }
                }
            }

            // Re-apply accent on either transition direction so chrome picks up the fresh
            // license state. Resolver short-circuits overrides when unlicensed (chrome falls
            // back to global accent) and honors them when licensed. `applyForFocusedProject`
            // is @RequiresEdt — dispatch via invokeLater. AccentContext.detect() also
            // preserves external-theme support, while null means no Ayu behavior is active.
            val isTransition = previous != null && previous != isNowLicensed
            val isInitialRevoke = previous == null && !isNowLicensed
            if (isTransition || isInitialRevoke) {
                ApplicationManager.getApplication().invokeLater {
                    try {
                        if (!isNowLicensed) {
                            cleanupCommitPanelPathRendering()
                        }

                        val context = AccentContext.detect()
                        if (context != null) {
                            AccentApplicator.applyForFocusedProject(context)
                            LOG.info("Ayu license transition: re-applied accent for chrome refresh")
                        }
                    } catch (exception: RuntimeException) {
                        LOG.error("Ayu license: failed to refresh UI after license transition", exception)
                    }
                }
            }
        } catch (exception: RuntimeException) {
            LOG.error("Ayu license: failed to handle license state change", exception)
        }
    }

    private fun cleanupCommitPanelPathRendering() {
        val openProjects =
            try {
                ProjectManager.getInstance().openProjects
            } catch (exception: RuntimeException) {
                LOG.warn("Ayu license: failed to enumerate projects for commit-panel cleanup", exception)
                return
            }
        for (project in openProjects) {
            if (!project.isDisposed) {
                try {
                    CommitPanelAutoFitManager.getInstance(project).apply()
                } catch (exception: RuntimeException) {
                    LOG.warn("Ayu license: failed to clean commit-panel rendering", exception)
                }
            }
        }
    }

    private companion object {
        private val LOG = logger<LicenseTransitionListener>()
    }
}
