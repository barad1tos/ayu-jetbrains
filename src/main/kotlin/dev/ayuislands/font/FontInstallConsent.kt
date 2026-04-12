package dev.ayuislands.font

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.util.SystemInfo
import org.jetbrains.annotations.TestOnly

/**
 * Shared consent dialogs for font install/uninstall lifecycle (D-05).
 *
 * Called from both [dev.ayuislands.onboarding.PremiumOnboardingPanel] (wizard,
 * `compact = false`) and [dev.ayuislands.settings.FontPresetPanel] (settings,
 * `compact = true`). Single source of truth for consent copy; every caller
 * reads the same path label and the same restart-effective warning so users
 * see consistent wording whether they trigger a lifecycle action from the
 * wizard or from Settings.
 *
 * The `compact` flag (D-06) shortens the message for the Settings modal
 * context where the user is already in a technical surface and the license
 * blurb / admin-rights reassurance would be noise.
 *
 * **Uninstall warning contract (D-07):** the uninstall dialog MUST include
 * the phrase "IDE restart" and the absolute platform font directory path,
 * because `java.awt.GraphicsEnvironment` has NO unregister API. A deleted
 * font file is not fully unregistered from the JVM until the next IDE
 * startup. See RESEARCH.md A3.
 */
object FontInstallConsent {
    /**
     * Show an install consent dialog. Returns `true` if the user accepts.
     *
     * @param entry the [FontCatalog.Entry] describing the font to install
     * @param project project scope for the modal; may be `null` in wizards
     *   that fire before any project window exists
     * @param compact shorter copy suitable for Settings; default is the
     *   full wizard copy with license blurb and admin-rights reassurance
     */
    fun confirmInstall(
        entry: FontCatalog.Entry,
        project: Project?,
        compact: Boolean = false,
    ): Boolean {
        val message = buildInstallMessage(entry, compact)
        return MessageDialogBuilder
            .yesNo("Install ${entry.displayName}?", message)
            .yesText("Install")
            .noText("Cancel")
            .ask(project)
    }

    /**
     * Show an uninstall consent dialog. Returns `true` if the user accepts.
     *
     * @param entry the [FontCatalog.Entry] describing the font to remove
     * @param project project scope for the modal; may be `null`
     * @param absolutePath absolute filesystem path of [FontInstaller.platformFontDir];
     *   MUST be shown verbatim in the dialog (not a symbolic label) so the
     *   user knows exactly which folder the plugin will touch
     */
    fun confirmUninstall(
        entry: FontCatalog.Entry,
        project: Project?,
        absolutePath: String,
    ): Boolean {
        val message = buildUninstallMessage(entry, absolutePath)
        return MessageDialogBuilder
            .yesNo("Remove ${entry.displayName}?", message)
            .yesText("Remove")
            .noText("Cancel")
            .asWarning()
            .ask(project)
    }

    /**
     * Human-readable label for the platform user-level font directory,
     * used in the wizard consent copy where the exact absolute path
     * would be noisy. Settings panel uses the absolute path directly
     * via [FontInstaller.platformFontDir].
     */
    fun platformFontDirLabel(): String =
        when {
            SystemInfo.isMac -> "~/Library/Fonts"
            SystemInfo.isWindows -> "%LOCALAPPDATA%\\Microsoft\\Windows\\Fonts"
            else -> "~/.local/share/fonts"
        }

    @TestOnly
    internal fun buildInstallMessage(
        entry: FontCatalog.Entry,
        compact: Boolean,
    ): String =
        if (compact) {
            "Download ${entry.displayName} (~${entry.approxSizeMb} MB) to " +
                "${platformFontDirLabel()}?"
        } else {
            "Ayu Islands will download ${entry.displayName} (~${entry.approxSizeMb} MB, " +
                "SIL Open Font License) from GitHub and install it to:\n\n" +
                "    ${platformFontDirLabel()}\n\n" +
                "This is a user-level install — no admin rights required.\n" +
                "You can remove it anytime from that folder."
        }

    @TestOnly
    internal fun buildUninstallMessage(
        entry: FontCatalog.Entry,
        absolutePath: String,
    ): String =
        "Ayu Islands will delete ${entry.displayName} files from:\n\n" +
            "    $absolutePath\n\n" +
            "The font will be removed from the editor immediately, but " +
            "full uninstall (including the JVM font registry) only takes " +
            "effect after an IDE restart."
}
