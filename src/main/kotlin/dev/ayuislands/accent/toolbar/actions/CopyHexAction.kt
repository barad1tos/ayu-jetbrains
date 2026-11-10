package dev.ayuislands.accent.toolbar.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.toolbar.QuickSwitcherCommands
import java.awt.datatransfer.StringSelection

/** Reads the current accepted color for this invocation or the popup's captured target. */
class CopyHexAction internal constructor(
    private val captured: QuickSwitcherCommands?,
) : DumbAwareAction("Copy Hex", "Copy the current accent hex to the clipboard", null) {
    constructor() : this(null)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = AccentContext.isQuickSwitcherActive()
    }

    override fun actionPerformed(event: AnActionEvent) {
        val commands = captured ?: QuickSwitcherCommands.capture() ?: return
        val hex = commands.accent() ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(hex))
    }
}
