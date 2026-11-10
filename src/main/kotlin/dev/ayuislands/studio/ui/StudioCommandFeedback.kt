package dev.ayuislands.studio.ui

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import dev.ayuislands.settings.AyuIslandsConfigurable
import dev.ayuislands.studio.StudioService
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.session.ImmediateExecutionResult

/** Reports the shared command outcome without retaining a second command or replaying its gesture. */
internal object StudioCommandFeedback {
    fun show(
        project: Project?,
        result: ImmediateExecutionResult,
        accepted: () -> Unit = {},
    ) {
        if (result is ImmediateExecutionResult.Pending) {
            notification(
                "Appearance change needs attention",
                "Retry to finish saving or recovering the pending change.",
            ).addAction(
                NotificationAction.createSimpleExpiring("Retry") {
                    StudioService.getInstance().retryImmediate(result.operationId) { show(project, it, accepted) }
                },
            ).notify(project?.takeUnless { it.isDisposed })
            return
        }
        when (val outcome = (result as ImmediateExecutionResult.Complete).acceptance) {
            is AcceptanceResult.Accepted -> {
                accepted()
                if (outcome.limitations.isNotEmpty()) {
                    review(
                        project,
                        "Your choices were saved. Some effects are unavailable in the current IDE context.",
                    )
                }
            }
            AcceptanceResult.NoChanges -> accepted()
            is AcceptanceResult.ReviewRequired ->
                review(
                    project,
                    "Review your pending choices in Settings before applying this change.",
                )
            is AcceptanceResult.Blocked ->
                review(
                    project,
                    "This change could not be applied in the current context. Your saved choices are unchanged.",
                )
            is AcceptanceResult.NotAccepted ->
                review(
                    project,
                    "The appearance change could not be saved. Your previous choices were retained.",
                )
            is AcceptanceResult.AcceptedCleanupPending, is AcceptanceResult.RecoveryRequired ->
                review(project, "A previous appearance change needs recovery before another change can be applied.")
        }
    }

    private fun review(
        project: Project?,
        message: String,
    ) {
        notification("Review Ayu Islands settings", message)
            .addAction(
                NotificationAction.createSimpleExpiring("Review in Settings") {
                    if (project == null || !project.isDisposed) {
                        ShowSettingsUtil.getInstance().showSettingsDialog(project, AyuIslandsConfigurable::class.java)
                    }
                },
            ).notify(project?.takeUnless { it.isDisposed })
    }

    private fun notification(
        title: String,
        message: String,
    ) = NotificationGroupManager
        .getInstance()
        .getNotificationGroup("Ayu Islands")
        .createNotification(title, message, NotificationType.WARNING)
}
