package dev.ayuislands.whatsnew

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/** Editor wrapper that displays the [WhatsNewPanel] showcase. */
internal class WhatsNewEditor(
    project: Project,
    private val virtualFile: VirtualFile,
) : UserDataHolderBase(),
    FileEditor {
    private val panel = WhatsNewPanel(project)

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = panel

    override fun getName(): String = "What's New"

    override fun getFile(): VirtualFile = virtualFile

    override fun setState(state: FileEditorState) = Unit

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun dispose() {
        // Drop AncestorListener / ComponentListener and clear ContentScaler refs
        // so closing or reopening the tab does not leak labels, gaps, or the
        // panel itself via the listener chain.
        panel.dispose()
    }
}
