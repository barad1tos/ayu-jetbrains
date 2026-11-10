package dev.ayuislands.settings.mappings

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.project.Project
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel

internal fun decorateMappingTable(
    table: JBTable,
    actions: MappingTableActions,
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

internal data class MappingTableActions(
    val add: () -> Unit,
    val edit: () -> Unit,
    val remove: () -> Unit,
    val addEnabled: () -> Boolean,
    val editEnabled: () -> Boolean,
    val removeEnabled: () -> Boolean,
    val extraActions: List<AnAction>,
)

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
