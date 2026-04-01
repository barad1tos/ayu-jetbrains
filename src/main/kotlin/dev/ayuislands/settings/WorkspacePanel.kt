@file:Suppress("DialogTitleCapitalization")

package dev.ayuislands.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.dsl.builder.CollapsibleRow
import com.intellij.ui.dsl.builder.Panel
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.commitpanel.CommitPanelAutoFitManager
import dev.ayuislands.editor.EditorScrollbarManager
import dev.ayuislands.gitpanel.GitPanelAutoFitManager
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.projectview.ProjectViewScrollbarManager
import dev.ayuislands.toolwindow.AutoFitCalculator
import java.awt.Color
import java.awt.Component
import java.util.Locale
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.UIManager

private const val EDITOR_TITLE = "Editor"
private const val PROJECT_VIEW_TITLE = "Project View"
private const val COMMIT_PANEL_TITLE = "Commit Panel"
private const val GIT_PANEL_TITLE = "Git Panel"

/** Workspace tab: tool window layout tweaks (Project View, Commit Panel, Git Panel). */
class WorkspacePanel : AyuIslandsSettingsPanel {
    private var pendingHideEditorVScrollbar = false
    private var storedHideEditorVScrollbar = false
    private var pendingHideEditorHScrollbar = false
    private var storedHideEditorHScrollbar = false

    private var hideEditorVScrollbarCheckbox: JCheckBox? = null
    private var hideEditorHScrollbarCheckbox: JCheckBox? = null

    private var pendingHideRootPath = false
    private var storedHideRootPath = false
    private var pendingHideHScrollbar = false
    private var storedHideHScrollbar = false

    private var hideRootPathCheckbox: JCheckBox? = null
    private var hideHScrollbarCheckbox: JCheckBox? = null

    private val projectWidth = WidthModeUiState()
    private val commitWidth = WidthModeUiState()
    private val gitWidth = WidthModeUiState()

    private var editorGroup: CollapsibleRow? = null
    private var projectViewGroup: CollapsibleRow? = null
    private var commitPanelGroup: CollapsibleRow? = null
    private var gitPanelGroup: CollapsibleRow? = null

    private var suppressListeners = false

    override fun buildPanel(
        panel: Panel,
        variant: AyuVariant,
    ) {
        val state = AyuIslandsSettings.getInstance().state
        val licensed = LicenseChecker.isLicensedOrGrace()

        loadCheckboxPair(state.hideEditorVScrollbar) { s, p ->
            storedHideEditorVScrollbar = s
            pendingHideEditorVScrollbar = p
        }
        loadCheckboxPair(state.hideEditorHScrollbar) { s, p ->
            storedHideEditorHScrollbar = s
            pendingHideEditorHScrollbar = p
        }
        loadCheckboxPair(state.hideProjectRootPath) { s, p ->
            storedHideRootPath = s
            pendingHideRootPath = p
        }
        loadCheckboxPair(state.hideProjectViewHScrollbar) { s, p ->
            storedHideHScrollbar = s
            pendingHideHScrollbar = p
        }

        projectWidth.state.load(
            PanelWidthMode.fromString(state.projectPanelWidthMode),
            state.autoFitMaxWidth,
            state.projectPanelFixedWidth,
            state.projectPanelAutoFitMinWidth,
        )
        commitWidth.state.load(
            PanelWidthMode.fromString(state.commitPanelWidthMode),
            state.autoFitCommitMaxWidth,
            state.commitPanelFixedWidth,
            state.commitPanelAutoFitMinWidth,
        )
        gitWidth.state.load(
            PanelWidthMode.fromString(state.gitPanelWidthMode),
            state.gitPanelAutoFitMaxWidth,
            state.gitPanelFixedWidth,
            state.gitPanelAutoFitMinWidth,
        )

        panel.row { comment("Customize tool window width and Project View display options.") }

        editorGroup =
            panel.collapsibleGroup(EDITOR_TITLE) {
                hideEditorVScrollbarCheckbox =
                    buildCheckboxRow(
                        "Hide vertical scrollbar",
                        "Remove the vertical scrollbar from the editor gutter",
                        pendingHideEditorVScrollbar,
                        licensed,
                    ) { pendingHideEditorVScrollbar = it }
                hideEditorHScrollbarCheckbox =
                    buildCheckboxRow(
                        "Hide horizontal scrollbar",
                        "Remove the bottom scrollbar from the editor",
                        pendingHideEditorHScrollbar,
                        licensed,
                    ) { pendingHideEditorHScrollbar = it }
            }
        editorGroup?.expanded = state.workspaceEditorExpanded
        editorGroup?.addExpandedListener { state.workspaceEditorExpanded = it }

        projectViewGroup =
            panel.collapsibleGroup(PROJECT_VIEW_TITLE) {
                hideRootPathCheckbox =
                    buildCheckboxRow(
                        "Hide filesystem path",
                        "Remove the directory path shown next to the project name",
                        pendingHideRootPath,
                        licensed,
                    ) { pendingHideRootPath = it }
                hideHScrollbarCheckbox =
                    buildCheckboxRow(
                        "Hide horizontal scrollbar",
                        "Remove the bottom scrollbar from the Project tool window",
                        pendingHideHScrollbar,
                        licensed,
                    ) { pendingHideHScrollbar = it }
                separator()
                buildWidthModeGroup(
                    this,
                    WidthModeGroupConfig(
                        projectWidth,
                        AutoFitCalculator.MIN_PROJECT_AUTOFIT_WIDTH,
                        licensed,
                        showMinSpinner = true,
                    ) {
                        updateGroupTitle(projectViewGroup, PROJECT_VIEW_TITLE, projectWidth.state)
                    },
                )
            }
        projectViewGroup?.expanded = state.workspaceProjectViewExpanded
        projectViewGroup?.addExpandedListener { state.workspaceProjectViewExpanded = it }
        updateGroupTitle(projectViewGroup, PROJECT_VIEW_TITLE, projectWidth.state)

        commitPanelGroup =
            panel.collapsibleGroup(COMMIT_PANEL_TITLE) {
                buildWidthModeGroup(
                    this,
                    WidthModeGroupConfig(
                        commitWidth,
                        AutoFitCalculator.MIN_COMMIT_AUTOFIT_WIDTH,
                        licensed,
                        showMinSpinner = true,
                    ) {
                        updateGroupTitle(commitPanelGroup, COMMIT_PANEL_TITLE, commitWidth.state)
                    },
                )
            }
        commitPanelGroup?.expanded = state.workspaceCommitPanelExpanded
        commitPanelGroup?.addExpandedListener { state.workspaceCommitPanelExpanded = it }
        updateGroupTitle(commitPanelGroup, COMMIT_PANEL_TITLE, commitWidth.state)

        gitPanelGroup =
            panel.collapsibleGroup(GIT_PANEL_TITLE) {
                buildWidthModeGroup(
                    this,
                    WidthModeGroupConfig(
                        gitWidth,
                        AutoFitCalculator.MIN_GIT_AUTOFIT_WIDTH,
                        licensed,
                        showMinSpinner = true,
                    ) {
                        updateGroupTitle(gitPanelGroup, GIT_PANEL_TITLE, gitWidth.state)
                    },
                )
            }
        gitPanelGroup?.expanded = state.workspaceGitPanelExpanded
        gitPanelGroup?.addExpandedListener { state.workspaceGitPanelExpanded = it }
        updateGroupTitle(gitPanelGroup, GIT_PANEL_TITLE, gitWidth.state)
    }

    private fun updateGroupTitle(
        group: CollapsibleRow?,
        baseName: String,
        widthState: PanelWidthState,
    ) {
        val summary = PanelWidthState.widthSummary(widthState)
        val mutedHex = mutedColorHex()
        group?.setTitle("<html>$baseName <font color='$mutedHex'>\u00B7 $summary</font></html>")
    }

    private fun mutedColorHex(): String {
        val color =
            UIManager.getColor("Label.disabledForeground")
                ?: Color(FALLBACK_MUTED_RED, FALLBACK_MUTED_GREEN, FALLBACK_MUTED_BLUE)
        return String.format(
            Locale.ROOT,
            "#%02x%02x%02x",
            color.red,
            color.green,
            color.blue,
        )
    }

    private data class WidthModeGroupConfig(
        val uiState: WidthModeUiState,
        val minAutoFitWidth: Int,
        val licensed: Boolean,
        val showMinSpinner: Boolean = false,
        val onModeChanged: () -> Unit = {},
    )

    private fun createSpinner(
        value: Int,
        min: Int,
        onChange: (Int) -> Unit,
    ): JSpinner =
        JSpinner(SpinnerNumberModel(value, min, MAX_AUTOFIT_WIDTH, AUTOFIT_WIDTH_STEP)).also { spinner ->
            spinner.addChangeListener {
                if (!suppressListeners) onChange(spinner.value as Int)
            }
        }

    private fun createModeComboBox(
        selectedMode: PanelWidthMode,
        enabled: Boolean,
    ): JComboBox<PanelWidthMode> {
        val comboBox = JComboBox(DefaultComboBoxModel(PanelWidthMode.entries.toTypedArray()))
        comboBox.selectedItem = selectedMode
        comboBox.isEnabled = enabled
        comboBox.renderer =
            object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ): Component {
                    val label =
                        super.getListCellRendererComponent(
                            list,
                            value,
                            index,
                            isSelected,
                            cellHasFocus,
                        ) as JLabel
                    label.text =
                        when (value as? PanelWidthMode) {
                            PanelWidthMode.DEFAULT -> "Default"
                            PanelWidthMode.AUTO_FIT -> "Auto-fit"
                            PanelWidthMode.FIXED -> "Fixed"
                            null -> ""
                        }
                    return label
                }
            }
        return comboBox
    }

    private fun buildWidthModeGroup(
        panel: Panel,
        config: WidthModeGroupConfig,
    ) {
        val uiState = config.uiState
        val autoFitVisible = uiState.autoFitVisible
        val fixedVisible = uiState.fixedVisible
        autoFitVisible.set(uiState.state.pendingMode == PanelWidthMode.AUTO_FIT)
        fixedVisible.set(uiState.state.pendingMode == PanelWidthMode.FIXED)

        val autoFitSpinner =
            createSpinner(uiState.state.pendingAutoFitMaxWidth, config.minAutoFitWidth) {
                uiState.state.pendingAutoFitMaxWidth = it
                val currentMin = uiState.minSpinner
                if (currentMin != null && it < uiState.state.pendingAutoFitMinWidth) {
                    uiState.state.pendingAutoFitMinWidth = it
                    currentMin.value = it
                }
                config.onModeChanged()
            }
        uiState.autoFitSpinner = autoFitSpinner

        val minSpinner =
            if (config.showMinSpinner) {
                createSpinner(uiState.state.pendingAutoFitMinWidth, MIN_AUTOFIT_MIN_WIDTH) {
                    uiState.state.pendingAutoFitMinWidth = it
                    if (it > uiState.state.pendingAutoFitMaxWidth) {
                        uiState.state.pendingAutoFitMaxWidth = it
                        autoFitSpinner.value = it
                    }
                    config.onModeChanged()
                }.also { uiState.minSpinner = it }
            } else {
                null
            }

        val fixedSpinner =
            createSpinner(uiState.state.pendingFixedWidth, AutoFitCalculator.MIN_FIXED_WIDTH) {
                uiState.state.pendingFixedWidth = it
                config.onModeChanged()
            }
        uiState.fixedSpinner = fixedSpinner

        val comboBox = createModeComboBox(uiState.state.pendingMode, config.licensed)
        uiState.modeComboBox = comboBox

        comboBox.addActionListener {
            if (!suppressListeners) {
                uiState.state.pendingMode = comboBox.selectedItem as PanelWidthMode
                autoFitVisible.set(uiState.state.pendingMode == PanelWidthMode.AUTO_FIT)
                fixedVisible.set(uiState.state.pendingMode == PanelWidthMode.FIXED)
                config.onModeChanged()
            }
        }

        panel.row {
            cell(comboBox)
            if (minSpinner != null) {
                label("min").visibleIf(autoFitVisible)
                cell(minSpinner).visibleIf(autoFitVisible)
            }
            label("max").visibleIf(autoFitVisible)
            cell(autoFitSpinner).visibleIf(autoFitVisible)
            label("px").visibleIf(autoFitVisible)
            cell(fixedSpinner).visibleIf(fixedVisible)
            label("px").visibleIf(fixedVisible)
        }
    }

    override fun isModified(): Boolean =
        pendingHideEditorVScrollbar != storedHideEditorVScrollbar ||
            pendingHideEditorHScrollbar != storedHideEditorHScrollbar ||
            pendingHideRootPath != storedHideRootPath ||
            pendingHideHScrollbar != storedHideHScrollbar ||
            projectWidth.state.isModified() ||
            commitWidth.state.isModified() ||
            gitWidth.state.isModified()

    override fun apply() {
        if (!isModified()) return
        val state = AyuIslandsSettings.getInstance().state

        val editorChanged =
            pendingHideEditorVScrollbar != storedHideEditorVScrollbar ||
                pendingHideEditorHScrollbar != storedHideEditorHScrollbar
        val displayChanged =
            pendingHideRootPath != storedHideRootPath ||
                pendingHideHScrollbar != storedHideHScrollbar
        val projectWidthChanged = projectWidth.state.isModified()
        val commitWidthChanged = commitWidth.state.isModified()
        val gitWidthChanged = gitWidth.state.isModified()

        state.hideEditorVScrollbar = pendingHideEditorVScrollbar
        state.hideEditorHScrollbar = pendingHideEditorHScrollbar
        storedHideEditorVScrollbar = pendingHideEditorVScrollbar
        storedHideEditorHScrollbar = pendingHideEditorHScrollbar

        state.hideProjectRootPath = pendingHideRootPath
        state.hideProjectViewHScrollbar = pendingHideHScrollbar
        storedHideRootPath = pendingHideRootPath
        storedHideHScrollbar = pendingHideHScrollbar

        applyWidthState(projectWidth, state.projectWidthTarget())
        applyWidthState(commitWidth, state.commitWidthTarget())
        applyWidthState(gitWidth, state.gitWidthTarget())

        if (displayChanged || projectWidthChanged) {
            dispatchToOpenProjects { ProjectViewScrollbarManager.getInstance(it).apply() }
        }
        if (commitWidthChanged) {
            dispatchToOpenProjects { CommitPanelAutoFitManager.getInstance(it).apply() }
        }
        if (gitWidthChanged) {
            dispatchToOpenProjects { GitPanelAutoFitManager.getInstance(it).apply() }
        }
        if (editorChanged) {
            dispatchToOpenProjects { EditorScrollbarManager.getInstance(it).apply() }
        }
    }

    override fun reset() {
        pendingHideEditorVScrollbar = storedHideEditorVScrollbar
        pendingHideEditorHScrollbar = storedHideEditorHScrollbar
        pendingHideRootPath = storedHideRootPath
        pendingHideHScrollbar = storedHideHScrollbar

        suppressListeners = true
        hideEditorVScrollbarCheckbox?.isSelected = storedHideEditorVScrollbar
        hideEditorHScrollbarCheckbox?.isSelected = storedHideEditorHScrollbar
        hideRootPathCheckbox?.isSelected = storedHideRootPath
        hideHScrollbarCheckbox?.isSelected = storedHideHScrollbar
        projectWidth.reset()
        commitWidth.reset()
        gitWidth.reset()
        updateGroupTitle(projectViewGroup, PROJECT_VIEW_TITLE, projectWidth.state)
        updateGroupTitle(commitPanelGroup, COMMIT_PANEL_TITLE, commitWidth.state)
        updateGroupTitle(gitPanelGroup, GIT_PANEL_TITLE, gitWidth.state)
        suppressListeners = false
    }

    private inline fun loadCheckboxPair(
        stateValue: Boolean,
        assign: (stored: Boolean, pending: Boolean) -> Unit,
    ) {
        assign(stateValue, stateValue)
    }

    private fun Panel.buildCheckboxRow(
        label: String,
        comment: String,
        initialValue: Boolean,
        enabled: Boolean,
        onChange: (Boolean) -> Unit,
    ): JCheckBox {
        lateinit var checkbox: JCheckBox
        row {
            val cb = checkBox(label).comment(comment)
            cb.component.isSelected = initialValue
            cb.component.isEnabled = enabled
            cb.component.addActionListener { onChange(cb.component.isSelected) }
            checkbox = cb.component
        }
        return checkbox
    }

    private fun dispatchToOpenProjects(action: (com.intellij.openapi.project.Project) -> Unit) {
        for (openProject in ProjectManager.getInstance().openProjects) {
            ApplicationManager.getApplication().invokeLater(
                { action(openProject) },
                openProject.disposed,
            )
        }
    }

    private fun applyWidthState(
        uiState: WidthModeUiState,
        target: WidthTarget,
    ) {
        val pending = uiState.state
        target.setMode(pending.pendingMode.name)
        target.setMaxWidth(pending.pendingAutoFitMaxWidth)
        target.setMinWidth(pending.pendingAutoFitMinWidth)
        target.setFixedWidth(pending.pendingFixedWidth)
        target.setLegacyAutoFit?.invoke(pending.pendingMode == PanelWidthMode.AUTO_FIT)
        pending.commitStored()
    }

    /**
     * Setter bundle for writing [PanelWidthState] values to the corresponding
     * [AyuIslandsState] properties. Each panel (project/commit/git) provides
     * its own instance via the extension functions below.
     */
    private class WidthTarget(
        val setMode: (String) -> Unit,
        val setMaxWidth: (Int) -> Unit,
        val setMinWidth: (Int) -> Unit,
        val setFixedWidth: (Int) -> Unit,
        val setLegacyAutoFit: ((Boolean) -> Unit)? = null,
    )

    private fun AyuIslandsState.projectWidthTarget(): WidthTarget =
        WidthTarget(
            setMode = { projectPanelWidthMode = it },
            setMaxWidth = { autoFitMaxWidth = it },
            setMinWidth = { projectPanelAutoFitMinWidth = it },
            setFixedWidth = { projectPanelFixedWidth = it },
            setLegacyAutoFit = { autoFitProjectPanelWidth = it },
        )

    private fun AyuIslandsState.commitWidthTarget(): WidthTarget =
        WidthTarget(
            setMode = { commitPanelWidthMode = it },
            setMaxWidth = { autoFitCommitMaxWidth = it },
            setMinWidth = { commitPanelAutoFitMinWidth = it },
            setFixedWidth = { commitPanelFixedWidth = it },
            setLegacyAutoFit = { autoFitCommitPanelWidth = it },
        )

    private fun AyuIslandsState.gitWidthTarget(): WidthTarget =
        WidthTarget(
            setMode = { gitPanelWidthMode = it },
            setMaxWidth = { gitPanelAutoFitMaxWidth = it },
            setMinWidth = { gitPanelAutoFitMinWidth = it },
            setFixedWidth = { gitPanelFixedWidth = it },
        )

    private class WidthModeUiState(
        val state: PanelWidthState = PanelWidthState(),
    ) {
        val autoFitVisible = AtomicBooleanProperty(false)
        val fixedVisible = AtomicBooleanProperty(false)

        var modeComboBox: JComboBox<PanelWidthMode>? = null
        var autoFitSpinner: JSpinner? = null
        var minSpinner: JSpinner? = null
        var fixedSpinner: JSpinner? = null

        fun reset() {
            state.reset()
            modeComboBox?.selectedItem = state.storedMode
            autoFitSpinner?.value = state.storedAutoFitMaxWidth
            minSpinner?.value = state.storedAutoFitMinWidth
            fixedSpinner?.value = state.storedFixedWidth
            autoFitVisible.set(state.storedMode == PanelWidthMode.AUTO_FIT)
            fixedVisible.set(state.storedMode == PanelWidthMode.FIXED)
        }
    }

    companion object {
        private const val MAX_AUTOFIT_WIDTH = 800
        private const val MIN_AUTOFIT_MIN_WIDTH = 50
        private const val AUTOFIT_WIDTH_STEP = 50
        private const val FALLBACK_MUTED_RED = 0x6C
        private const val FALLBACK_MUTED_GREEN = 0x73
        private const val FALLBACK_MUTED_BLUE = 0x80
    }
}
