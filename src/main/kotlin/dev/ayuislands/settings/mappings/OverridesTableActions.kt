package dev.ayuislands.settings.mappings

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.JBTable
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.settings.AyuIslandsSettings
import java.awt.BorderLayout
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Owns the CRUD action wiring for the Project and Language override tables.
 *
 * Extracted from [OverridesGroupBuilder] to keep the settings coordinator
 * under detekt's `TooManyFunctions` threshold. This class handles table
 * decoration ([ToolbarDecorator] wiring), add/edit/remove actions, and
 * the "Pin Current Project" extra action — all backed by the shared
 * pending table models that [OverridesGroupBuilder] owns.
 */
internal class OverridesTableActions(
    private val projectModel: ProjectMappingsTableModel,
    private val languageModel: LanguageMappingsTableModel,
    private val projectTable: JBTable,
    private val languageTable: JBTable,
    private val parentProjectProvider: () -> Project?,
    private val onChanged: () -> Unit,
) {
    fun decorateProjectTable(licensed: Boolean): JComponent = decorateTable(projectTable, projectActions(licensed))

    fun decorateLanguageTable(licensed: Boolean): JComponent = decorateTable(languageTable, languageActions(licensed))

    private fun decorateTable(
        table: JBTable,
        actions: TableActions,
    ): JComponent {
        val decorator =
            ToolbarDecorator
                .createDecorator(table)
                .disableUpDownActions()
                .setAddAction { actions.add() }
                .setEditAction { actions.edit() }
                .setRemoveAction { actions.remove() }
                .setAddActionName("Add")
                .setEditActionName("Edit Color")
                .setRemoveActionName("Remove")
                .setAddActionUpdater { _ -> actions.addEnabled() }
                .setEditActionUpdater { _ -> actions.editEnabled() }
                .setRemoveActionUpdater { _ -> actions.removeEnabled() }

        actions.extraActions.forEach { decorator.addExtraAction(it) }
        val wrapper = JPanel(BorderLayout())
        wrapper.add(decorator.createPanel(), BorderLayout.CENTER)
        return wrapper
    }

    private fun projectActions(licensed: Boolean): TableActions {
        val extras: List<AnAction> =
            if (licensed) {
                listOf(
                    object : AnAction(
                        "Pin Current Project",
                        "Add the current project with the global accent",
                        AllIcons.Actions.PinTab,
                    ) {
                        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

                        override fun actionPerformed(event: AnActionEvent) {
                            val project = parentProjectProvider() ?: return
                            if (project.isDefault || project.isDisposed) return
                            val key = AccentResolver.projectKey(project) ?: return
                            if (projectModel.containsPath(key)) return
                            val variant = AyuVariant.detect() ?: AyuVariant.MIRAGE
                            val hex = AyuIslandsSettings.getInstance().getAccentForVariant(variant)
                            val name = project.name.takeIf { it.isNotBlank() } ?: File(key).name
                            val index = projectModel.add(ProjectMapping(key, name, hex))
                            projectTable.selectionModel.setSelectionInterval(index, index)
                            onChanged()
                        }

                        override fun update(event: AnActionEvent) {
                            val project = parentProjectProvider()
                            event.presentation.isEnabled =
                                project != null &&
                                !project.isDefault &&
                                !project.isDisposed &&
                                AccentResolver.projectKey(project)?.let { key ->
                                    !projectModel.containsPath(key)
                                } == true
                        }
                    },
                )
            } else {
                emptyList()
            }

        return TableActions(
            add = { showAddProjectDialog() },
            edit = {
                editSelectedColor(
                    table = projectTable,
                    rowAt = projectModel::rowAt,
                    hex = ProjectMapping::hex,
                    displayName = ProjectMapping::displayName,
                    updateHex = projectModel::updateHex,
                )
            },
            remove = {
                if (licensed) {
                    removeSelectedRow(projectTable, projectModel::remove)
                }
            },
            addEnabled = { licensed },
            editEnabled = { licensed && projectTable.selectedRow >= 0 },
            removeEnabled = { licensed && projectTable.selectedRow >= 0 },
            extraActions = extras,
        )
    }

    private fun languageActions(licensed: Boolean): TableActions =
        TableActions(
            add = { showAddLanguageDialog() },
            edit = {
                editSelectedColor(
                    table = languageTable,
                    rowAt = languageModel::rowAt,
                    hex = LanguageMapping::hex,
                    displayName = LanguageMapping::displayName,
                    updateHex = languageModel::updateHex,
                )
            },
            remove = {
                if (licensed) {
                    removeSelectedRow(languageTable, languageModel::remove)
                }
            },
            addEnabled = { licensed },
            editEnabled = { licensed && languageTable.selectedRow >= 0 },
            removeEnabled = { licensed && languageTable.selectedRow >= 0 },
            extraActions = emptyList(),
        )

    private fun showAddProjectDialog() {
        val excluded = projectModel.snapshot().map { it.canonicalPath }.toSet()
        val dialog = AddProjectMappingDialog(parentProjectProvider(), excluded)
        if (!dialog.showAndGet()) return
        val path = dialog.resultCanonicalPath ?: return
        val hex = dialog.resultHex ?: return
        val name = dialog.resultDisplayName ?: File(path).name
        val index = projectModel.add(ProjectMapping(path, name, hex))
        projectTable.selectionModel.setSelectionInterval(index, index)
        onChanged()
    }

    private fun showAddLanguageDialog() {
        val excluded = languageModel.snapshot().map { it.languageId }.toSet()
        val dialog = AddLanguageMappingDialog(parentProjectProvider(), excluded)
        if (!dialog.showAndGet()) return
        val id = dialog.resultLanguageId ?: return
        val hex = dialog.resultHex ?: return
        val name = dialog.resultDisplayName ?: id
        val index = languageModel.add(LanguageMapping(id, name, hex))
        languageTable.selectionModel.setSelectionInterval(index, index)
        onChanged()
    }

    private inline fun <M> editSelectedColor(
        table: JBTable,
        rowAt: (Int) -> M?,
        hex: (M) -> String,
        displayName: (M) -> String,
        updateHex: (Int, String) -> Unit,
    ) {
        val row = table.selectedRow.takeIf { it >= 0 } ?: return
        val mapping = rowAt(row) ?: return
        val dialog = EditAccentColorDialog(parentProjectProvider(), hex(mapping), displayName(mapping))
        if (!dialog.showAndGet()) return
        updateHex(row, dialog.resultHex)
        onChanged()
    }

    private fun removeSelectedRow(
        table: JBTable,
        remove: (Int) -> Unit,
    ) {
        val row = table.selectedRow.takeIf { it >= 0 } ?: return
        remove(row)
        onChanged()
    }

    private data class TableActions(
        val add: () -> Unit,
        val edit: () -> Unit,
        val remove: () -> Unit,
        val addEnabled: () -> Boolean,
        val editEnabled: () -> Boolean,
        val removeEnabled: () -> Boolean,
        val extraActions: List<AnAction>,
    )
}
