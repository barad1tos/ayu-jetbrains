package dev.ayuislands.studio.prototype

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.JComponent

private const val PREVIEW_WIDTH = 1040
private const val PREVIEW_HEIGHT = 660

/** Native window for the local Studio interaction model. */
internal class StudioPrototypeDialog(
    project: Project?,
) : DialogWrapper(project, true) {
    private val applyAction =
        object : DialogWrapperAction("Apply") {
            override fun doAction(event: ActionEvent) {
                studioPanel.applyPreview()
            }
        }
    private val studioPanel =
        StudioPrototypePanel(StudioPrototypeSession(isProjectAvailable = project != null), ::refreshActions)

    init {
        title = "Ayu Studio — Design Preview"
        isResizable = true
        studioPanel.preferredSize = JBUI.size(PREVIEW_WIDTH, PREVIEW_HEIGHT)
        init()
        refreshActions()
    }

    override fun createCenterPanel(): JComponent = studioPanel

    override fun getPreferredFocusedComponent(): JComponent = studioPanel.preferredFocusComponent

    override fun createActions(): Array<Action> = arrayOf(okAction, cancelAction, applyAction)

    override fun doOKAction() {
        if (studioPanel.applyPreview() == StudioPrototypeResult.APPLIED) super.doOKAction()
    }

    override fun doCancelAction() {
        studioPanel.discardPending()
        super.doCancelAction()
    }

    private fun refreshActions() {
        applyAction.isEnabled = studioPanel.canApply
    }
}
