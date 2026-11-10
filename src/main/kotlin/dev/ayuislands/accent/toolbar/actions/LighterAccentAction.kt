package dev.ayuislands.accent.toolbar.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.color.AccentHsl
import dev.ayuislands.accent.toolbar.QuickSwitcherCommands
import dev.ayuislands.licensing.LicenseChecker

/** Requests one existing HSL lightness step against the captured accepted target. */
class LighterAccentAction internal constructor(
    private val captured: QuickSwitcherCommands?,
) : DumbAwareAction("Lighter", "Lighten the current accent by 5%", null) {
    constructor() : this(null)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible =
            AccentContext.isQuickSwitcherActive() &&
            LicenseChecker.isLicensedOrGrace()
    }

    override fun actionPerformed(event: AnActionEvent) {
        if (!LicenseChecker.isLicensedOrGrace()) return
        val commands = captured ?: QuickSwitcherCommands.capture() ?: return
        val current = commands.accent()?.let(AccentHex::of) ?: return
        commands.color(AccentHsl.lighten(current).value)
    }
}
