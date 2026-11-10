package dev.ayuislands.accent.toolbar.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.toolbar.QuickSwitcherCommands
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.studio.session.StudioQuickCommand

/** Pins only the captured project through the same ordered mapping edit as Settings. */
class PinAccentAction internal constructor(
    private val captured: QuickSwitcherCommands?,
) : DumbAwareAction("Pin Accent", "Pin the current accent to the focused project", null) {
    constructor() : this(null)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible =
            AccentContext.detect() is AccentContext.Ayu &&
            LicenseChecker.isLicensedOrGrace()
    }

    override fun actionPerformed(event: AnActionEvent) {
        if (!LicenseChecker.isLicensedOrGrace()) return
        val commands = captured ?: QuickSwitcherCommands.capture() ?: return
        if (commands.context !is AccentContext.Ayu || commands.project == null) return
        val hex = commands.accent() ?: return
        commands.submit(StudioQuickCommand.Pin(hex))
    }
}
