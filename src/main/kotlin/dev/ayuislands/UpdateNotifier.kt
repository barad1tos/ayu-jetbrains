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
            "2.4.0" to
                "<ul>" +
                "<li>Onboarding wizard with preset cards and accent picker</li>" +
                "<li>Font installation from the wizard</li>" +
                "<li>Responsive scaling for small windows</li>" +
                "<li>License and trial fixes</li>" +
                "</ul>",
            "2.3.7" to
                "<ul>" +
                "<li>Fix tool window width jumping when switching panels</li>" +
                "<li>Editor scrollbar visibility controls in Workspace settings</li>" +
                "</ul>",
            "2.3.6" to
                "<ul>" +
                "<li>Bug fixes</li>" +
                "</ul>",
            "2.3.4" to
                "<ul>" +
                "<li>Fix glow color desyncing from accent after rotation</li>" +
                "</ul>",
            "2.3.3" to
                "<ul>" +
                "<li>Fix \u201CWrite-unsafe context\u201D crash during accent rotation with Indent Rainbow</li>" +
                "<li>Rewrite Marketplace description</li>" +
                "</ul>",
            "2.3.2" to
                "<ul>" +
                "<li>[Paid] Accent rotation \u2014 automatic preset cycling or contrast-aware random</li>" +
                "<li>[Free] Shuffle UI redesign with heroic accent indicator</li>" +
                "<li>Fix trial</li>" +
                "</ul>",
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
