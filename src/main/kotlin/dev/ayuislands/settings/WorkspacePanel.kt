@file:Suppress("DialogTitleCapitalization")

package dev.ayuislands.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.dsl.builder.Panel
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.commitpanel.CommitPanelAutoFitManager
import dev.ayuislands.editor.EditorScrollbarManager
import dev.ayuislands.gitpanel.GitPanelAutoFitManager
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.projectview.ProjectViewScrollbarManager
import dev.ayuislands.toolwindow.AutoFitCalculator
import java.awt.Component
import java.awt.Dimension
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

private const val EDITOR_TITLE = "Editor"
private const val PROJECT_VIEW_TITLE = "Project View"
private const val PROJECT_VIEW_DISPLAY_TITLE = "Project View Display"
private const val COMMIT_PANEL_TITLE = "Commit Panel"
private const val GIT_PANEL_TITLE = "Git Panel"
private const val TOOL_WINDOW_WIDTH_TITLE = "Tool Window Width"
private const val PATH_DISPLAY_TITLE = "Path Display"

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
    private val commitPathShortening = PathShorteningUiState()
    private val gitWidth = WidthModeUiState()

    private var suppressListeners = false

    override fun buildPanel(
        panel: Panel,
        variant: AyuVariant,
    ) {
        val state = AyuIslandsSettings.getInstance().state
        val gate =
            PremiumFeatureGate(
                featureName = "Workspace customization",
                lockedDescription =
                    "Workspace customization is a Pro feature. " +
                        "Preview tool window width and Project View display controls here.",
                requestMessage = "Unlock workspace customization",
            )
        val licensed = gate.isUnlocked

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
        commitPathShortening.load(
            CommitPathDisplayMode.fromString(state.commitPanelPathDisplayMode),
            state.commitPanelPathMinHiddenLevels,
            state.commitPanelPathMaxHiddenLevels,
        )
        gitWidth.state.load(
            PanelWidthMode.fromString(state.gitPanelWidthMode),
            state.gitPanelAutoFitMaxWidth,
            state.gitPanelFixedWidth,
            state.gitPanelAutoFitMinWidth,
        )

        panel.row { comment("Customize editor chrome, Project View display, and tool window width/path behavior.") }
        panel.premiumFeatureNotice(gate)

        panel.group(EDITOR_TITLE) {
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

        panel.group(PROJECT_VIEW_DISPLAY_TITLE) {
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
        }

        panel.group(TOOL_WINDOW_WIDTH_TITLE) {
            buildWidthModeGroup(
                this,
                WidthModeGroupConfig(
                    PROJECT_VIEW_TITLE,
                    projectWidth,
                    AutoFitCalculator.MIN_PROJECT_AUTOFIT_WIDTH,
                    gate,
                    showMinSpinner = true,
                ),
            )
            buildWidthModeGroup(
                this,
                WidthModeGroupConfig(
                    COMMIT_PANEL_TITLE,
                    commitWidth,
                    AutoFitCalculator.MIN_COMMIT_AUTOFIT_WIDTH,
                    gate,
                    showMinSpinner = true,
                ) {
                    updatePathShorteningEnabled()
                },
            )
            buildWidthModeGroup(
                this,
                WidthModeGroupConfig(
                    GIT_PANEL_TITLE,
                    gitWidth,
                    AutoFitCalculator.MIN_GIT_AUTOFIT_WIDTH,
                    gate,
                    showMinSpinner = true,
                ),
            )
        }

        panel.group(PATH_DISPLAY_TITLE) {
            buildPathShorteningRow(this, gate)
        }
    }

    private data class WidthModeGroupConfig(
        val rowLabel: String,
        val uiState: WidthModeUiState,
        val minAutoFitWidth: Int,
        val gate: PremiumFeatureGate,
        val showMinSpinner: Boolean = false,
        val onModeChanged: () -> Unit = {},
    ) {
        val licensed: Boolean = gate.isUnlocked
    }

    private fun createSpinner(
        value: Int,
        min: Int,
        enabled: Boolean,
        onChange: (Int) -> Unit,
    ): JSpinner =
        JSpinner(SpinnerNumberModel(value, min, MAX_AUTOFIT_WIDTH, AUTOFIT_WIDTH_STEP)).also { spinner ->
            spinner.isEnabled = enabled
            spinner.addChangeListener {
                if (!suppressListeners && spinner.isEnabled) onChange(spinner.value as Int)
            }
        }

    private fun createModeComboBox(
        selectedMode: PanelWidthMode,
        enabled: Boolean,
    ): ComboBox<PanelWidthMode> {
        val comboBox = ComboBox(DefaultComboBoxModel(PanelWidthMode.entries.toTypedArray()))
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
        comboBox.applyPreferredWidth(MODE_COMBO_WIDTH)
        return comboBox
    }

    private fun createPathDisplayComboBox(
        selectedMode: CommitPathDisplayMode,
        enabled: Boolean,
    ): ComboBox<CommitPathDisplayMode> {
        val comboBox = ComboBox(DefaultComboBoxModel(CommitPathDisplayMode.entries.toTypedArray()))
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
                        when (value as? CommitPathDisplayMode) {
                            CommitPathDisplayMode.INLINE -> "Inline"
                            CommitPathDisplayMode.TOOLTIP -> "Tooltip only"
                            null -> ""
                        }
                    return label
                }
            }
        comboBox.addActionListener {
            if (!suppressListeners && comboBox.isEnabled) {
                commitPathShortening.pendingDisplayMode = comboBox.selectedItem as CommitPathDisplayMode
                updatePathShorteningEnabled()
            }
        }
        comboBox.applyPreferredWidth(MODE_COMBO_WIDTH)
        return comboBox
    }

    private fun Component.applyPreferredWidth(width: Int) {
        val scaledWidth = JBUI.scale(width)
        val height = preferredSize.height
        preferredSize = Dimension(scaledWidth, height)
        minimumSize = Dimension(scaledWidth, height)
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
            createSpinner(uiState.state.pendingAutoFitMaxWidth, config.minAutoFitWidth, config.licensed) {
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
                createSpinner(uiState.state.pendingAutoFitMinWidth, MIN_AUTOFIT_MIN_WIDTH, config.licensed) {
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
            createSpinner(uiState.state.pendingFixedWidth, AutoFitCalculator.MIN_FIXED_WIDTH, config.licensed) {
                uiState.state.pendingFixedWidth = it
                config.onModeChanged()
            }
        uiState.fixedSpinner = fixedSpinner

        val comboBox = createModeComboBox(uiState.state.pendingMode, config.licensed)
        uiState.modeComboBox = comboBox

        comboBox.addActionListener {
            if (!suppressListeners && config.licensed) {
                uiState.state.pendingMode = comboBox.selectedItem as PanelWidthMode
                autoFitVisible.set(uiState.state.pendingMode == PanelWidthMode.AUTO_FIT)
                fixedVisible.set(uiState.state.pendingMode == PanelWidthMode.FIXED)
                config.onModeChanged()
            }
        }

        panel.row(config.rowLabel) {
            cell(comboBox)
            if (minSpinner != null) {
                label("min").visibleIfUnlockedOrPreview(autoFitVisible, config.gate)
                cell(minSpinner).visibleIfUnlockedOrPreview(autoFitVisible, config.gate)
            }
            label("max").visibleIfUnlockedOrPreview(autoFitVisible, config.gate)
            cell(autoFitSpinner).visibleIfUnlockedOrPreview(autoFitVisible, config.gate)
            label("px").visibleIfUnlockedOrPreview(autoFitVisible, config.gate)
            cell(fixedSpinner).visibleIfUnlockedOrPreview(fixedVisible, config.gate)
            label("px").visibleIfUnlockedOrPreview(fixedVisible, config.gate)
        }
    }

    private fun buildPathShorteningRow(
        panel: Panel,
        gate: PremiumFeatureGate,
    ) {
        val displayComboBox =
            createPathDisplayComboBox(commitPathShortening.pendingDisplayMode, gate.isUnlocked)
        val minSpinner =
            createPathSpinner(commitPathShortening.pendingMinHiddenLevels, gate.isUnlocked) {
                commitPathShortening.pendingMinHiddenLevels = it
                if (it > commitPathShortening.pendingMaxHiddenLevels) {
                    commitPathShortening.pendingMaxHiddenLevels = it
                    commitPathShortening.maxSpinner?.value = it
                }
            }
        val maxSpinner =
            createPathSpinner(commitPathShortening.pendingMaxHiddenLevels, gate.isUnlocked) {
                commitPathShortening.pendingMaxHiddenLevels = it
                if (it < commitPathShortening.pendingMinHiddenLevels) {
                    commitPathShortening.pendingMinHiddenLevels = it
                    commitPathShortening.minSpinner?.value = it
                }
            }
        commitPathShortening.displayComboBox = displayComboBox
        commitPathShortening.minSpinner = minSpinner
        commitPathShortening.maxSpinner = maxSpinner

        panel.row(COMMIT_PANEL_TITLE) {
            cell(displayComboBox)
            label("min")
            cell(minSpinner)
            label("max")
            cell(maxSpinner)
            label("levels")
        }
        panel.row {
            comment("Tooltip only hides inline paths and keeps the full path available on hover.")
        }
        panel.row {
            comment("Min/max levels control how many leading directories Ayu can hide when shortening paths.")
        }
        updatePathShorteningEnabled()
    }

    private fun createPathSpinner(
        value: Int,
        enabled: Boolean,
        onChange: (Int) -> Unit,
    ): JSpinner =
        JSpinner(
            SpinnerNumberModel(
                value.coerceIn(0, MAX_PATH_HIDDEN_LEVELS),
                0,
                MAX_PATH_HIDDEN_LEVELS,
                PATH_HIDDEN_LEVEL_STEP,
            ),
        ).also { spinner ->
            spinner.isEnabled = enabled
            spinner.addChangeListener {
                if (!suppressListeners && spinner.isEnabled) onChange(spinner.value as Int)
            }
        }

    private fun updatePathShorteningEnabled() {
        commitPathShortening.setEnabled(
            LicenseChecker.isLicensedOrGrace() &&
                commitWidth.state.pendingMode != PanelWidthMode.DEFAULT,
        )
    }

    override fun isModified(): Boolean =
        pendingHideEditorVScrollbar != storedHideEditorVScrollbar ||
            pendingHideEditorHScrollbar != storedHideEditorHScrollbar ||
            pendingHideRootPath != storedHideRootPath ||
            pendingHideHScrollbar != storedHideHScrollbar ||
            projectWidth.state.isModified() ||
            commitWidth.state.isModified() ||
            commitPathShortening.isModified() ||
            gitWidth.state.isModified()

    override fun apply() {
        if (!isModified()) return
        if (!LicenseChecker.isLicensedOrGrace()) return
        val state = AyuIslandsSettings.getInstance().state

        val editorChanged =
            pendingHideEditorVScrollbar != storedHideEditorVScrollbar ||
                pendingHideEditorHScrollbar != storedHideEditorHScrollbar
        val displayChanged =
            pendingHideRootPath != storedHideRootPath ||
                pendingHideHScrollbar != storedHideHScrollbar
        val projectWidthChanged = projectWidth.state.isModified()
        val commitWidthChanged = commitWidth.state.isModified()
        val commitPathChanged = commitPathShortening.isModified()
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
        state.commitPanelPathDisplayMode = commitPathShortening.pendingDisplayMode.name
        state.commitPanelPathMinHiddenLevels = commitPathShortening.pendingMinHiddenLevels
        state.commitPanelPathMaxHiddenLevels = commitPathShortening.pendingMaxHiddenLevels
        commitPathShortening.commitStored()
        applyWidthState(commitWidth, state.commitWidthTarget())
        applyWidthState(gitWidth, state.gitWidthTarget())

        if (displayChanged || projectWidthChanged) {
            dispatchToOpenProjects { ProjectViewScrollbarManager.getInstance(it).apply() }
        }
        if (commitWidthChanged || commitPathChanged) {
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
        commitPathShortening.reset()
        gitWidth.reset()
        updatePathShorteningEnabled()
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

        var modeComboBox: ComboBox<PanelWidthMode>? = null
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

    private class PathShorteningUiState {
        var pendingDisplayMode = CommitPathDisplayMode.INLINE
        var storedDisplayMode = CommitPathDisplayMode.INLINE
        var pendingMinHiddenLevels = AyuIslandsState.DEFAULT_COMMIT_PATH_MIN_HIDDEN_LEVELS
        var storedMinHiddenLevels = AyuIslandsState.DEFAULT_COMMIT_PATH_MIN_HIDDEN_LEVELS
        var pendingMaxHiddenLevels = AyuIslandsState.DEFAULT_COMMIT_PATH_MAX_HIDDEN_LEVELS
        var storedMaxHiddenLevels = AyuIslandsState.DEFAULT_COMMIT_PATH_MAX_HIDDEN_LEVELS
        var displayComboBox: ComboBox<CommitPathDisplayMode>? = null
        var minSpinner: JSpinner? = null
        var maxSpinner: JSpinner? = null

        fun load(
            displayMode: CommitPathDisplayMode,
            minHiddenLevels: Int,
            maxHiddenLevels: Int,
        ) {
            val normalizedMin = minHiddenLevels.coerceIn(0, MAX_PATH_HIDDEN_LEVELS)
            val normalizedMax =
                maxHiddenLevels
                    .coerceIn(0, MAX_PATH_HIDDEN_LEVELS)
                    .coerceAtLeast(normalizedMin)
            storedDisplayMode = displayMode
            pendingDisplayMode = displayMode
            storedMinHiddenLevels = normalizedMin
            pendingMinHiddenLevels = normalizedMin
            storedMaxHiddenLevels = normalizedMax
            pendingMaxHiddenLevels = normalizedMax
        }

        fun isModified(): Boolean =
            pendingDisplayMode != storedDisplayMode ||
                pendingMinHiddenLevels != storedMinHiddenLevels ||
                pendingMaxHiddenLevels != storedMaxHiddenLevels

        fun commitStored() {
            storedDisplayMode = pendingDisplayMode
            storedMinHiddenLevels = pendingMinHiddenLevels
            storedMaxHiddenLevels = pendingMaxHiddenLevels
        }

        fun reset() {
            pendingDisplayMode = storedDisplayMode
            pendingMinHiddenLevels = storedMinHiddenLevels
            pendingMaxHiddenLevels = storedMaxHiddenLevels
            displayComboBox?.selectedItem = storedDisplayMode
            minSpinner?.value = storedMinHiddenLevels
            maxSpinner?.value = storedMaxHiddenLevels
        }

        fun setEnabled(enabled: Boolean) {
            displayComboBox?.isEnabled = enabled
            val areLevelControlsEnabled = enabled && pendingDisplayMode == CommitPathDisplayMode.INLINE
            minSpinner?.isEnabled = areLevelControlsEnabled
            maxSpinner?.isEnabled = areLevelControlsEnabled
        }
    }

    companion object {
        private const val MAX_AUTOFIT_WIDTH = 800
        private const val MIN_AUTOFIT_MIN_WIDTH = 50
        private const val AUTOFIT_WIDTH_STEP = 50
        private const val MODE_COMBO_WIDTH = 124
        private const val MAX_PATH_HIDDEN_LEVELS = 20
        private const val PATH_HIDDEN_LEVEL_STEP = 1
    }
}
