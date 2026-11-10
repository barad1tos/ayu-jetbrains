package dev.ayuislands.studio.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.session.StudioDraftChoice
import java.awt.Component
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel

/** Returns an explicit choice without writing, reordering or automatically selecting saved work. */
internal class StudioDraftDialog(
    records: List<StudioSessionRecord>,
    project: Project? = null,
) : DialogWrapper(project, true) {
    var choice: StudioDraftChoice = StudioDraftChoice.KeepForLater
        private set

    private val saved =
        JBList(records).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            accessibleContext.accessibleName = "Saved Ayu Islands work"
            cellRenderer = SavedWorkRenderer()
        }
    private val discard =
        object : AbstractAction("Discard") {
            override fun actionPerformed(event: ActionEvent) {
                val selected = saved.selectedValue ?: return
                choice = StudioDraftChoice.Discard(selected.id)
                close(OK_EXIT_CODE)
            }
        }
    internal val recoveryContent: JComponent =
        panel {
            row { label("Choose saved work to restore, discard it, or keep it for later.") }
            row { cell(JBScrollPane(saved)).align(Align.FILL).resizableColumn() }.resizableRow()
        }
    internal val recoveryButtons: List<JButton>
        get() =
            listOf(
                requireNotNull(getButton(okAction)),
                requireNotNull(getButton(discard)),
                requireNotNull(getButton(cancelAction)),
            )

    init {
        title = "Saved Ayu Islands Work"
        setOKButtonText("Restore")
        setCancelButtonText("Keep for Later")
        saved.addListSelectionListener { updateReadiness() }
        init()
        updateReadiness()
    }

    override fun createCenterPanel(): JComponent = recoveryContent

    override fun createActions(): Array<Action> = arrayOf(okAction, discard, cancelAction)

    override fun getPreferredFocusedComponent(): JComponent = saved

    override fun doOKAction() {
        val selected = saved.selectedValue ?: return
        choice = StudioDraftChoice.Restore(selected.id)
        super.doOKAction()
    }

    private fun updateReadiness() {
        val hasSelection = saved.selectedValue != null
        isOKActionEnabled = hasSelection
        discard.isEnabled = hasSelection
    }

    private class SavedWorkRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is StudioSessionRecord) {
                val draft = value.work.drafts.single { it.id == value.work.selected }
                val name = draft.sourceName ?: "Current appearance"
                text = "Saved work ${index + 1}: $name"
            }
            return component
        }
    }
}
