package dev.ayuislands

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.whatsnew.WhatsNewLauncher

internal object UpdateNotifier {
    private val LOG = logger<UpdateNotifier>()
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

        // Tab supersedes balloon when the version ships rich What's New content.
        // Falls through to balloon for patches without a manifest. The launcher's
        // own gating (lastWhatsNewShownVersion + manifest existence) decides
        // eligibility — we just defer to it.
        if (WhatsNewLauncher.openIfEligible(project, currentVersion)) return

        val notes = changeNotes(currentVersion)
        if (notes == null) {
            // Release shipped without a manifest AND without a RELEASE_NOTES
            // entry — the entire update goes silent. Log INFO so a future
            // "users didn't see the notification for X" report has a paper
            // trail; the missing-entry bug isn't invisible.
            LOG.info("Ayu Islands updated to $currentVersion — no manifest and no balloon notes; nothing to show")
            return
        }

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
            "2.6.0" to
                "<ul>" +
                "<li>Peacock parity — per-project / per-language chrome tinting, native to JetBrains</li>" +
                "<li>Editor color scheme auto-binds on theme change</li>" +
                "<li>Fix glow lingering after switching to a non-Ayu theme</li>" +
                "<li>Fix sticky-line panel transparency in the editor</li>" +
                "<li>Fix path widget breadcrumb tinting on IntelliJ 2026.1 New UI</li>" +
                "</ul>",
            "2.5.3" to
                "<ul>" +
                "<li>Rescan project language on demand — new Rescan link and Tools menu action</li>" +
                "<li>Bug fixes</li>" +
                "</ul>",
            "2.5.2" to
                "<ul>" +
                "<li>Per-Language Accent Pins show a live language-proportions breakdown</li>" +
                "<li>Fix accent rotation overriding pinned project colors</li>" +
                "<li>Fix Settings showing the wrong project with two or more windows open</li>" +
                "</ul>",
            "2.5.0" to
                "<ul>" +
                "<li>Pin an accent color to a specific project</li>" +
                "<li>Pin an accent color to a programming language</li>" +
                "<li>Recent projects populate the add-project dialog</li>" +
                "<li>First-launch release showcase with captioned screenshots</li>" +
                "</ul>",
            "2.4.2" to
                "<ul>" +
                "<li>Install, delete, or reinstall fonts from Settings</li>" +
                "<li>Consent dialog before any font file changes</li>" +
                "<li>Three-state font status detection</li>" +
                "</ul>",
            "2.4.1" to
                "<ul>" +
                "<li>Fix font install retry on corrupted cache</li>" +
                "</ul>",
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
