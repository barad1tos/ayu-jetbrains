package dev.ayuislands

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.SettingsBadges
import dev.ayuislands.whatsnew.WhatsNewLauncher

internal object UpdateNotifier {
    private val LOG = logger<UpdateNotifier>()

    fun showIfUpdated(project: Project) {
        val descriptor =
            AyuPlugin.findLoadedPlugin(AyuPlugin.ID)
                ?: return
        val currentVersion = descriptor.version
        val state = AyuIslandsSettings.getInstance().state
        val lastSeen = state.lastSeenVersion
        val nowMs = System.currentTimeMillis()

        if (lastSeen == currentVersion) {
            SettingsBadges.expireIfDue(state, nowMs)
            SettingsBadges.armExpiry(state, nowMs)
            return
        }

        state.lastSeenVersion = currentVersion

        // Skip notification on the first installation (no previous version).
        // Everything is new to a fresh install, so no settings badge shows;
        // onboarding and What's New own the first-run experience.
        if (lastSeen == null) {
            SettingsBadges.seedAllAcknowledged(state)
            return
        }

        SettingsBadges.pruneStaleIds(state)
        SettingsBadges.expireIfDue(state, nowMs)
        SettingsBadges.armExpiry(state, nowMs)

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
            "2.8.0" to
                releaseNotes(
                    "[Paid] External chrome tint and project-icon accents extend automatic color across more projects",
                    "[Paid] Solid glow can render around the full island or only along its side edges",
                    "[Paid] Typing-responsive ECG glow adds a configurable perimeter trace and live preview",
                    "[Free] New-setting wayfinding points directly to recently added controls",
                    "[Fix] Commit panel accessibility no longer triggers forbidden read-lock errors",
                ),
            "2.7.8" to
                releaseNotes(
                    "[Fix] Failed What's New or onboarding opens retry instead of blocking the session",
                    "[Fix] Interrupted accent applies recover on the next window focus",
                    "[Fix] Accent rotation stops with a notification when applies keep failing",
                    "[Fix] Settings overrides preview no longer schedules background language scans",
                    "[Fix] Language scans cancel on project close and skip stale results",
                ),
            "2.7.7" to
                releaseNotes(
                    "[Fix] Language diagnostics show detected breakdowns, manual state, and the winning accent source",
                    "[Fix] Mapped languages can win in balanced polyglot projects instead of falling back globally",
                    "[Fix] Swift and Noctule Swift aliases, predefined symbols, and nil fallback use Ayu colors",
                    "[Fix] Project fallback accents reuse warmed detector verdicts for matching diagnostics",
                    "[Fix] Font installs require the exact catalog entry approved in the consent dialog",
                ),
            "2.7.6" to
                releaseNotes(
                    "[Fix] Language override diagnostics keep full details in the compact resolution chain",
                    "[Fix] Groovy/Jenkinsfile colors cover DSL calls, named args, Jenkins bindings, and chains",
                    "[Fix] Jenkinsfile unresolved references no longer render as dark text or dotted underlines",
                    "[Fix] Focus-based accent refreshes now use IntelliJ's write-safe dispatch path",
                    "[Fix] Chrome tinting no longer colors shared IDE dividers around the editor",
                ),
            "2.7.5" to
                releaseNotes(
                    "[Paid] Accent Source status-bar widget shows why the current accent won",
                    "[Paid] Language override controls now show dominance and fallback details",
                    "[Free] Quick-Switcher diagnostics and Groovy/Jenkinsfile colors are expanded",
                    "[Fix] Widget buttons and Settings Apply now preserve chrome tinting choices",
                    "[Fix] Language fallback precedence and pinned-tab Glow alignment are corrected",
                ),
            "2.7.3" to
                releaseNotes(
                    "Syntax preview highlighting now updates reliably when switching presets and readability options",
                ),
            "2.7.2" to
                releaseNotes(
                    "Pro accent pins can now drive Ayu integrations on non-Ayu themes",
                    "Glow, CodeGlance Pro, and Indent Rainbow can follow external Ayu accents",
                    "Quick-Switcher now supports external themes and persists external accent actions",
                    "External accents can use Material Theme, IDE accent, or manual fallback colors",
                ),
            "2.7.1" to
                releaseNotes(
                    "Locked premium Settings controls are now visible as greyed-out previews",
                    "VCS and Font presets now use polished editor-style previews",
                    "Accent Settings preview now stays synced with the selected color",
                    "Tool windows keep auto-fit sizing after layout and theme refreshes",
                    "Optional .ignore plugin files now use dedicated Ayu colors with a Plugins-tab opt-out",
                ),
            "2.7.0" to
                releaseNotes(
                    "Expanded semantic highlighting across 26+ language families",
                    "Syntax presets: Whisper, Ambient, Neon, and Cyberpunk",
                    "Matching tags, YAML/HCL, Syntax Custom, and chrome tinting fixes",
                    "Custom per-language syntax tuning and readability controls for Pro",
                ),
            "2.6.4" to
                "<ul>" +
                "<li>Quick-Switcher Widget — chip in the main toolbar with variant + accent grid (free)</li>" +
                "<li>Right-click context menu on the chip exposes the same quick actions</li>" +
                "<li>Premium rows in the popup unlock when license is active or in trial</li>" +
                "<li>Hide the widget anytime from Settings → Ayu Islands → General</li>" +
                "</ul>",
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

    private fun releaseNotes(vararg items: String): String =
        items.joinToString(
            prefix = "<ul>",
            separator = "",
            postfix = "</ul>",
            transform = { "<li>$it</li>" },
        )
}
