package dev.ayuislands.studio.prototype

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/** Opens an isolated design preview; the action retains no project or dialog state. */
internal class AyuStudioPreviewAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(event: AnActionEvent) {
        StudioPrototypeDialog(event.project).show()
    }
}
