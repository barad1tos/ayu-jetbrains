package dev.ayuislands.settings.mappings

import com.intellij.ide.RecentProjectListActionProvider
import com.intellij.ide.ReopenProjectAction
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.io.File
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel

/** Row model used by the recent-projects list inside [AddProjectMappingDialog]. */
internal data class RecentProjectRow(
    val canonicalPath: String,
    val displayName: String,
)

/**
 * Dialog for adding a project → accent mapping. Offers a path field with a folder
 * browser and (when available) a list of recent projects pre-filtered to paths
 * not already mapped. A compact [AccentSwatchPickerRow] lets the user pick the color.
 */
class AddProjectMappingDialog(
    parent: Project?,
    private val excludedPaths: Set<String>,
    initialHex: String? = null,
) : DialogWrapper(parent, true) {
    private val pathField = TextFieldWithBrowseButton()
    private val recentList = JBList<RecentProjectRow>()
    private val swatchPicker = AccentSwatchPickerRow { selected -> resultHex = selected }

    var resultCanonicalPath: String? = null
        private set
    var resultDisplayName: String? = null
        private set
    var resultHex: String? = initialHex
        private set

    init {
        title = "Add Project Override"
        swatchPicker.selectedHex = initialHex

        pathField.addBrowseFolderListener(
            parent,
            FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Select Project Folder"),
        )

        val recentModel = DefaultListModel<RecentProjectRow>()
        for (row in loadRecentProjects(excludedPaths)) recentModel.addElement(row)
        recentList.model = recentModel
        recentList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        recentList.cellRenderer = RecentProjectRenderer()
        recentList.addListSelectionListener { event ->
            if (event.valueIsAdjusting) return@addListSelectionListener
            recentList.selectedValue?.let { row -> pathField.text = row.canonicalPath }
        }

        init()
    }

    override fun createCenterPanel(): JComponent =
        panel {
            row("Path:") {
                cell(pathField)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }
            if (recentList.model.size > 0) {
                row {
                    cell(JBLabel("Or pick from recent projects:"))
                }.topGap(TopGap.SMALL)
                row {
                    cell(JBScrollPane(recentList))
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .applyToComponent { preferredSize = JBUI.size(RECENT_LIST_WIDTH, RECENT_LIST_HEIGHT) }
                }
            }
            row("Color:") {
                cell(swatchPicker)
            }.topGap(TopGap.MEDIUM)
            row {
                comment("Applied across all Ayu variants (Mirage, Dark, Light).")
            }
        }

    override fun doValidate(): ValidationInfo? {
        val rawPath = pathField.text.trim()
        if (rawPath.isEmpty()) return ValidationInfo("Enter a project path.", pathField)
        val file = File(rawPath)
        if (!runCatching { file.isDirectory }.getOrDefault(false)) {
            return ValidationInfo("Path is not an existing directory.", pathField)
        }
        val canonical =
            runCatching { file.canonicalPath }.getOrNull()
                ?: return ValidationInfo("Could not resolve path.", pathField)
        if (excludedPaths.any { it.equals(canonical, ignoreCase = true) }) {
            return ValidationInfo("This project already has an override.", pathField)
        }
        if (swatchPicker.selectedHex.isNullOrBlank()) {
            return ValidationInfo("Choose an accent color.", swatchPicker)
        }
        return null
    }

    override fun doOKAction() {
        val rawPath = pathField.text.trim()
        val canonical = runCatching { File(rawPath).canonicalPath }.getOrNull() ?: return
        resultCanonicalPath = canonical
        resultDisplayName =
            recentList.selectedValue
                ?.takeIf { it.canonicalPath == canonical }
                ?.displayName
                ?: File(canonical).name
        resultHex = swatchPicker.selectedHex
        super.doOKAction()
    }

    private fun loadRecentProjects(excluded: Set<String>): List<RecentProjectRow> =
        runCatching {
            val actions =
                RecentProjectListActionProvider
                    .getInstance()
                    .getActions(addClearListItem = false, useGroups = false)
            actions
                .filterIsInstance<ReopenProjectAction>()
                .mapNotNull { action ->
                    val canonical =
                        runCatching { File(action.projectPath).canonicalPath }.getOrNull()
                            ?: return@mapNotNull null
                    if (excluded.any { it.equals(canonical, ignoreCase = true) }) return@mapNotNull null
                    if (!runCatching { File(canonical).isDirectory }.getOrDefault(false)) return@mapNotNull null
                    val name = action.projectName?.takeIf { it.isNotBlank() } ?: File(canonical).name
                    RecentProjectRow(canonical, name)
                }.distinctBy { it.canonicalPath }
        }.getOrDefault(emptyList())

    companion object {
        private const val RECENT_LIST_WIDTH = 460
        private const val RECENT_LIST_HEIGHT = 160
    }

    private class RecentProjectRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is RecentProjectRow) {
                text = "${value.displayName}   —   ${value.canonicalPath}"
            }
            return this
        }
    }
}
