package dev.ayuislands.whatsnew

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFocusManager
import dev.ayuislands.onboarding.OnboardingSchedulerService
import dev.ayuislands.settings.AyuIslandsSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Decides whether to open the What's New tab and schedules the open.
 *
 * Called from [dev.ayuislands.UpdateNotifier] on first IDE launch after upgrade
 * and from [ShowWhatsNewAction] on user demand. Two-layer dedup:
 *  - Persistent: [dev.ayuislands.settings.AyuIslandsState.lastWhatsNewShownVersion]
 *    survives IDE restarts. Once the tab opens for v2.5.0, it never auto-opens
 *    again until v2.6.0 (or whichever version next ships a manifest).
 *  - In-session: [WhatsNewOrchestrator] handles the multi-window startup race.
 *
 * Tab open mirrors `StartupLicenseHandler.openWizardIfThisProjectWins` —
 * focus-aware so the user's currently-focused project gets the tab, not just
 * the first one to schedule.
 */
internal object WhatsNewLauncher {
    private val LOG = logger<WhatsNewLauncher>()

    /** Delay before opening so the IDE frame settles and focus stabilizes. */
    private const val OPEN_DELAY_MS = 500

    /**
     * Auto-trigger entry called from [dev.ayuislands.UpdateNotifier]. Returns
     * `true` when an open was scheduled (caller skips the balloon notification
     * for this version regardless of async outcome). Returns `false` when not
     * eligible — caller falls back to the existing balloon path.
     *
     * Eligibility: [AyuIslandsState.lastWhatsNewShownVersion] differs from the
     * current version AND a manifest resource exists for the current version.
     */
    fun openIfEligible(
        project: Project,
        currentVersion: String,
    ): Boolean {
        val state = AyuIslandsSettings.getInstance().state
        val manifestPresent = WhatsNewManifestLoader.manifestExists(currentVersion)
        if (!isEligible(state.lastWhatsNewShownVersion, currentVersion, manifestPresent)) {
            return false
        }
        scheduleOpen(project, currentVersion, isManual = false)
        return true
    }

    /**
     * Manual entry called from [ShowWhatsNewAction]. Bypasses the persistent
     * `lastWhatsNewShownVersion` gate — the user is explicitly asking to see
     * the tab again. Still respects [WhatsNewOrchestrator] in-session pick to
     * avoid double-opening if the auto-trigger races with the menu click.
     *
     * Returns `false` when no manifest exists for the current version (the
     * action's [ShowWhatsNewAction.update] disables the menu item in that
     * case, so this is a defense-in-depth check).
     */
    fun openManually(project: Project): Boolean {
        val descriptor = pluginDescriptor() ?: return false
        if (!WhatsNewManifestLoader.manifestExists(descriptor.version)) return false
        scheduleOpen(project, descriptor.version, isManual = true)
        return true
    }

    /**
     * Pure eligibility helper — extracted so unit tests can exercise the gate
     * without bringing up the platform.
     *
     * Returns true iff: no record of this version having been shown, AND a
     * manifest resource exists. Either gate failing means "skip the tab,
     * fall through to balloon".
     */
    internal fun isEligible(
        lastShownVersion: String?,
        currentVersion: String,
        manifestPresent: Boolean,
    ): Boolean {
        if (!manifestPresent) return false
        if (lastShownVersion == currentVersion) return false
        return true
    }

    private fun scheduleOpen(
        project: Project,
        currentVersion: String,
        isManual: Boolean,
    ) {
        val settings = AyuIslandsSettings.getInstance()
        val scope = OnboardingSchedulerService.getInstance(project).scope()
        LOG.info(
            "Ayu What's New: scheduling tab open " +
                "(version=$currentVersion, manual=$isManual, delay=${OPEN_DELAY_MS}ms)",
        )
        scope.launch {
            delay(OPEN_DELAY_MS.milliseconds)
            if (project.isDisposed) return@launch
            openIfThisProjectWins(project, isManual) {
                settings.state.lastWhatsNewShownVersion = currentVersion
            }
        }
    }

    /**
     * Hop to EDT and open the tab in the user's active project. Mirrors
     * [dev.ayuislands.StartupLicenseHandler.openWizardIfThisProjectWins]:
     *  - In merged-window mode (project tabs), `IdeFocusManager.lastFocusedFrame.project`
     *    identifies which project tab the user is viewing.
     *  - Only the matching project opens the tab; others bail.
     *  - If no frame is focused (cold start), the first project to call
     *    [WhatsNewOrchestrator.tryPick] wins as a fallback.
     *
     * For [isManual] = true, the focus check is skipped — the user explicitly
     * asked from the action menu, so this project is the right one by definition.
     */
    private suspend fun openIfThisProjectWins(
        project: Project,
        isManual: Boolean,
        onSuccess: () -> Unit,
    ) {
        withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
            if (project.isDisposed) return@withContext

            if (!isManual) {
                val activeProject = IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project
                if (activeProject != null && activeProject != project) {
                    LOG.info("Ayu What's New: ${project.name} is not the active tab — deferring")
                    return@withContext
                }
            }

            if (!WhatsNewOrchestrator.tryPick()) {
                LOG.info("Ayu What's New: another project already claimed the tab slot")
                return@withContext
            }

            LOG.info("Ayu What's New: opening tab in ${project.name}")
            try {
                FileEditorManager.getInstance(project).openFile(WhatsNewVirtualFile(), true)
                onSuccess()
            } catch (exception: RuntimeException) {
                LOG.error("Ayu What's New: failed to open tab in ${project.name}", exception)
            }
        }
    }

    private fun pluginDescriptor() =
        com.intellij.ide.plugins.PluginManagerCore.getPlugin(
            com.intellij.openapi.extensions.PluginId
                .getId(PLUGIN_ID),
        )

    private const val PLUGIN_ID = "com.ayuislands.theme"
}
