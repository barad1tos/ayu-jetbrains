@file:Suppress("DialogTitleCapitalization")

package dev.ayuislands.onboarding

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent

/**
 * Modeless dialog wrapping [OnboardingPanel] for the welcome wizard.
 *
 * Replaces the previous notification-only onboarding with a richer UI
 * that opens instantly (no EDT freeze from FileEditorManager).
 */
internal class OnboardingDialog(
    private val dialogProject: Project,
) : DialogWrapper(dialogProject, true) {
    init {
        title = "Welcome to Ayu Islands"
        isModal = false
        setResizable(false)
        init()
    }

    override fun createCenterPanel(): JComponent {
        val scrollPane = JBScrollPane(OnboardingPanel(dialogProject) { close(OK_EXIT_CODE) })
        scrollPane.preferredSize = Dimension(JBUI.scale(DIALOG_WIDTH), JBUI.scale(DIALOG_HEIGHT))
        return scrollPane
    }

    override fun createActions(): Array<Action> = emptyArray()

    companion object {
        private const val DIALOG_WIDTH = 740
        private const val DIALOG_HEIGHT = 600
    }
}
