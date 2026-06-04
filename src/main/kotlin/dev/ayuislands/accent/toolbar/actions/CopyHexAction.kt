package dev.ayuislands.accent.toolbar.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.licensing.LicenseChecker
import java.awt.datatransfer.StringSelection

/**
 * Copy the focused project's currently-resolved accent hex to the system
 * clipboard. Reads the hex AT INVOCATION TIME (no cached field) so a
 * programmatic burst across two windows never leaks a stale prior-window hex
 * (information-disclosure mitigation locked by `CopyHexActionTest`).
 */
class CopyHexAction : DumbAwareAction("Copy Hex", "Copy the current accent hex to the clipboard", null) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible =
            AccentContext.isQuickSwitcherActive() &&
            LicenseChecker.isLicensedOrGrace()
    }

    override fun actionPerformed(event: AnActionEvent) {
        val context = AccentContext.detectQuickSwitcher() ?: return
        val project = AccentApplicator.resolveFocusedProject()
        val hex =
            try {
                // Read at invocation time — no cached state. Avoids leaking
                // a prior window's resolved hex on burst clipboard reads.
                AccentResolver.resolve(project, context)
            } catch (exception: RuntimeException) {
                LOG.warn("CopyHex: resolve failed", exception)
                return
            }
        CopyPasteManager.getInstance().setContents(StringSelection(hex))
    }

    private companion object {
        val LOG = logger<CopyHexAction>()
    }
}
