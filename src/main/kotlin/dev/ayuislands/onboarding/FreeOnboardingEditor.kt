package dev.ayuislands.onboarding

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBScrollPane
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/** Editor wrapper that displays the [FreeOnboardingPanel] wizard. */
internal class FreeOnboardingEditor(
    project: Project,
    private val virtualFile: VirtualFile,
) : UserDataHolderBase(),
    FileEditor {
    private val panel = FreeOnboardingPanel(project, virtualFile)
    private val scrollPane =
        JBScrollPane(panel).apply {
            border = null
            viewportBorder = null
        }

    override fun getComponent(): JComponent = scrollPane

    override fun getPreferredFocusedComponent(): JComponent = panel

    override fun getName(): String = "Ayu Islands"

    override fun getFile(): VirtualFile = virtualFile

    override fun setState(state: FileEditorState) = Unit

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun dispose() = Unit
}
