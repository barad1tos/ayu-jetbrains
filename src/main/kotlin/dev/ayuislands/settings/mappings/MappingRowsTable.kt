package dev.ayuislands.settings.mappings

import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

/** Native table identity and view preferences survive replacement of its read-only raw rows. */
internal class MappingRowsTable(
    kind: MappingsTable,
) : JBTable(MappingRowsModel(kind)) {
    private val rowsModel: MappingRowsModel
        get() = model as MappingRowsModel

    init {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        rowHeight = JBUI.scale(ROW_HEIGHT)
        setShowGrid(false)
        autoResizeMode = AUTO_RESIZE_ALL_COLUMNS
        setDefaultRenderer(String::class.java, MappingRenderer(rowsModel, kind))
        minimumSize = Dimension(0, 0)
    }

    fun selectedKey(): String? =
        selectedRow.takeIf { it >= 0 }?.let { rowsModel.rowAt(convertRowIndexToModel(it))?.key }

    fun render(
        rows: List<MappingRow>,
        observations: MappingsObservations,
    ) {
        val key = selectedKey()
        val scroll = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, this) as? JScrollPane
        val position = scroll?.viewport?.viewPosition
        rowsModel.replace(rows, observations)
        val selected = rows.indexOfFirst { it.key == key }
        if (selected >= 0) {
            val viewIndex = convertRowIndexToView(selected)
            setRowSelectionInterval(viewIndex, viewIndex)
        }
        if (position != null) scroll.viewport.viewPosition = position
    }

    override fun getPreferredScrollableViewportSize(): Dimension =
        Dimension(0, rowCount.coerceIn(MIN_VISIBLE_ROWS, MAX_VISIBLE_ROWS) * rowHeight)

    private companion object {
        const val ROW_HEIGHT = 24
        const val MIN_VISIBLE_ROWS = 2
        const val MAX_VISIBLE_ROWS = 8
    }
}

/** Uses the existing table column facts without routing raw presentation through validated modal DTOs. */
private class MappingRowsModel(
    private val kind: MappingsTable,
) : AbstractTableModel() {
    private var rows: List<MappingRow> = emptyList()
    var observations = MappingsObservations()
        private set
    private val columns =
        when (kind) {
            MappingsTable.PROJECTS -> ProjectMappingsTableModel.COLUMN_NAMES
            MappingsTable.LANGUAGES -> LanguageMappingsTableModel.COLUMN_NAMES
        }

    fun replace(
        next: List<MappingRow>,
        observed: MappingsObservations,
    ) {
        rows = next
        observations = observed
        fireTableDataChanged()
    }

    fun rowAt(index: Int): MappingRow? = rows.getOrNull(index)

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns.getOrElse(column) { "" }

    override fun getColumnClass(columnIndex: Int): Class<*> = String::class.java

    override fun getValueAt(
        rowIndex: Int,
        columnIndex: Int,
    ): Any? {
        val mapping = rowAt(rowIndex) ?: return null
        return when (columnIndex) {
            ProjectMappingsTableModel.COLUMN_COLOR -> {
                mapping.hex
            }

            ProjectMappingsTableModel.COLUMN_PROJECT -> {
                mapping.displayName ?: when (kind) {
                    MappingsTable.PROJECTS -> mapping.key.substringAfterLast('/').ifEmpty { mapping.key }
                    MappingsTable.LANGUAGES -> observations.languageNames[mapping.key] ?: mapping.key
                }
            }

            ProjectMappingsTableModel.COLUMN_PATH -> {
                mapping.key
            }

            else -> {
                null
            }
        }
    }
}

private class MappingRenderer(
    private val rows: MappingRowsModel,
    private val kind: MappingsTable,
) : TableCellRenderer {
    private val label = DefaultTableCellRenderer()
    private val swatch = RoundedSwatchRenderer()

    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int,
    ): Component {
        val raw = value?.toString().orEmpty()
        val modelColumn = table.convertColumnIndexToModel(column)
        val mapping = rows.rowAt(table.convertRowIndexToModel(row))
        val colorCell = modelColumn == ProjectMappingsTableModel.COLUMN_COLOR
        val renderer = if (colorCell && RoundedSwatchRenderer.safeDecodeColor(raw) != null) swatch else label
        val literal = if (renderer === label) " $raw" else raw
        val cell = renderer.getTableCellRendererComponent(table, literal, isSelected, hasFocus, row, column)
        val orphan = kind == MappingsTable.PROJECTS && mapping?.key in rows.observations.orphanProjectKeys
        renderer.icon =
            if (kind == MappingsTable.LANGUAGES && !colorCell) rows.observations.languageIcons[mapping?.key] else null
        if (orphan && !isSelected) renderer.foreground = UIUtil.getLabelDisabledForeground()
        (cell as JComponent).toolTipText =
            "${table.model.getColumnName(modelColumn)}: $raw" + if (orphan) " — Path no longer exists on disk" else ""
        cell.accessibleContext.accessibleName = raw
        return cell
    }
}
