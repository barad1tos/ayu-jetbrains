package dev.ayuislands

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupManager
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.commitpanel.CommitPanelAutoFitManager
import dev.ayuislands.editor.EditorScrollbarManager
import dev.ayuislands.gitpanel.GitPanelAutoFitManager
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.onboarding.OnboardingVirtualFile
import dev.ayuislands.projectview.ProjectViewScrollbarManager
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.PanelWidthMode

/**
 * License-state dispatch logic, extracted from
 * [AyuIslandsStartupActivity] for testability.
 */
internal object StartupLicenseHandler {
    private val LOG = logger<StartupLicenseHandler>()

    // Placeholder: measure empirically on M1 Pro — run measureCpuSpeed() 10x, take median
    private const val BASELINE_MS = 20L
    private const val BASE_DELAY_MS = 15_000
    private const val MIN_DELAY_MS = 3_000
    private const val MAX_DELAY_MS = 45_000

    /**
     * Computes an adaptive onboarding delay based on CPU speed.
     * Faster machines get shorter delays, slower machines get longer.
     *
     * Must be called from a background thread (runs the benchmark).
     */
    fun computeAdaptiveDelay(): Int {
        val measuredMs = StartupBenchmark.measureCpuSpeed()
        val coefficient = measuredMs.toDouble() / BASELINE_MS
        val delay = (BASE_DELAY_MS * coefficient).toInt()
        return delay.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS).also {
            LOG.info(
                "Ayu onboarding delay: ${it}ms " +
                    "(coefficient: ${"%.2f".format(coefficient)})",
            )
        }
    }

    fun applyLicensedDefaults(
        project: Project,
        settings: AyuIslandsSettings,
        delayMs: Int,
    ) {
        if (settings.state.trialExpiredNotified) {
            settings.state.trialExpiredNotified = false
            settings.state.proDefaultsApplied = false
            settings.state.trialWelcomeShown = false
        }

        if (!settings.state.proDefaultsApplied) {
            LicenseChecker.enableProDefaults()
            LOG.info(
                "Ayu Islands Pro defaults enabled " +
                    "(first-time license activation)",
            )

            if (!settings.state.trialWelcomeShown) {
                settings.state.trialWelcomeShown = true
                scheduleTrialWelcome(project, delayMs)
            }
        }

        if (!settings.state.workspaceDefaultsApplied) {
            LicenseChecker.applyWorkspaceDefaults()
            LOG.info(
                "Ayu Islands workspace defaults " +
                    "migrated for existing user",
            )
        }
    }

    private fun scheduleTrialWelcome(
        project: Project,
        delayMs: Int,
    ) {
        StartupManager
            .getInstance(project)
            .runAfterOpened {
                javax.swing
                    .Timer(delayMs) {
                        if (!project.isDisposed) {
                            FileEditorManager
                                .getInstance(project)
                                .openFile(OnboardingVirtualFile(), true)
                        }
                    }.apply {
                        isRepeats = false
                        start()
                    }
            }
    }

    fun applyUnlicensedDefaults(
        project: Project,
        variant: AyuVariant,
        settings: AyuIslandsSettings,
    ) {
        LicenseChecker.revertToFreeDefaults(variant)
        LOG.info(
            "Ayu Islands reverted to free defaults " +
                "for ${variant.name}",
        )

        if (!settings.state.trialExpiredNotified) {
            LicenseChecker.notifyTrialExpired(project)
            settings.state.trialExpiredNotified = true
        }
    }

    fun initWorkspaceServices(
        project: Project,
        settings: AyuIslandsSettings,
    ) {
        if (project.isDisposed) {
            LOG.info("Skipping workspace services — project disposed")
            return
        }
        val state = settings.state
        val hasProjectViewCustomizations =
            state.hideProjectViewHScrollbar ||
                state.hideProjectRootPath
        if (hasProjectViewCustomizations ||
            PanelWidthMode.fromString(
                state.projectPanelWidthMode,
            ) != PanelWidthMode.DEFAULT
        ) {
            ProjectViewScrollbarManager.getInstance(project)
        }

        if (state.hideEditorVScrollbar || state.hideEditorHScrollbar) {
            EditorScrollbarManager.getInstance(project)
        }

        if (PanelWidthMode.fromString(
                state.commitPanelWidthMode,
            ) != PanelWidthMode.DEFAULT
        ) {
            CommitPanelAutoFitManager.getInstance(project)
        }

        if (PanelWidthMode.fromString(
                state.gitPanelWidthMode,
            ) != PanelWidthMode.DEFAULT
        ) {
            GitPanelAutoFitManager.getInstance(project)
        }
    }
}
