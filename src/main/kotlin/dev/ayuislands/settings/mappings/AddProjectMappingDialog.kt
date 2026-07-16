package dev.ayuislands.settings.mappings

import com.intellij.ide.RecentProjectListActionProvider
import com.intellij.ide.ReopenProjectAction
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import dev.ayuislands.accent.color.ProjectIconAccentExtractor
import org.jetbrains.annotations.TestOnly
import java.awt.Component
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** Row model used by the recent-projects list inside [AddProjectMappingDialog]. */
internal data class RecentProjectRow(
    val canonicalPath: String,
    val displayName: String,
)

/**
 * Dialog for adding a project → accent mapping. Offers a path field with a folder
 * browser and (when available) a list of recent projects pre-filtered to paths
 * not already mapped. A compact [AccentSwatchPickerRow] lets the user pick the color.
 */
class AddProjectMappingDialog(
    parent: Project?,
    private val excludedPaths: Set<String>,
    initialHex: String? = null,
) : DialogWrapper(parent, true) {
    private val pathField = TextFieldWithBrowseButton()
    private val recentList = JBList<RecentProjectRow>()
    private var manualSelectionRevision = 0
    private val swatchPicker = AccentSwatchPickerRow(::applyManualColor)
    private val useIconColorLink = ActionLink("Use icon color") { applyIconColor() }
    private val iconProbeAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, disposable)
    private val iconExtractAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, disposable)
    private var iconGeneration = 0
    private var resolvedIconFile: File? = null

    var resultCanonicalPath: String? = null
        private set
    var resultDisplayName: String? = null
        private set
    var resultHex: String? = initialHex
        private set

    init {
        title = "Add Project Override"
        swatchPicker.selectedHex = initialHex

        pathField.addBrowseFolderListener(
            parent,
            FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Select Project Folder"),
        )

        val recentModel = DefaultListModel<RecentProjectRow>()
        for (row in loadRecentProjects(excludedPaths)) recentModel.addElement(row)
        recentList.model = recentModel
        recentList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        recentList.cellRenderer = RecentProjectRenderer()
        recentList.addListSelectionListener { event ->
            if (event.valueIsAdjusting) return@addListSelectionListener
            recentList.selectedValue?.let { row -> pathField.text = row.canonicalPath }
        }

        // Detect, don't assume: the icon shortcut only appears when the typed
        // (or recent-list-selected — that path routes through pathField.text
        // too) project actually has a usable .idea/icon.png.
        pathField.textField.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(event: DocumentEvent) = refreshIconLinkVisibility()

                override fun removeUpdate(event: DocumentEvent) = refreshIconLinkVisibility()

                override fun changedUpdate(event: DocumentEvent) = refreshIconLinkVisibility()
            },
        )
        refreshIconLinkVisibility()

        init()
    }

    override fun createCenterPanel(): JComponent =
        panel {
            row("Path:") {
                cell(pathField)
                    .resizableColumn()
                    .align(AlignX.FILL)
            }
            if (recentList.model.size > 0) {
                row {
                    cell(JBLabel("Or pick from recent projects:"))
                }.topGap(TopGap.SMALL)
                row {
                    cell(JBScrollPane(recentList))
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .applyToComponent { preferredSize = JBUI.size(RECENT_LIST_WIDTH, RECENT_LIST_HEIGHT) }
                }
            }
            row("Color:") {
                cell(swatchPicker)
                cell(useIconColorLink)
            }.topGap(TopGap.MEDIUM)
            row {
                comment("Applied across all Ayu variants (Mirage, Dark, Light).")
            }
        }

    override fun doValidate(): ValidationInfo? {
        val rawPath = pathField.text.trim()
        if (rawPath.isEmpty()) return ValidationInfo("Enter a project path.", pathField)
        val file = File(rawPath)
        if (!runCatching { file.isDirectory }.getOrDefault(false)) {
            return ValidationInfo("Path is not an existing directory.", pathField)
        }
        val canonical =
            runCatching { file.canonicalPath }.getOrNull()
                ?: return ValidationInfo("Could not resolve path.", pathField)
        if (excludedPaths.any { it.equals(canonical, ignoreCase = true) }) {
            return ValidationInfo("This project already has an override.", pathField)
        }
        if (swatchPicker.selectedHex.isNullOrBlank()) {
            return ValidationInfo("Choose an accent color.", swatchPicker)
        }
        return null
    }

    override fun doOKAction() {
        val rawPath = pathField.text.trim()
        // doValidate() already checks canonicalPath before the OK button enables, but a race
        // (directory deleted between validate and click) would otherwise cause a silent early
        // return — dialog appears frozen, user has no idea why OK stopped working. setErrorText
        // surfaces the condition and keeps the dialog open so the user can retry or cancel.
        val canonical =
            runCatching { File(rawPath).canonicalPath }.getOrNull() ?: run {
                setErrorText("Could not resolve path (directory may have been removed).", pathField)
                return
            }
        resultCanonicalPath = canonical
        resultDisplayName =
            recentList.selectedValue
                ?.takeIf { it.canonicalPath == canonical }
                ?.displayName
                ?: File(canonical).name
        resultHex = swatchPicker.selectedHex
        super.doOKAction()
    }

    private fun refreshIconLinkVisibility() {
        val generation = ++iconGeneration
        val projectPath = pathField.text.trim()
        resolvedIconFile = null
        useIconColorLink.isVisible = false
        useIconColorLink.isEnabled = false
        iconProbeAlarm.cancelAllRequests()
        iconProbeAlarm.addRequest(
            {
                val iconFile = ProjectIconAccentExtractor.projectIconFile(projectPath)
                SwingUtilities.invokeLater {
                    if (isDisposed || generation != iconGeneration || projectPath != pathField.text.trim()) {
                        return@invokeLater
                    }
                    resolvedIconFile = iconFile
                    useIconColorLink.isVisible = iconFile != null
                    useIconColorLink.isEnabled = iconFile != null
                }
            },
            ICON_PROBE_DELAY_MS,
        )
    }

    private fun applyIconColor() {
        val iconFile = resolvedIconFile ?: return
        val generation = iconGeneration
        val selectionRevision = manualSelectionRevision
        useIconColorLink.isEnabled = false
        iconExtractAlarm.addRequest(
            {
                val accent = ProjectIconAccentExtractor.extract(iconFile)
                SwingUtilities.invokeLater {
                    if (isDisposed || generation != iconGeneration || resolvedIconFile != iconFile) {
                        return@invokeLater
                    }
                    useIconColorLink.isEnabled = true
                    if (selectionRevision != manualSelectionRevision) return@invokeLater
                    if (accent == null) {
                        setErrorText("No dominant color found in the project icon.", swatchPicker)
                        return@invokeLater
                    }
                    setErrorText(null)
                    swatchPicker.selectedHex = accent.value
                    resultHex = accent.value
                }
            },
            0,
        )
    }

    private fun applyManualColor(selected: String) {
        manualSelectionRevision++
        resultHex = selected
    }

    @TestOnly
    internal fun iconLinkVisibleForTest(): Boolean = useIconColorLink.isVisible

    @TestOnly
    internal fun iconLinkEnabledForTest(): Boolean = useIconColorLink.isEnabled

    @TestOnly
    internal fun useIconColorForTest() = applyIconColor()

    @TestOnly
    internal fun drainIconTasks() {
        check(!SwingUtilities.isEventDispatchThread()) { "Icon tasks must be drained outside the EDT" }
        awaitEmpty(iconProbeAlarm)
        awaitEmpty(iconExtractAlarm)
        SwingUtilities.invokeAndWait {}
    }

    private fun awaitEmpty(alarm: Alarm) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TEST_TIMEOUT_SECONDS)
        while (!alarm.isEmpty && System.nanoTime() < deadline) {
            Thread.sleep(TEST_POLL_INTERVAL_MS)
        }
        check(alarm.isEmpty) { "Timed out waiting for icon work" }
    }

    @TestOnly
    internal fun setPathForTest(path: String) {
        pathField.text = path
    }

    @TestOnly
    internal fun selectHexForTest(hex: String?) {
        swatchPicker.selectedHex = hex
        manualSelectionRevision++
        resultHex = hex
    }

    @TestOnly
    internal fun validationMessageForTest(): String? = doValidate()?.message

    @TestOnly
    internal fun confirmForTest() {
        val validation = doValidate()
        check(validation == null) { validation?.message ?: "Dialog validation failed" }
        doOKAction()
    }

    private fun loadRecentProjects(excluded: Set<String>): List<RecentProjectRow> =
        runCatching {
            val actions =
                RecentProjectListActionProvider
                    .getInstance()
                    .getActions(addClearListItem = false, useGroups = false)
            actions
                .filterIsInstance<ReopenProjectAction>()
                .mapNotNull { action ->
                    val canonical =
                        runCatching { File(action.projectPath).canonicalPath }.getOrNull()
                            ?: return@mapNotNull null
                    if (excluded.any { it.equals(canonical, ignoreCase = true) }) return@mapNotNull null
                    if (!runCatching { File(canonical).isDirectory }.getOrDefault(false)) return@mapNotNull null
                    val name = action.projectName?.takeIf { it.isNotBlank() } ?: File(canonical).name
                    RecentProjectRow(canonical, name)
                }.distinctBy { it.canonicalPath }
        }.getOrDefault(emptyList())

    companion object {
        private const val RECENT_LIST_WIDTH = 460
        private const val RECENT_LIST_HEIGHT = 160
        private const val ICON_PROBE_DELAY_MS = 150
        private const val TEST_TIMEOUT_SECONDS = 5L
        private const val TEST_POLL_INTERVAL_MS = 10L
    }

    private class RecentProjectRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is RecentProjectRow) {
                text = "${value.displayName}   —   ${value.canonicalPath}"
            }
            return this
        }
    }
}
