@file:Suppress("DialogTitleCapitalization")

package dev.ayuislands.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.dsl.builder.CollapsibleRow
import com.intellij.ui.dsl.builder.Panel
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.commitpanel.CommitPanelAutoFitManager
import dev.ayuislands.gitpanel.GitPanelAutoFitManager
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.projectview.ProjectViewScrollbarManager
import dev.ayuislands.settings.AyuIslandsState.Companion.DEFAULT_AUTO_FIT_MAX_WIDTH
import dev.ayuislands.settings.AyuIslandsState.Companion.DEFAULT_FIXED_WIDTH
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

/** Workspace tab: tool window layout tweaks (Project View, Commit Panel, Git Panel). */
class WorkspacePanel : AyuIslandsSettingsPanel {
    private var pendingHideRootPath = false
    private var storedHideRootPath = false
    private var pendingHideRootVcs = false
    private var storedHideRootVcs = false
    private var pendingHideHScrollbar = false
    private var storedHideHScrollbar = false

    private var hideRootPathCheckbox: JCheckBox? = null
    private var hideRootVcsCheckbox: JCheckBox? = null
    private var hideHScrollbarCheckbox: JCheckBox? = null

    private val projectWidth = WidthModeState()
    private val commitWidth = WidthModeState()
    private val gitWidth = WidthModeState()

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

        storedHideRootPath = state.hideProjectRootPath
        pendingHideRootPath = storedHideRootPath
        storedHideRootVcs = state.hideRootVcsAnnotations
        pendingHideRootVcs = storedHideRootVcs
        storedHideHScrollbar = state.hideProjectViewHScrollbar
        pendingHideHScrollbar = storedHideHScrollbar

        projectWidth.load(
            PanelWidthMode.fromString(state.projectPanelWidthMode),
            state.autoFitMaxWidth,
            state.projectPanelFixedWidth,
        )
        commitWidth.load(
            PanelWidthMode.fromString(state.commitPanelWidthMode),
            state.autoFitCommitMaxWidth,
            state.commitPanelFixedWidth,
        )
        gitWidth.load(
            PanelWidthMode.fromString(state.gitPanelWidthMode),
            state.gitPanelAutoFitMaxWidth,
            state.gitPanelFixedWidth,
        )

        projectViewGroup =
            panel.collapsibleGroup("Project View") {
                row {
                    val cb =
                        checkBox("Hide filesystem path")
                            .comment("Remove the directory path shown next to the project name")
                    cb.component.isSelected = pendingHideRootPath
                    cb.component.isEnabled = licensed
                    cb.component.addActionListener {
                        pendingHideRootPath = cb.component.isSelected
                    }
                    hideRootPathCheckbox = cb.component
                }
                row {
                    val cb =
                        checkBox("Hide VCS annotations")
                            .comment("Remove branch name and changed file count from the project root")
                    cb.component.isSelected = pendingHideRootVcs
                    cb.component.isEnabled = licensed
                    cb.component.addActionListener {
                        pendingHideRootVcs = cb.component.isSelected
                    }
                    hideRootVcsCheckbox = cb.component
                }
                row {
                    val cb =
                        checkBox("Hide horizontal scrollbar")
                            .comment("Remove the bottom scrollbar from the Project tool window")
                    cb.component.isSelected = pendingHideHScrollbar
                    cb.component.isEnabled = licensed
                    cb.component.addActionListener {
                        pendingHideHScrollbar = cb.component.isSelected
                    }
                    hideHScrollbarCheckbox = cb.component
                }
                separator()
                buildWidthModeGroup(this, projectWidth, MIN_PROJECT_AUTOFIT_WIDTH, licensed) {
                    updateGroupTitle(projectViewGroup, "Project View", projectWidth)
                }
            }
        projectViewGroup?.expanded = state.workspaceProjectViewExpanded
        projectViewGroup?.addExpandedListener { state.workspaceProjectViewExpanded = it }
        updateGroupTitle(projectViewGroup, "Project View", projectWidth)

        commitPanelGroup =
            panel.collapsibleGroup("Commit Panel") {
                buildWidthModeGroup(this, commitWidth, MIN_COMMIT_AUTOFIT_WIDTH, licensed) {
                    updateGroupTitle(commitPanelGroup, "Commit Panel", commitWidth)
                }
            }
        commitPanelGroup?.expanded = state.workspaceCommitPanelExpanded
        commitPanelGroup?.addExpandedListener { state.workspaceCommitPanelExpanded = it }
        updateGroupTitle(commitPanelGroup, "Commit Panel", commitWidth)

        gitPanelGroup =
            panel.collapsibleGroup("Git Panel") {
                buildWidthModeGroup(this, gitWidth, MIN_GIT_AUTOFIT_WIDTH, licensed) {
                    updateGroupTitle(gitPanelGroup, "Git Panel", gitWidth)
                }
            }
        gitPanelGroup?.expanded = state.workspaceGitPanelExpanded
        gitPanelGroup?.addExpandedListener { state.workspaceGitPanelExpanded = it }
        updateGroupTitle(gitPanelGroup, "Git Panel", gitWidth)
    }

    private fun widthSummary(widthState: WidthModeState): String =
        when (widthState.pendingMode) {
            PanelWidthMode.DEFAULT -> "Default"
            PanelWidthMode.AUTO_FIT -> "Auto-fit \u00B7 max ${widthState.pendingAutoFitMaxWidth}px"
            PanelWidthMode.FIXED -> "Fixed \u00B7 ${widthState.pendingFixedWidth}px"
        }

    private fun updateGroupTitle(
        group: CollapsibleRow?,
        baseName: String,
        widthState: WidthModeState,
    ) {
        val summary = widthSummary(widthState)
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

    private fun buildWidthModeGroup(
        panel: Panel,
        widthState: WidthModeState,
        minAutoFitWidth: Int,
        licensed: Boolean,
        onModeChanged: () -> Unit = {},
    ) {
        val autoFitVisible = widthState.autoFitVisible
        val fixedVisible = widthState.fixedVisible
        autoFitVisible.set(widthState.pendingMode == PanelWidthMode.AUTO_FIT)
        fixedVisible.set(widthState.pendingMode == PanelWidthMode.FIXED)

        val autoFitSpinner =
            JSpinner(
                SpinnerNumberModel(
                    widthState.pendingAutoFitMaxWidth,
                    minAutoFitWidth,
                    MAX_AUTOFIT_WIDTH,
                    AUTOFIT_WIDTH_STEP,
                ),
            )
        autoFitSpinner.addChangeListener {
            if (!suppressListeners) {
                widthState.pendingAutoFitMaxWidth = autoFitSpinner.value as Int
                onModeChanged()
            }
        }
        widthState.autoFitSpinner = autoFitSpinner

        val fixedSpinner =
            JSpinner(
                SpinnerNumberModel(
                    widthState.pendingFixedWidth,
                    MIN_FIXED_WIDTH,
                    MAX_AUTOFIT_WIDTH,
                    AUTOFIT_WIDTH_STEP,
                ),
            )
        fixedSpinner.addChangeListener {
            if (!suppressListeners) {
                widthState.pendingFixedWidth = fixedSpinner.value as Int
                onModeChanged()
            }
        }
        widthState.fixedSpinner = fixedSpinner

        val comboBox = JComboBox(DefaultComboBoxModel(PanelWidthMode.entries.toTypedArray()))
        comboBox.selectedItem = widthState.pendingMode
        comboBox.isEnabled = licensed
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
        widthState.modeComboBox = comboBox

        comboBox.addActionListener {
            if (!suppressListeners) {
                widthState.pendingMode = comboBox.selectedItem as PanelWidthMode
                autoFitVisible.set(widthState.pendingMode == PanelWidthMode.AUTO_FIT)
                fixedVisible.set(widthState.pendingMode == PanelWidthMode.FIXED)
                onModeChanged()
            }
        }

        panel.row {
            cell(comboBox)
            label("max").visibleIf(autoFitVisible)
            cell(autoFitSpinner).visibleIf(autoFitVisible)
            label("px").visibleIf(autoFitVisible)
            cell(fixedSpinner).visibleIf(fixedVisible)
            label("px").visibleIf(fixedVisible)
        }
    }

    override fun isModified(): Boolean =
        pendingHideRootPath != storedHideRootPath ||
            pendingHideRootVcs != storedHideRootVcs ||
            pendingHideHScrollbar != storedHideHScrollbar ||
            projectWidth.isModified() ||
            commitWidth.isModified() ||
            gitWidth.isModified()

    override fun apply() {
        if (!isModified()) return
        val state = AyuIslandsSettings.getInstance().state

        val displayChanged =
            pendingHideRootPath != storedHideRootPath ||
                pendingHideRootVcs != storedHideRootVcs ||
                pendingHideHScrollbar != storedHideHScrollbar
        val projectWidthChanged = projectWidth.isModified()
        val commitWidthChanged = commitWidth.isModified()
        val gitWidthChanged = gitWidth.isModified()

        state.hideProjectRootPath = pendingHideRootPath
        state.hideRootVcsAnnotations = pendingHideRootVcs
        state.hideProjectViewHScrollbar = pendingHideHScrollbar
        storedHideRootPath = pendingHideRootPath
        storedHideRootVcs = pendingHideRootVcs
        storedHideHScrollbar = pendingHideHScrollbar

        state.projectPanelWidthMode = projectWidth.pendingMode.name
        state.autoFitMaxWidth = projectWidth.pendingAutoFitMaxWidth
        state.projectPanelFixedWidth = projectWidth.pendingFixedWidth
        state.autoFitProjectPanelWidth = projectWidth.pendingMode == PanelWidthMode.AUTO_FIT
        projectWidth.commitStored()

        state.commitPanelWidthMode = commitWidth.pendingMode.name
        state.autoFitCommitMaxWidth = commitWidth.pendingAutoFitMaxWidth
        state.commitPanelFixedWidth = commitWidth.pendingFixedWidth
        state.autoFitCommitPanelWidth = commitWidth.pendingMode == PanelWidthMode.AUTO_FIT
        commitWidth.commitStored()

        state.gitPanelWidthMode = gitWidth.pendingMode.name
        state.gitPanelAutoFitMaxWidth = gitWidth.pendingAutoFitMaxWidth
        state.gitPanelFixedWidth = gitWidth.pendingFixedWidth
        gitWidth.commitStored()

        if (displayChanged || projectWidthChanged) {
            for (openProject in ProjectManager.getInstance().openProjects) {
                ApplicationManager.getApplication().invokeLater(
                    { ProjectViewScrollbarManager.getInstance(openProject).apply() },
                    openProject.disposed,
                )
            }
        }

        if (commitWidthChanged) {
            for (openProject in ProjectManager.getInstance().openProjects) {
                ApplicationManager.getApplication().invokeLater(
                    { CommitPanelAutoFitManager.getInstance(openProject).apply() },
                    openProject.disposed,
                )
            }
        }

        if (gitWidthChanged) {
            for (openProject in ProjectManager.getInstance().openProjects) {
                ApplicationManager.getApplication().invokeLater(
                    { GitPanelAutoFitManager.getInstance(openProject).apply() },
                    openProject.disposed,
                )
            }
        }
    }

    override fun reset() {
        pendingHideRootPath = storedHideRootPath
        pendingHideRootVcs = storedHideRootVcs
        pendingHideHScrollbar = storedHideHScrollbar

        suppressListeners = true
        hideRootPathCheckbox?.isSelected = storedHideRootPath
        hideRootVcsCheckbox?.isSelected = storedHideRootVcs
        hideHScrollbarCheckbox?.isSelected = storedHideHScrollbar
        projectWidth.reset()
        commitWidth.reset()
        gitWidth.reset()
        updateGroupTitle(projectViewGroup, "Project View", projectWidth)
        updateGroupTitle(commitPanelGroup, "Commit Panel", commitWidth)
        updateGroupTitle(gitPanelGroup, "Git Panel", gitWidth)
        suppressListeners = false
    }

    private class WidthModeState {
        var pendingMode = PanelWidthMode.DEFAULT
        var storedMode = PanelWidthMode.DEFAULT
        var pendingAutoFitMaxWidth = DEFAULT_AUTO_FIT_MAX_WIDTH
        var storedAutoFitMaxWidth = DEFAULT_AUTO_FIT_MAX_WIDTH
        var pendingFixedWidth = DEFAULT_FIXED_WIDTH
        var storedFixedWidth = DEFAULT_FIXED_WIDTH

        val autoFitVisible = AtomicBooleanProperty(false)
        val fixedVisible = AtomicBooleanProperty(false)

        var modeComboBox: JComboBox<PanelWidthMode>? = null
        var autoFitSpinner: JSpinner? = null
        var fixedSpinner: JSpinner? = null

        fun load(
            mode: PanelWidthMode,
            autoFitMaxWidth: Int,
            fixedWidth: Int,
        ) {
            storedMode = mode
            pendingMode = mode
            storedAutoFitMaxWidth = autoFitMaxWidth
            pendingAutoFitMaxWidth = autoFitMaxWidth
            storedFixedWidth = fixedWidth
            pendingFixedWidth = fixedWidth
        }

        fun isModified(): Boolean =
            pendingMode != storedMode ||
                pendingAutoFitMaxWidth != storedAutoFitMaxWidth ||
                pendingFixedWidth != storedFixedWidth

        fun commitStored() {
            storedMode = pendingMode
            storedAutoFitMaxWidth = pendingAutoFitMaxWidth
            storedFixedWidth = pendingFixedWidth
        }

        fun reset() {
            pendingMode = storedMode
            pendingAutoFitMaxWidth = storedAutoFitMaxWidth
            pendingFixedWidth = storedFixedWidth
            modeComboBox?.selectedItem = storedMode
            autoFitSpinner?.value = storedAutoFitMaxWidth
            fixedSpinner?.value = storedFixedWidth
            autoFitVisible.set(storedMode == PanelWidthMode.AUTO_FIT)
            fixedVisible.set(storedMode == PanelWidthMode.FIXED)
        }
    }

    companion object {
        private const val MIN_PROJECT_AUTOFIT_WIDTH = 200
        private const val MIN_COMMIT_AUTOFIT_WIDTH = 269
        private const val MIN_GIT_AUTOFIT_WIDTH = 200
        private const val MIN_FIXED_WIDTH = 100
        private const val MAX_AUTOFIT_WIDTH = 800
        private const val AUTOFIT_WIDTH_STEP = 50
        private const val FALLBACK_MUTED_RED = 0x6C
        private const val FALLBACK_MUTED_GREEN = 0x73
        private const val FALLBACK_MUTED_BLUE = 0x80
    }
}
