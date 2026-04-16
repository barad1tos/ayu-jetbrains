package dev.ayuislands.settings.mappings

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.LanguageDetectionRules
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
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
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

    /**
     * Captured [JPanel] backing the detected-language proportions status row.
     * Children are cleared and rebuilt on every [reset] and every pending-change
     * event via [populateProportionsPanel]. Each child is a [JBLabel] carrying
     * the language-specific icon from
     * [LanguageDetectionRules.iconForLanguageId] (or the info icon for the
     * polyglot state). Null before [buildGroup] has populated it.
     *
     * Replaces the pre-icon `JEditorPane` with `comment()`-style gray italic
     * text that was too faint to read on the premium settings row.
     */
    private var proportionsPanel: JPanel? = null

    init {
        configureTables()
    }

    fun buildGroup(
        panel: Panel,
        contextProject: Project?,
    ) {
        parentProject = contextProject
        // Defense-in-depth: StartupActivity warms the detector cache off-EDT on
        // project open, but Settings might be opened before that coroutine lands
        // (race window) or the cache might have been invalidated mid-session.
        // Kick a warmup now on the EDT path — the detector's own EDT bail-out
        // schedules a background scan and returns null immediately, so this is
        // zero-cost on EDT; the first paint may show the polyglot copy but the
        // next reset (any model edit or panel reopen) picks up the warm cache.
        contextProject?.let { ProjectLanguageDetector.dominant(it) }
        loadFromState()

        val licensed = LicenseChecker.isLicensedOrGrace()

        val segmentedBar = buildSegmentedBar()
        cardPanel.add(decorateTable(projectTable, projectActions(licensed)), CARD_PROJECTS)
        cardPanel.add(decorateTable(languageTable, languageActions(licensed)), CARD_LANGUAGES)
        // No fixed preferredSize: the AutoSizingTable drives height via
        // getPreferredScrollableViewportSize (row count × row height) and every column
        // auto-packs to the wider of header/content on every model change. AUTO_RESIZE_LAST_COLUMN
        // then lets the last column absorb any remaining width when the containing panel is wider
        // than the sum of packed widths, or shrink (via horizontal scroll) when narrower.

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
                row {
                    val gap = JBUI.scale(PROPORTIONS_ENTRY_GAP_PX)
                    val panel =
                        JPanel(FlowLayout(FlowLayout.LEFT, gap, 0)).apply {
                            isOpaque = false
                            border = null
                        }
                    proportionsPanel = panel
                    populateProportionsPanel(panel)
                    cell(panel)
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
        // Keep the proportions status line in sync with the pending-change surface so
        // any override add / edit / delete re-reads from the detector cache. Phase 29's
        // rescan action will reuse this same channel (invalidate + fireChanged) without
        // introducing a new MessageBus Topic.
        addPendingChangeListener { proportionsPanel?.let { populateProportionsPanel(it) } }
    }

    // Settings panel lifecycle (isModified / apply / reset)

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

        // Re-apply the committed mapping set via resolver → applicator → swap-cache sync.
        // Keep the focus-swap cache consistent so the next WINDOW_ACTIVATED event evaluates
        // against the color actually showing on screen right now.
        AyuVariant.detect()?.let { variant ->
            val project = parentProject ?: ProjectManager.getInstance().openProjects.firstOrNull()
            val hex = AccentResolver.resolve(project, variant)
            AccentApplicator.apply(hex)
            ProjectAccentSwapService.getInstance().notifyExternalApply(hex)
        }
        storedProjects = projectModel.snapshot().map { ProjectMapping(it.canonicalPath, it.displayName, it.hex) }
        storedLanguages = languageModel.snapshot().map { LanguageMapping(it.languageId, it.displayName, it.hex) }
        fireChanged()
    }

    fun reset() {
        projectModel.replaceAll(storedProjects.map { ProjectMapping(it.canonicalPath, it.displayName, it.hex) })
        languageModel.replaceAll(storedLanguages.map { LanguageMapping(it.languageId, it.displayName, it.hex) })
        proportionsPanel?.let { populateProportionsPanel(it) }
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
                    // LanguageMapping.init enforces lowercase at construction; ProjectLanguageDetector
                    // returns lowercase ids. Case-sensitive equality is sufficient — plain `==`
                    // stays consistent with LanguageMappingsTableModel.containsLanguage.
                    languages.firstOrNull { it.languageId == languageId }?.let { return it.hex }
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
                // See resolvePending: both sides are lowercase by invariant, no compensator needed.
                if (dominant != null && languages.any { it.languageId == dominant }) {
                    return AccentResolver.Source.LANGUAGE_OVERRIDE
                }
            }
        }
        return AccentResolver.Source.GLOBAL
    }

    // Internals: pending-model resolver + UI wiring helpers

    /**
     * Compute the current status-line text by reading from the detector's warm cache.
     *
     * Returns the fixed polyglot copy ([POLYGLOT_COPY]) when:
     *  - [parentProject] is null (Settings opened with no focused project)
     *  - [ProjectLanguageDetector.proportions] returns null (cold cache, polyglot
     *    no-winner verdict, or legacy-SDK fallback path — all leave weightsCache empty)
     *  - [LanguageDetectionRules.pickTopLanguagesForDisplay] produces a blank string
     *    (all-zero weights or markup-only below-threshold — same visual as polyglot)
     *
     * Otherwise returns [DETECTED_PREFIX] concatenated with the HTML-escaped formatter
     * output. Escaping goes through [StringUtil.escapeXmlEntities] because the
     * surrounding `JEditorPane.text` is parsed as `text/html` by the IntelliJ UI DSL;
     * a third-party language plugin could theoretically register a display name
     * containing markup (T-26-01).
     *
     * O(1) HashMap probe plus a bounded formatter pass; safe to call on EDT.
     */
    private fun computeProportionsText(): String {
        val project = parentProject ?: return POLYGLOT_COPY
        val weights = ProjectLanguageDetector.proportions(project) ?: return POLYGLOT_COPY
        val rendered = LanguageDetectionRules.pickTopLanguagesForDisplay(weights)
        if (rendered.isBlank()) return POLYGLOT_COPY
        return DETECTED_PREFIX + StringUtil.escapeXmlEntities(rendered)
    }

    /**
     * Rebuild the status-panel children from the detector's warm cache. Clears
     * existing children first so stale entries from a previous project focus
     * don't leak. Called from the inline panel construction inside [buildGroup]
     * (initial paint), from [reset] (Settings re-opens or Cancel), and from the
     * pending-change listener registered in [buildGroup] (override add / edit /
     * delete path via [fireChanged]).
     *
     * Two render paths:
     *  - **Icon row** (normal): one [JBLabel] per [LanguageDetectionRules.DisplayEntry]
     *    with the IDE-platform icon from [LanguageDetectionRules.iconForLanguageId]
     *    (null for the "other" bucket — JBLabel without an icon renders as
     *    text-only, same font). Labels carry the raw display name + percentage.
     *    Because `JBLabel.text` is plain-text by default (NOT `<html>`-prefixed),
     *    any pathological language display name renders literally — no HTML
     *    interpretation, no need for escape-xml.
     *  - **Polyglot row** (null / empty entries): one [JBLabel] with
     *    `AllIcons.General.Information` + [POLYGLOT_COPY]. Visually distinct
     *    from the icon row so the user instantly sees "no single dominant".
     */
    private fun populateProportionsPanel(panel: JPanel) {
        panel.removeAll()
        val project = parentProject
        val entries =
            if (project == null) {
                emptyList()
            } else {
                val weights = ProjectLanguageDetector.proportions(project)
                if (weights == null) emptyList() else LanguageDetectionRules.pickDisplayEntries(weights)
            }
        val subdued = UIUtil.getContextHelpForeground()
        if (entries.isEmpty()) {
            panel.add(
                JBLabel(POLYGLOT_COPY, AllIcons.General.Information, SwingConstants.LEADING).apply {
                    foreground = subdued
                },
            )
        } else {
            panel.add(JBLabel(PROPORTIONS_PREFIX).apply { foreground = subdued })
            entries.forEachIndexed { index, entry ->
                if (index > 0) {
                    panel.add(
                        JBLabel(PROPORTIONS_SEPARATOR.toString()).apply { foreground = subdued },
                    )
                }
                val icon = entry.id?.let { LanguageDetectionRules.iconForLanguageId(it) }
                panel.add(
                    JBLabel("${entry.percent}%", icon, SwingConstants.LEADING).apply {
                        foreground = subdued
                        // Hover affordance: icon alone can be unfamiliar for less-common
                        // languages, so the display name surfaces in the tooltip.
                        // toolTipText is plain-text unless it starts with <html>, so any
                        // pathological Language.displayName renders literally — no HTML
                        // interpretation, consistent with the label-text safety story.
                        toolTipText = entry.label
                    },
                )
            }
        }
        panel.revalidate()
        panel.repaint()
    }

    /**
     * Populate the pending table models from the persisted [AccentMappingsSettings]. Public
     * [buildGroup] calls this during UI setup; exposed to tests so `resolvePending` and
     * `sourcePending` can be exercised without inflating the full Swing tree.
     */
    @org.jetbrains.annotations.TestOnly
    internal fun loadFromStateForTest() {
        loadFromState()
    }

    /**
     * Builder-level seam for the proportions status line: lets wiring tests bind a
     * fake focused project without spinning up a full Swing panel. Mirrors the
     * pattern established by [loadFromStateForTest].
     */
    @org.jetbrains.annotations.TestOnly
    internal fun setParentProjectForTest(project: Project?) {
        parentProject = project
    }

    /**
     * Builder-level seam returning the exact text [buildGroup] would render for the
     * current [parentProject] + detector cache state. Used by
     * `OverridesGroupBuilderProportionsTest` to assert every user-visible render
     * state without instantiating the Swing tree.
     */
    @org.jetbrains.annotations.TestOnly
    internal fun currentProportionsTextForTest(): String = computeProportionsText()

    /**
     * Builder-level seam: lazily builds the proportions panel on first call (so
     * wiring tests can bypass `buildGroup`), repopulates from the current
     * [parentProject] + detector cache, and returns `(icon, text)` pairs for
     * each child in layout order.
     *
     * Combines refresh + read into one seam so test call sites stay terse and
     * the public surface of [OverridesGroupBuilder] stays under the detekt
     * `TooManyFunctions` threshold — refresh helpers were inlined into [reset]
     * and the pending-change listener instead of adding dedicated private
     * functions.
     */
    @org.jetbrains.annotations.TestOnly
    internal fun proportionsPanelLabelsForTest(): List<Triple<Icon?, String, String?>> {
        val panel = proportionsPanel ?: JPanel().also { proportionsPanel = it }
        populateProportionsPanel(panel)
        return panel.components.filterIsInstance<JBLabel>().map { label ->
            Triple(label.icon, label.text, label.toolTipText)
        }
    }

    private fun loadFromState() {
        val state = AccentMappingsSettingsAccess.stateFor()
        // Per-row resilience: ProjectMapping / LanguageMapping have require(...) invariants at
        // construction (valid #RRGGBB hex, lowercase language id, non-blank canonical path).
        // If the persisted XML contains even one malformed row — hand-edited, imported, legacy —
        // a blanket map { ... } would throw during Settings panel construction and the whole
        // Overrides UI would refuse to open. Worse, the user would have no way to fix it
        // because the UI that shows the bad row is exactly the UI that's broken. Drop malformed
        // rows individually with a warn log; users can re-add them from the surviving UI.
        val projects =
            state.projectAccents.mapNotNull { (path, hex) ->
                runCatching {
                    ProjectMapping(
                        canonicalPath = path,
                        displayName = state.projectDisplayNames[path] ?: File(path).name,
                        hex = hex,
                    )
                }.onFailure { exception ->
                    LOG.warn(
                        "Dropping malformed project override row (path='$path', hex='$hex'): ${exception.message}",
                    )
                }.getOrNull()
            }
        val languages =
            state.languageAccents.mapNotNull { (id, hex) ->
                runCatching {
                    val displayName =
                        runCatching {
                            com.intellij.lang.Language
                                .findLanguageByID(id)
                                ?.displayName
                        }.onFailure { exception ->
                            // Display-name lookup fell through to the raw id fallback — log at
                            // DEBUG because the row is still usable; surface the platform API
                            // regression only for users who enable debug logging.
                            LOG.debug("Language display-name lookup failed for id='$id'", exception)
                        }.getOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?: id
                    LanguageMapping(
                        languageId = id,
                        displayName = displayName,
                        hex = hex,
                    )
                }.onFailure { exception ->
                    LOG.warn(
                        "Dropping malformed language override row (id='$id', hex='$hex'): ${exception.message}",
                    )
                }.getOrNull()
            }
        projectModel.replaceAll(projects)
        languageModel.replaceAll(languages)
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
            JPanel(FlowLayout(FlowLayout.LEADING, BAR_HORIZONTAL_GAP, BAR_VERTICAL_GAP)).apply {
                isOpaque = false
                add(projectsRadio)
                add(languagesRadio)
            }
        // Retain strong reference so actions survive focus changes
        bar.putClientProperty("ayu.overrides.group", group)
        return bar
    }

    private fun configureTables() {
        for (table in listOf(projectTable, languageTable)) {
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            table.rowHeight = TABLE_ROW_HEIGHT
            table.setShowGrid(false)
        }
        projectTable.getColumnModel().getColumn(ProjectMappingsTableModel.COLUMN_COLOR).apply {
            cellRenderer = RoundedSwatchRenderer()
        }
        projectTable.getColumnModel().getColumn(ProjectMappingsTableModel.COLUMN_PROJECT).apply {
            cellRenderer = DimOrphanRenderer { row -> projectModel.isOrphan(row) }
        }
        projectTable.getColumnModel().getColumn(ProjectMappingsTableModel.COLUMN_PATH).apply {
            cellRenderer = DimOrphanRenderer { row -> projectModel.isOrphan(row) }
        }
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
                        override fun actionPerformed(event: AnActionEvent) {
                            pinCurrentProject()
                        }

                        override fun update(event: AnActionEvent) {
                            // Inlined pin-eligibility gate: the action is enabled only when a
                            // focused, non-default, non-disposed project has a canonical key
                            // that isn't already pinned. Kept inline rather than as a helper so
                            // the file stays under detekt's TooManyFunctions threshold.
                            val project = parentProject
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
            remove = { removeSelectedRow(projectTable, projectModel::remove) },
            addEnabled = { licensed },
            editEnabled = { licensed && projectTable.selectedRow >= 0 },
            removeEnabled = { projectTable.selectedRow >= 0 },
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
            remove = { removeSelectedRow(languageTable, languageModel::remove) },
            addEnabled = { licensed },
            editEnabled = { licensed && languageTable.selectedRow >= 0 },
            removeEnabled = { languageTable.selectedRow >= 0 },
            extraActions = emptyList(),
        )

    // Project-table actions: add / edit / remove + pin-current

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

    /**
     * Generic remove-selected-row flow shared by the project and language tables.
     * Shares the JBTable selection probe, the model-agnostic remove call, and the
     * pending-change notification.
     */
    private fun removeSelectedRow(
        table: JBTable,
        remove: (Int) -> Unit,
    ) {
        val row = table.selectedRow.takeIf { it >= 0 } ?: return
        remove(row)
        fireChanged()
    }

    // Language-table actions: add / edit / remove

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

    /**
     * Generic edit-color flow shared by project and language tables. Takes accessors
     * for the model-specific lookup and update so the Project- and Language- specific
     * callers collapse to a single call with closures capturing their model/table —
     * sharing the JBTable selection probe, the modal dialog wiring, and the
     * pending-change notification.
     */
    private inline fun <M> editSelectedColor(
        table: JBTable,
        rowAt: (Int) -> M?,
        hex: (M) -> String,
        displayName: (M) -> String,
        updateHex: (Int, String) -> Unit,
    ) {
        val row = table.selectedRow.takeIf { it >= 0 } ?: return
        val mapping = rowAt(row) ?: return
        val dialog = EditAccentColorDialog(parentProject, hex(mapping), displayName(mapping))
        if (!dialog.showAndGet()) return
        updateHex(row, dialog.resultHex)
        fireChanged()
    }

    companion object {
        private val LOG = logger<OverridesGroupBuilder>()
        private const val CARD_PROJECTS = "projects"
        private const val CARD_LANGUAGES = "languages"
        private const val TABLE_ROW_HEIGHT = 24
        private const val BAR_HORIZONTAL_GAP = 4
        private const val BAR_VERTICAL_GAP = 0

        /**
         * Horizontal gap between entries in the proportions status row.
         * Scaled via [com.intellij.util.ui.JBUI.scale] for HiDPI displays.
         * 12 px at 1x matches the rhythm of other Settings rows in the plugin.
         */
        private const val PROPORTIONS_ENTRY_GAP_PX: Int = 12

        /**
         * Fixed status-line copy rendered under Per-Language Accent Pins when the
         * detector has no warm weights for the focused project — cache is cold, the
         * most recent scan produced no dominant winner, or the legacy SDK / module
         * fallback path was used (no weights to proportion). Exact string is locked
         * per phase-26 D-05 / ROADMAP success criterion. NOT i18n'd, NOT
         * user-configurable. The em-dash is U+2014, not a hyphen.
         */
        const val POLYGLOT_COPY: String =
            "Polyglot — no single dominant language; global accent applies"

        /**
         * Prefix for the detected-language proportions line. Paired with the output
         * of [LanguageDetectionRules.pickTopLanguagesForDisplay]. The trailing space
         * is part of the locked copy — do not trim.
         */
        const val DETECTED_PREFIX: String = "Detected in this project: "

        /**
         * Header rendered in front of the icon-row proportions layout under the
         * overrides table. Kept terse ("Detected:") because the row sits below
         * the overrides table where context is already established, and the
         * full "Languages detected" was verbose for a subdued helper-text row.
         */
        const val PROPORTIONS_PREFIX: String = "Detected:"

        /**
         * Middle-dot (U+00B7) separator glued to the end of every entry except
         * the last. Matches the visual separator style used in the font preset
         * panel (e.g. `Maple Mono · 14pt · Regular · 1.0× · ligatures`).
         */
        const val PROPORTIONS_SEPARATOR: Char = '·'
    }

    /**
     * [JBTable] that sizes its viewport to the current row count (clamped to
     * [MIN_VISIBLE_ROWS]..[MAX_VISIBLE_ROWS]) and auto-packs every column to the wider of
     * header / cell content on every model change. With [AUTO_RESIZE_LAST_COLUMN] Swing then
     * lets the last column absorb any extra width when the containing panel is wider than
     * the sum of packed widths, and shrink (horizontal scroll) when narrower.
     */
    private class AutoSizingTable(
        model: TableModel,
    ) : JBTable(model) {
        init {
            autoResizeMode = AUTO_RESIZE_LAST_COLUMN
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

    private data class TableActions(
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
            table: JTable,
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
