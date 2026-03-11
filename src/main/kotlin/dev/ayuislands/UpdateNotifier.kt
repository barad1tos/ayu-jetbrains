package dev.ayuislands

import com.intellij.ide.BrowserUtil
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import dev.ayuislands.settings.AyuIslandsSettings

internal object UpdateNotifier {
    private const val PLUGIN_ID = "com.ayuislands.theme"
    private const val MARKETPLACE_URL =
        "https://plugins.jetbrains.com/plugin/30373-ayu-islands"

    fun showIfUpdated(project: Project) {
        val descriptor =
            PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))
                ?: return
        val currentVersion = descriptor.version
        val state = AyuIslandsSettings.getInstance().state
        val lastSeen = state.lastSeenVersion

        if (lastSeen == currentVersion) return

        state.lastSeenVersion = currentVersion

        // Skip notification on the first installation (no previous version)
        if (lastSeen == null) return

        val notes = changeNotes(currentVersion) ?: return

        NotificationGroupManager
            .getInstance()
            .getNotificationGroup("Ayu Islands")
            .createNotification(
                "Ayu Islands updated to $currentVersion",
                notes,
                NotificationType.INFORMATION,
            ).addAction(
                NotificationAction.createSimpleExpiring(
                    "What's New",
                ) {
                    BrowserUtil.browse(MARKETPLACE_URL)
                },
            ).notify(project)
    }

    private fun changeNotes(version: String): String? = RELEASE_NOTES[version]

    private val RELEASE_NOTES =
        mapOf(
            "2.2.0" to
                "4 font presets (Whisper, Ambient, Neon, Cyberpunk) " +
                "with one-click apply. " +
                "12 accent presets + custom color picker. " +
                "Follow system appearance on macOS.",
        )
}
