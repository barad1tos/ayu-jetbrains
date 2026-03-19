package dev.ayuislands

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import dev.ayuislands.settings.AyuIslandsSettings

internal object UpdateNotifier {
    private const val PLUGIN_ID = "com.ayuislands.theme"

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
            ).notify(project)
    }

    private fun changeNotes(version: String): String? = RELEASE_NOTES[version]

    private val RELEASE_NOTES =
        mapOf(
            "2.3.0" to
                "<ul>" +
                "<li>Auto-fit with min/max for Project, Commit, and Git panels</li>" +
                "<li>Git panel: branches and file changes auto-resize</li>" +
                "<li>30-day premium trial \u2014 all features unlocked</li>" +
                "</ul>",
            "2.2.1" to
                "VCS modified-line colors corrected to canonical ayu palette " +
                "for Mirage and Dark variants.",
            "2.2.0" to
                "4 font presets (Whisper, Ambient, Neon, Cyberpunk) " +
                "with one-click apply. " +
                "12 accent presets + custom color picker. " +
                "Follow system appearance on macOS.",
        )
}
