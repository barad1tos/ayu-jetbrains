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
    private val isLicensed: () -> Boolean,
    private val onChanged: () -> Unit,
    private val prompts: MappingPrompts = MappingPrompts(),
) {
    fun decorateProjectTable(showPinAction: Boolean): JComponent =
        decorateTable(projectTable, projectActions(showPinAction))

    fun decorateLanguageTable(): JComponent = decorateTable(languageTable, languageActions())

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

    private fun projectActions(showPinAction: Boolean): TableActions {
        val extras: List<AnAction> =
            if (showPinAction) {
                listOf(
                    object : AnAction(
                        "Pin Current Project",
                        "Add the current project with the global accent",
                        AllIcons.Actions.PinTab,
                    ) {
                        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

                        override fun actionPerformed(event: AnActionEvent) {
                            if (!isLicensed()) return
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
                                isLicensed() &&
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
            add = { mutateIfLicensed(::addProjectMapping) },
            edit = {
                mutateIfLicensed {
                    editSelectedColor(
                        table = projectTable,
                        rowAt = projectModel::rowAt,
                        hex = ProjectMapping::hex,
                        displayName = ProjectMapping::displayName,
                        updateHex = projectModel::updateHex,
                    )
                }
            },
            remove = {
                mutateIfLicensed {
                    removeSelectedRow(projectTable, projectModel::remove)
                }
            },
            addEnabled = isLicensed,
            editEnabled = { isLicensed() && projectTable.selectedRow >= 0 },
            removeEnabled = { isLicensed() && projectTable.selectedRow >= 0 },
            extraActions = extras,
        )
    }

    private fun languageActions(): TableActions =
        TableActions(
            add = { mutateIfLicensed(::addLanguageMapping) },
            edit = {
                mutateIfLicensed {
                    editSelectedColor(
                        table = languageTable,
                        rowAt = languageModel::rowAt,
                        hex = LanguageMapping::hex,
                        displayName = LanguageMapping::displayName,
                        updateHex = languageModel::updateHex,
                    )
                }
            },
            remove = {
                mutateIfLicensed {
                    removeSelectedRow(languageTable, languageModel::remove)
                }
            },
            addEnabled = isLicensed,
            editEnabled = { isLicensed() && languageTable.selectedRow >= 0 },
            removeEnabled = { isLicensed() && languageTable.selectedRow >= 0 },
            extraActions = emptyList(),
        )

    private fun mutateIfLicensed(mutation: () -> Unit) {
        if (isLicensed()) {
            mutation()
        }
    }

    private fun addProjectMapping() {
        val excluded = projectModel.snapshot().map { it.canonicalPath }.toSet()
        val mapping = prompts.projectMapping(parentProjectProvider(), excluded) ?: return
        val index = projectModel.add(mapping)
        projectTable.selectionModel.setSelectionInterval(index, index)
        onChanged()
    }

    private fun addLanguageMapping() {
        val excluded = languageModel.snapshot().map { it.languageId }.toSet()
        val mapping = prompts.languageMapping(parentProjectProvider(), excluded) ?: return
        val index = languageModel.add(mapping)
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
        val updatedHex = prompts.accentHex(parentProjectProvider(), hex(mapping), displayName(mapping)) ?: return
        updateHex(row, updatedHex)
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

internal class MappingPrompts(
    val projectMapping: (Project?, Set<String>) -> ProjectMapping? = ::promptProjectMapping,
    val languageMapping: (Project?, Set<String>) -> LanguageMapping? = ::promptLanguageMapping,
    val accentHex: (Project?, String, String) -> String? = ::promptAccentHex,
)

private fun promptProjectMapping(
    parent: Project?,
    excludedPaths: Set<String>,
): ProjectMapping? {
    val dialog = AddProjectMappingDialog(parent, excludedPaths)
    if (!dialog.showAndGet()) return null
    val path = dialog.resultCanonicalPath ?: return null
    val hex = dialog.resultHex ?: return null
    val name = dialog.resultDisplayName ?: File(path).name
    return ProjectMapping(path, name, hex)
}

private fun promptLanguageMapping(
    parent: Project?,
    excludedLanguageIds: Set<String>,
): LanguageMapping? {
    val dialog = AddLanguageMappingDialog(parent, excludedLanguageIds)
    if (!dialog.showAndGet()) return null
    val languageId = dialog.resultLanguageId ?: return null
    val hex = dialog.resultHex ?: return null
    val name = dialog.resultDisplayName ?: languageId
    return LanguageMapping(languageId, name, hex)
}

private fun promptAccentHex(
    parent: Project?,
    initialHex: String,
    displayName: String,
): String? {
    val dialog = EditAccentColorDialog(parent, initialHex, displayName)
    if (!dialog.showAndGet()) return null
    return dialog.resultHex
}
