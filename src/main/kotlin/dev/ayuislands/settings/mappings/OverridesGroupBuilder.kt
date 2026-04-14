package dev.ayuislands.settings.mappings

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.table.JBTable
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ProjectLanguageDetector
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.File
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableModel

/**
 * Builds the "Overrides" group inside the Accent settings tab. Hosts a segmented
 * toggle between Projects and Languages, each backed by its own [JBTable] and
 * [ToolbarDecorator] with add / pin-current / edit-color / remove actions.
 *
 * All mutations happen on an in-memory pending model; callers use [isModified],
 * [apply], and [reset] to participate in the usual settings lifecycle. [addPendingChangeListener]
 * lets observers (the reactive "Currently active: ..." comment) refresh on every edit.
 */
class OverridesGroupBuilder {
    private val projectModel = ProjectMappingsTableModel()
    private val languageModel = LanguageMappingsTableModel()
    private val projectTable: JBTable = AutoSizingTable(projectModel)
    private val languageTable: JBTable = AutoSizingTable(languageModel)

    private var storedProjects: List<ProjectMapping> = emptyList()
    private var storedLanguages: List<LanguageMapping> = emptyList()
    private val listeners: MutableList<Runnable> = mutableListOf()

    private val cardPanel = JPanel(CardLayout())
    private var parentProject: Project? = null

    init {
        configureProjectTable()
        configureLanguageTable()
    }

    fun buildGroup(
        panel: Panel,
        contextProject: Project?,
    ) {
        parentProject = contextProject
        loadFromState()

        val licensed = LicenseChecker.isLicensedOrGrace()

        val segmentedBar = buildSegmentedBar()
        cardPanel.add(decorateTable(projectTable, projectActions(licensed)), CARD_PROJECTS)
        cardPanel.add(decorateTable(languageTable, languageActions(licensed)), CARD_LANGUAGES)
        // No fixed preferredSize: the AutoSizingTable drives height via
        // getPreferredScrollableViewportSize (row count × row height) and columns 0..N-2
        // auto-pack to their widest cell on every model change; last column absorbs
        // remaining width via AUTO_RESIZE_LAST_COLUMN.

        val settings = AyuIslandsSettings.getInstance()
        val collapsible =
            panel.collapsibleGroup("Overrides") {
                row {
                    comment(
                        "Pin an accent color to a specific project or a programming language. " +
                            "Project overrides win over language overrides; both win over the global accent.",
                    )
                }
                row {
                    cell(segmentedBar)
                }
                row {
                    cell(cardPanel)
                        .resizableColumn()
                        .align(Align.FILL)
                }
                if (!licensed) {
                    row {
                        comment("Pro feature")
                    }
                }
            }
        collapsible.expanded = settings.state.overridesGroupExpanded
        collapsible.addExpandedListener { expanded ->
            settings.state.overridesGroupExpanded = expanded
        }
    }

    // ---- Lifecycle integration ----

    fun isModified(): Boolean {
        val currentProjects = projectModel.snapshot().toFingerprint()
        val storedProjectsFingerprint = storedProjects.toFingerprint()
        if (currentProjects != storedProjectsFingerprint) return true

        val currentLanguages = languageModel.snapshot().toLanguageFingerprint()
        val storedLanguagesFingerprint = storedLanguages.toLanguageFingerprint()
        return currentLanguages != storedLanguagesFingerprint
    }

    fun apply() {
        val state = AccentMappingsSettingsAccess.stateFor()
        state.projectAccents.clear()
        state.projectDisplayNames.clear()
        for (row in projectModel.snapshot()) {
            state.projectAccents[row.canonicalPath] = row.hex
            state.projectDisplayNames[row.canonicalPath] = row.displayName
        }
        state.languageAccents.clear()
        for (row in languageModel.snapshot()) {
            state.languageAccents[row.languageId] = row.hex
        }

        reapplyForCurrentContext()
        snapshotStored()
        fireChanged()
    }

    fun reset() {
        projectModel.replaceAll(storedProjects.map { ProjectMapping(it.canonicalPath, it.displayName, it.hex) })
        languageModel.replaceAll(storedLanguages.map { LanguageMapping(it.languageId, it.displayName, it.hex) })
        fireChanged()
    }

    fun addPendingChangeListener(runnable: Runnable) {
        listeners += runnable
    }

    /**
     * Resolve the accent hex using the **pending** (not yet applied) overrides model.
     * Falls back to [fallbackGlobalHex] when no override matches.
     */
    fun resolvePending(
        project: Project?,
        fallbackGlobalHex: String,
    ): String {
        if (project != null && !project.isDefault && !project.isDisposed) {
            AccentResolver.projectKey(project)?.let { key ->
                projectModel.snapshot().firstOrNull { it.canonicalPath == key }?.let { return it.hex }
            }
            val languages = languageModel.snapshot()
            if (languages.isNotEmpty()) {
                ProjectLanguageDetector.dominant(project)?.let { languageId ->
                    languages.firstOrNull { it.languageId.equals(languageId, ignoreCase = true) }?.let { return it.hex }
                }
            }
        }
        return fallbackGlobalHex
    }

    /**
     * Matching [AccentResolver.Source] for [project] under the **pending** overrides model.
     */
    fun sourcePending(project: Project?): AccentResolver.Source {
        if (project != null && !project.isDefault && !project.isDisposed) {
            val key = AccentResolver.projectKey(project)
            if (key != null && projectModel.snapshot().any { it.canonicalPath == key }) {
                return AccentResolver.Source.PROJECT_OVERRIDE
            }
            val languages = languageModel.snapshot()
            if (languages.isNotEmpty()) {
                val dominant = ProjectLanguageDetector.dominant(project)
                if (dominant != null && languages.any { it.languageId.equals(dominant, ignoreCase = true) }) {
                    return AccentResolver.Source.LANGUAGE_OVERRIDE
                }
            }
        }
        return AccentResolver.Source.GLOBAL
    }

    // ---- Internals ----

    private fun loadFromState() {
        val state = AccentMappingsSettingsAccess.stateFor()
        val projects =
            state.projectAccents.map { (path, hex) ->
                ProjectMapping(
                    canonicalPath = path,
                    displayName = state.projectDisplayNames[path] ?: File(path).name,
                    hex = hex,
                )
            }
        val languages =
            state.languageAccents.map { (id, hex) ->
                val displayName =
                    runCatching {
                        com.intellij.lang.Language
                            .findLanguageByID(id)
                            ?.displayName
                    }.getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?: id
                LanguageMapping(
                    languageId = id,
                    displayName = displayName,
                    hex = hex,
                )
            }
        projectModel.replaceAll(projects)
        languageModel.replaceAll(languages)
        snapshotStored()
    }

    private fun snapshotStored() {
        storedProjects = projectModel.snapshot().map { ProjectMapping(it.canonicalPath, it.displayName, it.hex) }
        storedLanguages = languageModel.snapshot().map { LanguageMapping(it.languageId, it.displayName, it.hex) }
    }

    private val fireChanged: () -> Unit = { listeners.forEach { it.run() } }

    private fun buildSegmentedBar(): JComponent {
        val projectsRadio = JRadioButton("Projects", true)
        val languagesRadio = JRadioButton("Languages", false)
        val group =
            ButtonGroup().apply {
                add(projectsRadio)
                add(languagesRadio)
            }
        projectsRadio.addActionListener { (cardPanel.layout as CardLayout).show(cardPanel, CARD_PROJECTS) }
        languagesRadio.addActionListener { (cardPanel.layout as CardLayout).show(cardPanel, CARD_LANGUAGES) }

        val bar =
            JPanel(FlowLayout(FlowLayout.LEADING, BAR_HGAP, BAR_VGAP)).apply {
                isOpaque = false
                add(projectsRadio)
                add(languagesRadio)
            }
        // Retain strong reference so actions survive focus changes
        bar.putClientProperty("ayu.overrides.group", group)
        return bar
    }

    private fun configureProjectTable() {
        projectTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        projectTable.rowHeight = TABLE_ROW_HEIGHT
        projectTable.setShowGrid(false)
        projectTable.getColumnModel().getColumn(ProjectMappingsTableModel.COLUMN_COLOR).apply {
            cellRenderer = RoundedSwatchRenderer()
        }
        projectTable.getColumnModel().getColumn(ProjectMappingsTableModel.COLUMN_PROJECT).apply {
            cellRenderer = DimOrphanRenderer { row -> projectModel.isOrphan(row) }
        }
        projectTable.getColumnModel().getColumn(ProjectMappingsTableModel.COLUMN_PATH).apply {
            cellRenderer = DimOrphanRenderer { row -> projectModel.isOrphan(row) }
        }
    }

    private fun configureLanguageTable() {
        languageTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        languageTable.rowHeight = TABLE_ROW_HEIGHT
        languageTable.setShowGrid(false)
        languageTable.getColumnModel().getColumn(LanguageMappingsTableModel.COLUMN_COLOR).apply {
            cellRenderer = RoundedSwatchRenderer()
        }
    }

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
                .setEditActionName("Edit color")
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
                        override fun actionPerformed(event: AnActionEvent) {
                            pinCurrentProject()
                        }

                        override fun update(event: AnActionEvent) {
                            event.presentation.isEnabled = canPinCurrentProject()
                        }
                    },
                )
            } else {
                emptyList()
            }

        return TableActions(
            add = { showAddProjectDialog() },
            edit = { editSelectedProjectColor() },
            remove = { removeSelectedProject() },
            addEnabled = { licensed },
            editEnabled = { licensed && projectTable.selectedRow >= 0 },
            removeEnabled = { projectTable.selectedRow >= 0 },
            extraActions = extras,
        )
    }

    private fun languageActions(licensed: Boolean): TableActions =
        TableActions(
            add = { showAddLanguageDialog() },
            edit = { editSelectedLanguageColor() },
            remove = { removeSelectedLanguage() },
            addEnabled = { licensed },
            editEnabled = { licensed && languageTable.selectedRow >= 0 },
            removeEnabled = { languageTable.selectedRow >= 0 },
            extraActions = emptyList(),
        )

    // ---- Project actions ----

    private fun showAddProjectDialog() {
        val excluded = projectModel.snapshot().map { it.canonicalPath }.toSet()
        val dialog = AddProjectMappingDialog(parentProject, excluded)
        if (!dialog.showAndGet()) return
        val path = dialog.resultCanonicalPath ?: return
        val hex = dialog.resultHex ?: return
        val name = dialog.resultDisplayName ?: File(path).name
        val index = projectModel.add(ProjectMapping(path, name, hex))
        projectTable.selectionModel.setSelectionInterval(index, index)
        fireChanged()
    }

    private fun canPinCurrentProject(): Boolean {
        val project = parentProject ?: return false
        if (project.isDefault || project.isDisposed) return false
        val key = AccentResolver.projectKey(project) ?: return false
        return !projectModel.containsPath(key)
    }

    private fun pinCurrentProject() {
        val project = parentProject ?: return
        if (project.isDefault || project.isDisposed) return
        val key = AccentResolver.projectKey(project) ?: return
        if (projectModel.containsPath(key)) return
        val variant = AyuVariant.detect() ?: AyuVariant.MIRAGE
        val hex = AyuIslandsSettings.getInstance().getAccentForVariant(variant)
        val name = project.name.takeIf { it.isNotBlank() } ?: File(key).name
        val index = projectModel.add(ProjectMapping(key, name, hex))
        projectTable.selectionModel.setSelectionInterval(index, index)
        fireChanged()
    }

    private fun editSelectedProjectColor() {
        val row = projectTable.selectedRow.takeIf { it >= 0 } ?: return
        val mapping = projectModel.rowAt(row) ?: return
        val dialog = EditAccentColorDialog(parentProject, mapping.hex, mapping.displayName)
        if (!dialog.showAndGet()) return
        projectModel.updateHex(row, dialog.resultHex)
        fireChanged()
    }

    private fun removeSelectedProject() {
        val row = projectTable.selectedRow.takeIf { it >= 0 } ?: return
        projectModel.remove(row)
        fireChanged()
    }

    // ---- Language actions ----

    private fun showAddLanguageDialog() {
        val excluded = languageModel.snapshot().map { it.languageId }.toSet()
        val dialog = AddLanguageMappingDialog(parentProject, excluded)
        if (!dialog.showAndGet()) return
        val id = dialog.resultLanguageId ?: return
        val hex = dialog.resultHex ?: return
        val name = dialog.resultDisplayName ?: id
        val index = languageModel.add(LanguageMapping(id, name, hex))
        languageTable.selectionModel.setSelectionInterval(index, index)
        fireChanged()
    }

    private fun editSelectedLanguageColor() {
        val row = languageTable.selectedRow.takeIf { it >= 0 } ?: return
        val mapping = languageModel.rowAt(row) ?: return
        val dialog = EditAccentColorDialog(parentProject, mapping.hex, mapping.displayName)
        if (!dialog.showAndGet()) return
        languageModel.updateHex(row, dialog.resultHex)
        fireChanged()
    }

    private fun removeSelectedLanguage() {
        val row = languageTable.selectedRow.takeIf { it >= 0 } ?: return
        languageModel.remove(row)
        fireChanged()
    }

    // ---- Apply re-render ----

    private fun reapplyForCurrentContext() {
        val variant = AyuVariant.detect() ?: return
        val project = parentProject ?: ProjectManager.getInstance().openProjects.firstOrNull()
        val hex = AccentResolver.resolve(project, variant)
        AccentApplicator.apply(hex)
        // Keep the focus-swap cache consistent so the next WINDOW_ACTIVATED event
        // evaluates against the color actually showing on screen right now.
        ProjectAccentSwapService.getInstance().notifyExternalApply(hex)
    }

    companion object {
        private const val CARD_PROJECTS = "projects"
        private const val CARD_LANGUAGES = "languages"
        private const val TABLE_ROW_HEIGHT = 24
        private const val BAR_HGAP = 4
        private const val BAR_VGAP = 0
    }

    /**
     * [JBTable] that sizes its viewport to the current row count (clamped to
     * [MIN_VISIBLE_ROWS]..[MAX_VISIBLE_ROWS]) and auto-packs all columns except the
     * last one to the widest header or cell content on every model change.
     * The last column is left to absorb remaining width via [AUTO_RESIZE_LAST_COLUMN].
     */
    private class AutoSizingTable(
        model: TableModel,
    ) : JBTable(model) {
        init {
            autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
            model.addTableModelListener { repack() }
        }

        override fun getPreferredScrollableViewportSize(): Dimension {
            val visibleRows = rowCount.coerceIn(MIN_VISIBLE_ROWS, MAX_VISIBLE_ROWS)
            val headerHeight = tableHeader?.preferredSize?.height ?: 0
            val bodyHeight = visibleRows * rowHeight
            // Viewport width = sum of packed column widths so the surrounding Kotlin UI DSL
            // panel (Align.FILL) grows to show every column fully before kicking in horizontal
            // scrolling. Floor to MIN_VIEWPORT_WIDTH so an empty table still looks reasonable
            // on first render (before the TableModelListener has fired a pack pass).
            val summedWidth = (0 until columnCount).sumOf { columnModel.getColumn(it).preferredWidth }
            val width = maxOf(summedWidth, MIN_VIEWPORT_WIDTH)
            return Dimension(width, bodyHeight + headerHeight)
        }

        /**
         * Packs every column (including the last) to the widest of header + cell content.
         * [AUTO_RESIZE_LAST_COLUMN] then lets the last column expand when the containing
         * panel exceeds the sum, and lets it shrink (horizontal scroll) when the panel is
         * narrower.
         */
        fun repack() {
            for (index in 0 until columnCount) {
                packColumn(index)
            }
            revalidate()
            repaint()
        }

        private fun packColumn(columnIndex: Int) {
            val column = columnModel.getColumn(columnIndex)
            val headerWidth =
                tableHeader
                    ?.defaultRenderer
                    ?.getTableCellRendererComponent(
                        this,
                        column.headerValue,
                        false,
                        false,
                        -1,
                        columnIndex,
                    )?.preferredSize
                    ?.width
                    ?: COLUMN_MIN_WIDTH
            var width = maxOf(COLUMN_MIN_WIDTH, headerWidth + PACK_PADDING)
            for (row in 0 until rowCount) {
                val renderer = getCellRenderer(row, columnIndex)
                val comp = prepareRenderer(renderer, row, columnIndex)
                width = maxOf(width, comp.preferredSize.width + PACK_PADDING)
            }
            column.preferredWidth = width.coerceAtMost(COLUMN_MAX_WIDTH)
        }

        companion object {
            private const val MIN_VISIBLE_ROWS = 2
            private const val MAX_VISIBLE_ROWS = 8
            private const val COLUMN_MIN_WIDTH = 60
            private const val COLUMN_MAX_WIDTH = 600
            private const val PACK_PADDING = 16
            private const val MIN_VIEWPORT_WIDTH = 520
        }
    }

    private class TableActions(
        val add: () -> Unit,
        val edit: () -> Unit,
        val remove: () -> Unit,
        val addEnabled: () -> Boolean,
        val editEnabled: () -> Boolean,
        val removeEnabled: () -> Boolean,
        val extraActions: List<AnAction>,
    )

    private class DimOrphanRenderer(
        private val orphanProbe: (Int) -> Boolean,
    ) : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: javax.swing.JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val orphan = orphanProbe(row)
            if (orphan) {
                foreground = javax.swing.UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
                toolTipText = "Path no longer exists on disk"
            } else {
                toolTipText = null
            }
            return this
        }
    }
}

/**
 * Isolated state access wrapper so test code can substitute a synthetic state
 * without pulling in the full [AccentMappingsSettings] service graph.
 */
private object AccentMappingsSettingsAccess {
    fun stateFor(): AccentMappingsState = AccentMappingsSettings.getInstance().state
}

private fun List<ProjectMapping>.toFingerprint(): Set<Triple<String, String, String>> =
    map { Triple(it.canonicalPath, it.displayName, it.hex) }.toSet()

private fun List<LanguageMapping>.toLanguageFingerprint(): Set<Triple<String, String, String>> =
    map { Triple(it.languageId, it.displayName, it.hex) }.toSet()
