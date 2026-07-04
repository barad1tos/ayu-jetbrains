package dev.ayuislands.whatsnew

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import dev.ayuislands.AyuPlugin
import dev.ayuislands.onboarding.OnboardingSchedulerService
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.ui.FocusWinningTabOpener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Decides whether to open the What's New tab and schedules the open.
 *
 * Called from [dev.ayuislands.UpdateNotifier] on first IDE launch after upgrade
 * and from [ShowWhatsNewAction] on user demand. Two-layer dedup:
 *  - Persistent: [dev.ayuislands.settings.AyuIslandsState.lastWhatsNewShownVersion]
 *    survives IDE restarts. Once the tab opens for v2.5.0, it never auto-opens
 *    again until v2.6.0 (or whichever version next ships a manifest).
 *  - In-session: [WhatsNewOrchestrator] holds the gate for the multi-window
 *    startup race.
 *
 * The focus-race protocol itself (EDT hop, disposed guard, focus check, CAS
 * claim, release-on-failure) lives in [FocusWinningTabOpener], shared with the
 * onboarding wizard scheduler in `dev.ayuislands.StartupLicenseHandler`.
 */
internal object WhatsNewLauncher {
    private val LOG = logger<WhatsNewLauncher>()

    /** Delay before opening so the IDE frame settles and focus stabilizes. */
    private const val OPEN_DELAY_MS = 500

    private val opener =
        FocusWinningTabOpener(
            gate = WhatsNewOrchestrator.gate,
            log = LOG,
            logPrefix = "Ayu What's New",
            subject = "tab",
        )

    /**
     * Auto-trigger entry called from [dev.ayuislands.UpdateNotifier]. Returns
     * `true` when an open was scheduled (caller skips the balloon notification
     * for this version regardless of async outcome). Returns `false` when not
     * eligible — caller falls back to the existing balloon path.
     *
     * Eligibility: [dev.ayuislands.settings.AyuIslandsState.lastWhatsNewShownVersion]
     * differs from the current version (after normalizing both sides via
     * [WhatsNewManifestLoader.normalizeVersion]) AND a manifest resource exists
     * for the current version.
     */
    fun openIfEligible(
        project: Project,
        currentVersion: String,
    ): Boolean {
        val state = AyuIslandsSettings.getInstance().state
        val normalized = WhatsNewManifestLoader.normalizeVersion(currentVersion)
        val manifestPresent = WhatsNewManifestLoader.manifestExists(currentVersion)
        if (!isWhatsNewEligible(state.lastWhatsNewShownVersion, currentVersion, manifestPresent)) {
            return false
        }
        scheduleOpen(project, normalized, isManual = false)
        return true
    }

    /**
     * Manual entry called from [ShowWhatsNewAction]. Bypasses BOTH the persistent
     * `lastWhatsNewShownVersion` gate AND the in-session [WhatsNewOrchestrator]
     * gate — the user is explicitly asking to see the tab. If a tab is already
     * open in this project, [FileEditorManager.openFile] focuses it instead of
     * creating a duplicate (light virtual file equality is identity-based, so
     * we rely on the FileEditorManager's own existing-file detection by name).
     *
     * Returns `false` when no manifest exists for the current version (the
     * action's [ShowWhatsNewAction.update] disables the menu item in that
     * case, so this is a defense-in-depth check). Returns `true` when an open
     * was scheduled — caller can use this to log/notify on no-op.
     */
    fun openManually(project: Project): Boolean {
        val descriptor = pluginDescriptor() ?: return false
        val normalized = WhatsNewManifestLoader.normalizeVersion(descriptor.version)
        if (!WhatsNewManifestLoader.manifestExists(descriptor.version)) return false
        scheduleOpen(project, normalized, isManual = true)
        return true
    }

    private fun scheduleOpen(
        project: Project,
        normalizedVersion: String,
        isManual: Boolean,
    ) {
        val settings = AyuIslandsSettings.getInstance()
        val scope = OnboardingSchedulerService.getInstance(project).scope()
        LOG.info(
            "Ayu What's New: scheduling tab open " +
                "(version=$normalizedVersion, manual=$isManual, delay=${OPEN_DELAY_MS}ms)",
        )
        scope.launch {
            try {
                delay(OPEN_DELAY_MS.milliseconds)
                if (project.isDisposed) return@launch
                // Manual triggers bypass both the focus check AND the
                // orchestrator gate — the user explicitly asked from the
                // action menu, so this project is the right one and the
                // auto-trigger one-shot must not block them.
                opener.open(
                    project = project,
                    bypassFocus = isManual,
                    bypassGate = isManual,
                    onSuccess = {
                        settings.state.lastWhatsNewShownVersion = normalizedVersion
                        LOG.info("Ayu What's New: marked $normalizedVersion as shown")
                    },
                ) { target -> openWhatsNewTab(target) }
            } catch (cancellation: CancellationException) {
                // Catch order matters: CancellationException is a RuntimeException
                // subtype, so this branch MUST come first to preserve structured
                // concurrency. The broader catch below swallows anything else.
                throw cancellation
            } catch (exception: RuntimeException) {
                // IllegalStateException (e.g. IdeFocusManager called during IDE
                // shutdown), platform IllegalArgumentException, etc. Errors
                // (OOM, LinkageError) intentionally propagate.
                LOG.error("Ayu What's New: launcher coroutine failed", exception)
            }
        }
    }

    /**
     * What's New-specific open action, run by [FocusWinningTabOpener] on EDT.
     *
     * [WhatsNewVirtualFile] uses identity equality, so a fresh instance would
     * NOT match an already-open tab and [FileEditorManager] would create a
     * duplicate. Look up the existing instance first and pass THAT to
     * `openFile`, which then focuses the existing tab instead. If MULTIPLE
     * stale instances exist (leaked from an earlier bug or a split-editor
     * race), focus the first and close the rest so the tab strip doesn't
     * collect orphans silently.
     */
    private fun openWhatsNewTab(project: Project) {
        val manager = FileEditorManager.getInstance(project)
        val matches = manager.openFiles.filterIsInstance<WhatsNewVirtualFile>()
        if (matches.size > 1) {
            LOG.warn("Ayu What's New: found ${matches.size} stale tabs in ${project.name}; closing extras")
            matches.drop(1).forEach { manager.closeFile(it) }
        }
        val target = matches.firstOrNull() ?: WhatsNewVirtualFile()
        manager.openFile(target, true)
    }

    private fun pluginDescriptor() = AyuPlugin.findLoadedPlugin(AyuPlugin.ID)
}
