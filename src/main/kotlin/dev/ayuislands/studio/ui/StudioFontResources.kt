package dev.ayuislands.studio.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.ToolWindowManager
import dev.ayuislands.font.FontCatalog
import dev.ayuislands.font.FontInstallConsent
import dev.ayuislands.font.FontInstaller
import dev.ayuislands.font.FontPreset
import dev.ayuislands.font.FontUninstaller
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.studio.session.StudioFontTerminal
import java.awt.datatransfer.StringSelection
import java.io.IOException

internal fun fontInstallCommand(preset: FontPreset): String? =
    FontCatalog.forPreset(preset)?.let { "brew install --cask ${it.brewCaskSlug}" }

/** Resource completion refreshes inventory independently of the draft that opened the dialog. */
internal class StudioFontResources(
    private val inventoryChanged: () -> Unit,
) {
    fun handle(
        project: Project?,
        action: StudioFontAction.Resource,
    ) {
        when (action) {
            is StudioFontAction.ManageFont -> manage(project, action)
            is StudioFontAction.CopyInstallCommand -> copyCommand(action.preset)
            is StudioFontAction.OpenInstallTerminal -> {
                if (copyCommand(action.preset)) openTerminal(project, action.terminal)
            }
        }
    }

    private fun manage(
        project: Project?,
        action: StudioFontAction.ManageFont,
    ) {
        val entry = FontCatalog.forPreset(action.preset) ?: return
        when (action.operation) {
            StudioFontOperation.INSTALL, StudioFontOperation.REINSTALL -> install(project, entry)
            StudioFontOperation.DELETE -> remove(project, entry)
        }
    }

    private fun install(
        project: Project?,
        entry: FontCatalog.Entry,
    ) {
        if (!LicenseChecker.isLicensedOrGrace()) {
            LicenseChecker.requestLicense("Unlock font installation")
            return
        }
        val consent = FontInstallConsent.confirmInstall(entry, project, compact = true) ?: return
        FontInstaller.install(entry, consent, project, applyPreset = false) { inventoryChanged() }
    }

    private fun remove(
        project: Project?,
        entry: FontCatalog.Entry,
    ) {
        val directory = FontInstaller.platformFontDir().absolutePath
        if (!FontInstallConsent.confirmUninstall(entry, project, directory)) return
        FontUninstaller.uninstall(entry.preset, project) { inventoryChanged() }
    }

    private fun copyCommand(preset: FontPreset): Boolean {
        if (!SystemInfo.isMac) return false
        val command = fontInstallCommand(preset) ?: return false
        CopyPasteManager.getInstance().setContents(StringSelection(command))
        return true
    }

    private fun openTerminal(
        project: Project?,
        terminal: StudioFontTerminal,
    ) {
        when (terminal) {
            StudioFontTerminal.BUILTIN -> {
                val window = project?.let { ToolWindowManager.getInstance(it).getToolWindow("Terminal") }
                if (window == null) LOG.warn("The font install command was copied, but the IDE terminal is unavailable")
                window?.activate(null)
            }
            StudioFontTerminal.SYSTEM ->
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        ProcessBuilder("open", "-a", "Terminal").start()
                    } catch (failure: IOException) {
                        LOG.warn("The font install command was copied, but Terminal could not be opened", failure)
                    }
                }
        }
    }

    private companion object {
        val LOG = Logger.getInstance(StudioFontResources::class.java)
    }
}
