package dev.ayuislands.accent.toolbar.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAwareAction
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.rethrowIfCancelled
import dev.ayuislands.accent.toolbar.QuickSwitcherCommands
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.rotation.ContrastAwareColorGenerator

/** Reuses the rotation palette generator and the shared Studio command admission. */
class RandomAccentAction internal constructor(
    private val captured: QuickSwitcherCommands?,
) : DumbAwareAction("Random Accent", "Pick a random readable accent", null) {
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
        val variant = (commands.context as? AccentContext.Ayu)?.ayuVariant ?: AyuVariant.MIRAGE
        try {
            commands.color(ContrastAwareColorGenerator.generate(variant))
        } catch (failure: RuntimeException) {
            failure.rethrowIfCancelled()
            logger<RandomAccentAction>().warn("Quick Switcher could not generate an accent", failure)
        }
    }
}
