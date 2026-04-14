package dev.ayuislands.settings.mappings

import javax.swing.table.AbstractTableModel

/**
 * Row for the Language Overrides table. [languageId] is the lowercase canonical id
 * used as the map key (e.g. "kotlin", "python"). [displayName] is the human-readable
 * label pulled from `Language.displayName` at add time and kept as-is in storage.
 *
 * `languageId` is validated at construction to be non-blank and all-lowercase — the
 * KDoc convention that `AddLanguageMappingDialog` relies on now also holds at the
 * type level, so [containsLanguage] can use a plain case-sensitive `==` instead of
 * a defensive `ignoreCase = true` compensator.
 */
data class LanguageMapping(
    val languageId: String,
    val displayName: String,
    val hex: String,
) {
    init {
        require(languageId.isNotBlank()) { "languageId must not be blank" }
        require(languageId == languageId.lowercase()) {
            "languageId must be lowercase, got: '$languageId'"
        }
        require(HEX_REGEX.matches(hex)) { "hex must be #RRGGBB, got: '$hex'" }
    }

    companion object {
        private val HEX_REGEX = Regex("^#[0-9A-Fa-f]{6}$")
    }
}

/**
 * Table model for the Language Overrides section. Two columns: Color (hex),
 * Language (display name). Matches the Project model shape for symmetric UI code.
 */
class LanguageMappingsTableModel : AbstractTableModel() {
    private val rows: MutableList<LanguageMapping> = mutableListOf()

    fun replaceAll(mappings: Collection<LanguageMapping>) {
        rows.clear()
        rows.addAll(mappings)
        fireTableDataChanged()
    }

    fun snapshot(): List<LanguageMapping> = rows.toList()

    fun add(mapping: LanguageMapping): Int {
        rows += mapping
        val index = rows.size - 1
        fireTableRowsInserted(index, index)
        return index
    }

    fun remove(row: Int) {
        if (row !in rows.indices) return
        rows.removeAt(row)
        fireTableRowsDeleted(row, row)
    }

    fun updateHex(
        row: Int,
        hex: String,
    ) {
        if (row !in rows.indices) return
        rows[row] = rows[row].copy(hex = hex)
        fireTableRowsUpdated(row, row)
    }

    fun rowAt(index: Int): LanguageMapping? = rows.getOrNull(index)

    /**
     * Case-sensitive match: [LanguageMapping.languageId] is guaranteed lowercase by its init
     * block, and call sites (`AddLanguageMappingDialog.doOKAction`) lowercase the probe before
     * calling this. No `ignoreCase = true` compensator needed.
     */
    fun containsLanguage(languageId: String): Boolean = rows.any { it.languageId == languageId }

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = COLUMN_NAMES.size

    override fun getColumnName(column: Int): String = COLUMN_NAMES.getOrElse(column) { "" }

    override fun getColumnClass(columnIndex: Int): Class<*> = String::class.java

    override fun isCellEditable(
        rowIndex: Int,
        columnIndex: Int,
    ): Boolean = false

    override fun getValueAt(
        rowIndex: Int,
        columnIndex: Int,
    ): Any? {
        val mapping = rowAt(rowIndex) ?: return null
        return when (columnIndex) {
            COLUMN_COLOR -> mapping.hex
            COLUMN_LANGUAGE -> mapping.displayName
            else -> null
        }
    }

    companion object {
        const val COLUMN_COLOR = 0
        const val COLUMN_LANGUAGE = 1
        private val COLUMN_NAMES = listOf("Color", "Language")
    }
}
