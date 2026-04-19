package dev.ayuislands.actions

import com.intellij.lang.Language
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import dev.ayuislands.accent.ProjectLanguageDetectionListener
import dev.ayuislands.accent.ProjectLanguageDetector
import dev.ayuislands.accent.ScanOutcome
import dev.ayuislands.accent.runCatchingPreservingCancellation
import dev.ayuislands.licensing.LicenseChecker

/**
 * Tools-menu action that forces a fresh project-language detection scan.
 *
 * Why it exists: the detector normally re-runs only on project open, on
 * content-root changes (gradle sync, module add/remove), and on project close
 * — so after a major manual refactor (language migration, mass rename) the
 * user can be stuck on a stale winner until the next Gradle sync. This action
 * is the manual bypass.
 *
 * Single-scan-per-click: the subscription to [ProjectLanguageDetectionListener.TOPIC]
 * is a one-shot — it disconnects inside the callback so rapid-fire clicks don't
 * pile subscriptions on the bus. The scheduler's dedup gate coalesces concurrent
 * scans, so one balloon fires per *completed* scan regardless of click spam.
 */
internal class RescanLanguageAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        event.presentation.isEnabledAndVisible =
            project != null &&
            !project.isDefault &&
            !project.isDisposed &&
            LicenseChecker.isLicensedOrGrace()
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        if (project.isDefault || project.isDisposed) return
        if (!LicenseChecker.isLicensedOrGrace()) return
        subscribeOnceForBalloon(project)
        ProjectLanguageDetector.rescan(project)
    }

    /**
     * Wire a one-shot subscription that fires the result balloon and
     * immediately disconnects. Protects against click spam by verifying the
     * `AtomicBoolean` latch inside the callback — the MessageBus will deliver
     * to every live subscriber even after we call disconnect, and we don't
     * want two balloons for one scan when two clicks raced a single scan
     * completion.
     */
    private fun subscribeOnceForBalloon(project: Project) {
        val connection = project.messageBus.connect()
        val fired =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        val listener =
            ProjectLanguageDetectionListener { outcome ->
                if (!fired.compareAndSet(false, true)) return@ProjectLanguageDetectionListener
                runCatchingPreservingCancellation { connection.disconnect() }
                    .onFailure { exception ->
                        LOG.debug("Rescan balloon connection disconnect failed", exception)
                    }
                notifyUser(project, outcome)
            }
        connection.subscribe(ProjectLanguageDetectionListener.TOPIC, listener)
    }

    private fun notifyUser(
        project: Project,
        outcome: ScanOutcome,
    ) {
        if (project.isDisposed) return
        val label = humanLabelFor(outcome)
        runCatchingPreservingCancellation {
            NotificationGroupManager
                .getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(
                    NOTIFICATION_TITLE,
                    label,
                    NotificationType.INFORMATION,
                ).notify(project)
        }.onFailure { exception ->
            LOG.warn("Rescan balloon emit failed; cache is still warm", exception)
        }
    }

    /**
     * Resolve a [ScanOutcome] to a human-readable balloon body.
     * [ScanOutcome.Detected] maps to the registered Language `displayName`
     * (case-insensitive, raw id on lookup failure or blank displayName).
     * [ScanOutcome.Polyglot] and [ScanOutcome.Unavailable] both map to the
     * polyglot copy today — from the user's perspective "no dominant
     * language right now" reads the same whether the scan definitively
     * said polyglot or hit a transient failure, and the UI keeps a
     * single copy string rather than surfacing detector-internal
     * transience.
     *
     * Case-insensitive lookup because `Language.id` casing varies per
     * plugin (`"JAVA"` / `"kotlin"` / `"JavaScript"`) while the detector
     * always normalises to lowercase via
     * [dev.ayuislands.accent.LanguageDetectionRules].
     */
    private fun humanLabelFor(outcome: ScanOutcome): String =
        when (outcome) {
            is ScanOutcome.Detected -> displayNameFor(outcome.languageId)
            ScanOutcome.Polyglot, ScanOutcome.Unavailable -> POLYGLOT_LABEL
        }

    private fun displayNameFor(detectedId: String): String {
        val display =
            runCatchingPreservingCancellation {
                Language
                    .getRegisteredLanguages()
                    .firstOrNull { it.id.equals(detectedId, ignoreCase = true) }
                    ?.displayName
            }.onFailure { exception ->
                // DEBUG not WARN: a corrupted third-party Language
                // registry can fire on every rescan, and WARN would
                // spam idea.log. DEBUG leaves a triage breadcrumb so
                // "balloon shows raw id" reports can be diagnosed.
                LOG.debug("Language registry lookup threw for id='$detectedId'; falling back to raw id", exception)
            }.getOrNull()
        return display?.takeIf { it.isNotBlank() } ?: detectedId
    }

    companion object {
        private val LOG = logger<RescanLanguageAction>()
        private const val NOTIFICATION_GROUP_ID = "Ayu Islands"
        private const val NOTIFICATION_TITLE = "Project language re-detected"
        private const val POLYGLOT_LABEL =
            "Polyglot — no single dominant language; global accent applies"
    }
}
