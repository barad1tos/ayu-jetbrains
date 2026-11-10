package dev.ayuislands.studio

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import dev.ayuislands.rotation.RuntimeFault
import dev.ayuislands.settings.AyuIslandsConfigurable

/** Native fault reporting belongs to the application owner, independently of an open Settings view. */
internal object StudioNotifications {
    private const val NOTIFICATION_GROUP = "Ayu Islands"

    private val log = logger<StudioNotifications>()

    fun runtime(
        failure: RuntimeFault,
        retry: () -> Unit,
    ) {
        val message =
            when (failure) {
                RuntimeFault.SessionCircuitBreaker ->
                    "Accent rotation paused after repeated failures. Retry to resume with your current settings."
                is RuntimeFault.DurableRecovery ->
                    "Ayu Islands could not finish updating the appearance. Retry to safely finish the pending change."
            }
        log.warn("Studio runtime requires attention: $failure")
        NotificationGroupManager
            .getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("Appearance update paused", message, NotificationType.WARNING)
            .addAction(NotificationAction.createSimpleExpiring("Retry", retry))
            .notify(null)
    }

    fun storage() {
        NotificationGroupManager
            .getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                "Ayu Islands settings are unavailable",
                "The saved settings could not be opened. Open Ayu Islands settings to retry.",
                NotificationType.WARNING,
            ).addAction(
                NotificationAction.createSimpleExpiring("Open settings") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(null, AyuIslandsConfigurable::class.java)
                },
            ).notify(null)
    }

    fun metadata(retry: () -> Unit) {
        NotificationGroupManager
            .getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                "Ayu Islands could not save this change",
                "Your saved settings are preserved. Retry to finish the pending update.",
                NotificationType.WARNING,
            ).addAction(NotificationAction.createSimpleExpiring("Retry", retry))
            .notify(null)
    }
}
