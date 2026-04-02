package dev.ayuislands.onboarding

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

/**
 * Shows an actionable welcome notification that opens the [OnboardingDialog]
 * for a richer preset-selection experience.
 */
object OnboardingNotifier {
    private const val NOTIFICATION_GROUP = "Ayu Islands"

    /** Display the trial welcome notification with a single action to open the onboarding wizard. */
    fun showWelcome(project: Project) {
        NotificationGroupManager
            .getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                "Welcome to Ayu Islands Premium",
                "Your 30-day trial is active. Open the setup wizard to pick a glow + font preset in one click.",
                NotificationType.INFORMATION,
            ).addAction(
                object : NotificationAction("Open Setup Wizard") {
                    override fun actionPerformed(
                        event: AnActionEvent,
                        notification: Notification,
                    ) {
                        notification.expire()
                        OnboardingDialog(project).show()
                    }
                },
            ).notify(project)
    }
}
