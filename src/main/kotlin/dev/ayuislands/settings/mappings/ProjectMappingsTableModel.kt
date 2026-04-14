package dev.ayuislands.settings.mappings

import java.io.File
import javax.swing.table.AbstractTableModel

/**
 * Row representation for the Project Overrides table.
 *
 * Immutable `val` fields enforce invariants at construction — `canonicalPath` can't be
 * blank, and `hex` must look like `#RRGGBB`. Edits go through [copy] rather than in-place
 * mutation, so the table model's fingerprint-based isModified detection stays reliable.
 */
data class ProjectMapping(
    val canonicalPath: String,
    val displayName: String,
    val hex: String,
) {
    init {
        require(canonicalPath.isNotBlank()) { "canonicalPath must not be blank" }
        require(HEX_REGEX.matches(hex)) { "hex must be #RRGGBB, got: '$hex'" }
    }

    companion object {
        private val HEX_REGEX = Regex("^#[0-9A-Fa-f]{6}$")
    }
}

/**
 * Table model for the Project Overrides section. Three columns: Color (hex),
 * Project (display name), Path (canonical path). Rows track orphaned paths
 * via [isOrphan]; the table-level prepareRenderer hook styles them accordingly.
 */
class ProjectMappingsTableModel : AbstractTableModel() {
    private val rows: MutableList<ProjectMapping> = mutableListOf()

    fun replaceAll(mappings: Collection<ProjectMapping>) {
        rows.clear()
        rows.addAll(mappings)
        fireTableDataChanged()
    }

    fun snapshot(): List<ProjectMapping> = rows.toList()

    fun add(mapping: ProjectMapping): Int {
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

    fun rowAt(index: Int): ProjectMapping? = rows.getOrNull(index)

    fun containsPath(canonicalPath: String): Boolean = rows.any { it.canonicalPath == canonicalPath }

    fun isOrphan(row: Int): Boolean {
        val mapping = rowAt(row) ?: return false
        return !runCatching { File(mapping.canonicalPath).isDirectory }.getOrDefault(false)
    }

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
            COLUMN_PROJECT -> mapping.displayName
            COLUMN_PATH -> mapping.canonicalPath
            else -> null
        }
    }

    companion object {
        const val COLUMN_COLOR = 0
        const val COLUMN_PROJECT = 1
        const val COLUMN_PATH = 2
        private val COLUMN_NAMES = listOf("Color", "Project", "Path")
    }
}
