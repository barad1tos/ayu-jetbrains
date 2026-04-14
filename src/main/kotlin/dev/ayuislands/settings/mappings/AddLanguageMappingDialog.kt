package dev.ayuislands.settings.mappings

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import java.awt.Component
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JList

/** Option shown in the language combo box: a stable id plus its human-readable name. */
internal data class LanguageOption(
    val id: String,
    val displayName: String,
)

/**
 * Dialog for adding a language → accent mapping. The combo box lists every
 * registered [Language] that has a [com.intellij.openapi.fileTypes.LanguageFileType]
 * association, filtered to exclude Language.ANY and already-mapped ids.
 */
class AddLanguageMappingDialog(
    parent: Project?,
    excludedLanguageIds: Set<String>,
    initialHex: String? = null,
) : DialogWrapper(parent, true) {
    private val comboBox: ComboBox<LanguageOption>
    private val swatchPicker = AccentSwatchPickerRow { selected -> resultHex = selected }

    var resultLanguageId: String? = null
        private set
    var resultDisplayName: String? = null
        private set
    var resultHex: String? = initialHex
        private set

    init {
        title = "Add Language Override"
        swatchPicker.selectedHex = initialHex

        val options = loadLanguages(excludedLanguageIds)
        comboBox =
            ComboBox(DefaultComboBoxModel(options.toTypedArray())).apply {
                renderer = LanguageRenderer()
                isSwingPopup = false
                if (options.isNotEmpty()) selectedIndex = 0
            }

        init()
    }

    override fun createCenterPanel(): JComponent =
        panel {
            row("Language:") {
                cell(comboBox)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }
            row("Color:") {
                cell(swatchPicker)
            }.topGap(TopGap.MEDIUM)
            row {
                comment("Applied when a project's dominant language matches and no project override exists.")
            }
        }

    override fun doValidate(): ValidationInfo? {
        if (comboBox.itemCount == 0) return ValidationInfo("No languages available to add.", comboBox)
        if (comboBox.selectedItem !is LanguageOption) return ValidationInfo("Choose a language.", comboBox)
        if (swatchPicker.selectedHex.isNullOrBlank()) return ValidationInfo("Choose an accent color.", swatchPicker)
        return null
    }

    override fun doOKAction() {
        val option = comboBox.selectedItem as? LanguageOption ?: return
        resultLanguageId = option.id
        resultDisplayName = option.displayName
        resultHex = swatchPicker.selectedHex
        super.doOKAction()
    }

    private fun loadLanguages(excluded: Set<String>): List<LanguageOption> {
        val fileTypes = FileTypeManager.getInstance()
        return Language
            .getRegisteredLanguages()
            .asSequence()
            .filter { it !== Language.ANY }
            .filter { it.displayName.isNotBlank() }
            .filter { runCatching { fileTypes.findFileTypeByLanguage(it) }.getOrNull() != null }
            .map { LanguageOption(it.id.lowercase(), it.displayName) }
            .distinctBy { it.id }
            .filter { option -> excluded.none { it.equals(option.id, ignoreCase = true) } }
            .sortedBy { it.displayName.lowercase() }
            .toList()
    }

    private class LanguageRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is LanguageOption) {
                text = value.displayName
            }
            return this
        }
    }
}
