package dev.ayuislands.settings

import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import dev.ayuislands.commitpanel.CommitPathShortener
import dev.ayuislands.commitpanel.CommitPathShorteningRequest
import dev.ayuislands.studio.ui.StudioResponsiveLayout
import dev.ayuislands.studio.ui.StudioUiTokens
import dev.ayuislands.toolwindow.AutoFitCalculator
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Rectangle
import javax.swing.DefaultComboBoxModel
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.UIManager

private const val UNSET_MODE = "not set"

/** Render explicit Workspace facts without reading services or changing the working values. */
internal class WorkspaceControls(
    private val onEvent: (WorkspacePanelEvent) -> Unit,
) : JPanel(BorderLayout()) {
    private var rendered: WorkspacePanelView? = null
    private var isRendering = false
    private val locked = AtomicBooleanProperty(false)
    private val preview = WorkspaceSample()
    private val toggles = WorkspaceToggle.entries.associateWith { JCheckBox(it.title) }
    private val widths = WorkspaceSurface.entries.associateWith { WidthControls(it, ::emit) }
    private val pathMode = modeCombo()
    private val pathMinimum = widthSpinner("Commit path minimum hidden levels")
    private val pathMaximum = widthSpinner("Commit path maximum hidden levels")

    init {
        for ((target, checkbox) in toggles) {
            checkbox.accessibleContext.accessibleName = target.description
            checkbox.addActionListener {
                if (checkbox.isEnabled) {
                    emit(
                        WorkspacePanelEvent.Toggle(target, checkbox.isSelected),
                    )
                }
            }
        }
        pathMode.accessibleContext.accessibleName = "Commit path display"
        pathMode.addActionListener {
            val selection = pathMode.selectedItem as? CommitPathDisplayMode
            if (pathMode.isEnabled && selection != null) emit(WorkspacePanelEvent.PathMode(selection))
        }
        pathMinimum.addChangeListener {
            if (pathMinimum.isEnabled) emit(WorkspacePanelEvent.PathMinimum(pathMinimum.value as Int))
        }
        pathMaximum.addChangeListener {
            if (pathMaximum.isEnabled) emit(WorkspacePanelEvent.PathMaximum(pathMaximum.value as Int))
        }
        add(panel { buildRows() }, BorderLayout.CENTER)
    }

    fun render(view: WorkspacePanelView) {
        isRendering = true
        try {
            rendered = view
            locked.set(!view.isLicensed)
            val editable = view.isEditable && view.isLicensed
            for ((target, checkbox) in toggles) {
                checkbox.isSelected = view.settings.isHidden(target)
                checkbox.isEnabled = editable
            }
            for ((surface, controls) in widths) {
                controls.render(
                    view.settings.width(surface),
                    editable,
                    !view.isLicensed,
                )
            }
            renderPaths(view.settings, editable)
            preview.render(view.preview)
            revalidate()
            repaint()
        } finally {
            isRendering = false
        }
    }

    private fun Panel.buildRows() {
        row { comment("Customize editor chrome, Project View display, and tool window width/path behavior.") }
        row { cell(preview).resizableColumn().align(Align.FILL) }
        row { comment("Workspace customization is a Pro feature. Preview its controls here.") }.visibleIf(locked)
        row { link("Unlock") { emit(WorkspacePanelEvent.Unlock) } }.visibleIf(locked)
        group("Editor") {
            buildToggle(WorkspaceToggle.EDITOR_VERTICAL_SCROLLBAR)
            buildToggle(WorkspaceToggle.EDITOR_HORIZONTAL_SCROLLBAR)
        }
        group("Project View Display") {
            buildToggle(WorkspaceToggle.PROJECT_ROOT_PATH)
            buildToggle(WorkspaceToggle.PROJECT_HORIZONTAL_SCROLLBAR)
        }
        group("Tool Window Width") {
            for ((surface, controls) in widths) {
                row(surface.title) {
                    cell(controls.mode)
                    cell(controls.dimensions).resizableColumn().align(Align.FILL)
                }
            }
        }
        group("Path Display") {
            row("Commit Panel") {
                cell(pathMode)
                cell(pathDimensions()).resizableColumn().align(Align.FILL)
            }
            row { comment("Tooltip only hides inline paths and keeps the full path available on hover.") }
            row { comment("Min/max levels control how many leading directories Ayu can hide when shortening paths.") }
        }
    }

    private fun Panel.buildToggle(target: WorkspaceToggle) {
        row { cell(toggles.getValue(target)).comment(target.description) }
    }

    private fun pathDimensions(): JPanel =
        JPanel(StudioResponsiveLayout()).apply {
            add(JLabel("min").apply { labelFor = pathMinimum })
            add(pathMinimum)
            add(JLabel("max levels").apply { labelFor = pathMaximum })
            add(pathMaximum)
        }

    private fun renderPaths(
        settings: WorkspaceSettings,
        editable: Boolean,
    ) {
        selectMode(pathMode, CommitPathDisplayMode.entries, settings.paths.mode)
        val commitMode = PanelWidthMode.entries.firstOrNull { it.name == settings.commit.mode }
        pathMode.isEnabled = editable && commitMode != null && commitMode != PanelWidthMode.DEFAULT
        val levelsEnabled = pathMode.isEnabled && settings.paths.mode == CommitPathDisplayMode.INLINE.name
        pathMinimum.model = numberModel(settings.paths.minimum, 0, MAX_PATH_LEVELS, 1)
        pathMaximum.model = numberModel(settings.paths.maximum, 0, MAX_PATH_LEVELS, 1)
        pathMinimum.isEnabled = levelsEnabled
        pathMaximum.isEnabled = levelsEnabled
    }

    private fun emit(event: WorkspacePanelEvent) {
        val view = rendered ?: return
        if (isRendering) return
        if (event == WorkspacePanelEvent.Unlock || (view.isLicensed && view.isEditable)) onEvent(event)
    }

    private companion object {
        const val MAX_PATH_LEVELS = 20
    }
}

private class WidthControls(
    private val surface: WorkspaceSurface,
    private val emit: (WorkspacePanelEvent) -> Unit,
) {
    val mode = modeCombo()
    private val minimum = widthSpinner("${surface.title} minimum width")
    private val maximum = widthSpinner("${surface.title} maximum width")
    private val fixed = widthSpinner("${surface.title} fixed width")
    private val minimumLabel = JLabel("min").apply { labelFor = minimum }
    private val maximumLabel = JLabel("max px").apply { labelFor = maximum }
    private val fixedLabel = JLabel("px").apply { labelFor = fixed }
    val dimensions =
        JPanel(StudioResponsiveLayout()).apply {
            add(minimumLabel)
            add(minimum)
            add(maximumLabel)
            add(maximum)
            add(fixedLabel)
            add(fixed)
        }

    init {
        mode.accessibleContext.accessibleName = "${surface.title} width mode"
        mode.addActionListener {
            val selected = mode.selectedItem as? PanelWidthMode
            if (mode.isEnabled && selected != null) emit(WorkspacePanelEvent.Mode(surface, selected))
        }
        minimum.addChangeListener {
            if (minimum.isEnabled) {
                emit(
                    WorkspacePanelEvent.Minimum(surface, minimum.value as Int),
                )
            }
        }
        maximum.addChangeListener {
            if (maximum.isEnabled) {
                emit(
                    WorkspacePanelEvent.Maximum(surface, maximum.value as Int),
                )
            }
        }
        fixed.addChangeListener { if (fixed.isEnabled) emit(WorkspacePanelEvent.Fixed(surface, fixed.value as Int)) }
    }

    fun render(
        values: WorkspaceWidth,
        editable: Boolean,
        showPreview: Boolean,
    ) {
        selectMode(mode, PanelWidthMode.entries, values.mode)
        mode.isEnabled = editable
        minimum.model = numberModel(values.minimum, MINIMUM_WIDTH, MAXIMUM_WIDTH, WIDTH_STEP)
        maximum.model = numberModel(values.maximum, surface.maximumLowerBound, MAXIMUM_WIDTH, WIDTH_STEP)
        fixed.model = numberModel(values.fixed, AutoFitCalculator.MIN_FIXED_WIDTH, MAXIMUM_WIDTH, WIDTH_STEP)
        minimum.isEnabled = editable
        maximum.isEnabled = editable
        fixed.isEnabled = editable
        val autoFitVisible = showPreview || values.mode == PanelWidthMode.AUTO_FIT.name
        val fixedVisible = showPreview || values.mode == PanelWidthMode.FIXED.name
        minimumLabel.isVisible = autoFitVisible
        minimum.isVisible = autoFitVisible
        maximumLabel.isVisible = autoFitVisible
        maximum.isVisible = autoFitVisible
        fixedLabel.isVisible = fixedVisible
        fixed.isVisible = fixedVisible
    }

    private companion object {
        const val MINIMUM_WIDTH = 50
        const val MAXIMUM_WIDTH = 800
        const val WIDTH_STEP = 50
    }
}

// Swing's selection contains existing enum items and an optional literal unavailable value.
private fun modeCombo(): ComboBox<Any> =
    ComboBox<Any>().apply {
        renderer =
            object : ColoredListCellRenderer<Any>() {
                override fun customizeCellRenderer(
                    list: JList<out Any>,
                    value: Any?,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean,
                ) {
                    append(modeLabel(value))
                }
            }
        val width = JBUI.scale(MODE_WIDTH)
        preferredSize = Dimension(width, preferredSize.height)
        minimumSize = Dimension(width, minimumSize.height)
    }

private fun modeLabel(value: Any?): String =
    when (value) {
        PanelWidthMode.DEFAULT -> "Default"
        PanelWidthMode.AUTO_FIT -> "Auto-fit"
        PanelWidthMode.FIXED -> "Fixed"
        CommitPathDisplayMode.INLINE -> "Inline"
        CommitPathDisplayMode.TOOLTIP -> "Tooltip only"
        else -> value?.toString().orEmpty()
    }

private fun <T : Enum<T>> selectMode(
    combo: ComboBox<Any>,
    choices: List<T>,
    raw: String?,
) {
    val selected: Any = choices.firstOrNull { it.name == raw } ?: "Unavailable: ${raw ?: UNSET_MODE}"
    val items: List<Any> = if (selected in choices) choices else choices + selected
    combo.model = DefaultComboBoxModel(items.toTypedArray())
    combo.selectedItem = selected
    combo.toolTipText = modeLabel(selected)
}

private fun widthSpinner(name: String): JSpinner =
    JSpinner(SpinnerNumberModel(0, null, null, 1)).apply { accessibleContext.accessibleName = name }

private fun numberModel(
    value: Int,
    minimum: Int,
    maximum: Int,
    step: Int,
): SpinnerNumberModel = SpinnerNumberModel(value, minOf(value, minimum), maxOf(value, maximum), step)

/** A local structural illustration; dimensions are labels, never measurements of real tool windows. */
private class WorkspaceSample : JPanel() {
    private var settings: WorkspaceSettings? = null
    private val spacing = StudioUiTokens.spacing()

    init {
        preferredSize = Dimension(JBUI.scale(SAMPLE_WIDTH), JBUI.scale(SAMPLE_HEIGHT))
        minimumSize = Dimension(JBUI.scale(MINIMUM_SAMPLE_WIDTH), preferredSize.height)
        toolTipText = "Workspace layout sample"
    }

    fun render(value: WorkspaceSettings) {
        settings = value
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val value = settings ?: return
        val gap = spacing.fieldGap
        val halfWidth = (width - gap) / 2
        val halfHeight = (height - gap) / 2
        val project = Rectangle(0, 0, halfWidth, halfHeight)
        val editor = Rectangle(halfWidth + gap, 0, width - halfWidth - gap, halfHeight)
        val commit = Rectangle(0, halfHeight + gap, halfWidth, height - halfHeight - gap)
        val git = Rectangle(halfWidth + gap, halfHeight + gap, width - halfWidth - gap, height - halfHeight - gap)
        drawPane(graphics, project, "Project View", widthSummary(value.project))
        drawPane(graphics, editor, "Editor", "Main.kt")
        drawPane(graphics, commit, "Commit Panel", widthSummary(value.commit))
        drawPane(graphics, git, "Git Panel", widthSummary(value.git))
        drawDetail(graphics, project, if (value.hideProjectRootPath) "project" else "project ~/workspace/project")
        drawDetail(graphics, commit, pathSample(value, graphics, commit.width - gap * 2))
        drawScrollbars(graphics, project, vertical = true, horizontal = !value.hideProjectHorizontal)
        drawScrollbars(graphics, editor, vertical = !value.hideEditorVertical, horizontal = !value.hideEditorHorizontal)
    }

    private fun drawPane(
        graphics: Graphics,
        bounds: Rectangle,
        title: String,
        detail: String,
    ) {
        val pane = graphics.create(bounds.x, bounds.y, bounds.width, bounds.height)
        try {
            pane.color = UIManager.getColor("Separator.foreground") ?: foreground
            pane.drawRect(0, 0, bounds.width - 1, bounds.height - 1)
            pane.color = foreground
            pane.drawString(title, spacing.fieldGap, pane.fontMetrics.ascent + spacing.fieldGap)
            pane.drawString(detail, spacing.fieldGap, pane.fontMetrics.height * 2 + spacing.fieldGap)
        } finally {
            pane.dispose()
        }
    }

    private fun drawDetail(
        graphics: Graphics,
        bounds: Rectangle,
        text: String,
    ) {
        val pane = graphics.create(bounds.x, bounds.y, bounds.width, bounds.height)
        try {
            pane.color = foreground
            pane.drawString(text, spacing.fieldGap, pane.fontMetrics.height * DETAIL_LINE + spacing.fieldGap)
        } finally {
            pane.dispose()
        }
    }

    private fun drawScrollbars(
        graphics: Graphics,
        bounds: Rectangle,
        vertical: Boolean,
        horizontal: Boolean,
    ) {
        graphics.color = UIManager.getColor("ScrollBar.thumb") ?: foreground
        val gap = spacing.fieldGap
        if (vertical) graphics.fillRect(bounds.x + bounds.width - gap, bounds.y + gap, gap / 2, bounds.height / 2)
        if (horizontal) graphics.fillRect(bounds.x + gap, bounds.y + bounds.height - gap, bounds.width / 2, gap / 2)
    }

    private fun widthSummary(value: WorkspaceWidth): String =
        when (value.mode) {
            PanelWidthMode.DEFAULT.name -> "Default"
            PanelWidthMode.AUTO_FIT.name -> "Auto-fit · ${value.minimum}–${value.maximum}px"
            PanelWidthMode.FIXED.name -> "Fixed · ${value.fixed}px"
            else -> "Unavailable: ${value.mode ?: UNSET_MODE}"
        }

    private fun pathSample(
        settings: WorkspaceSettings,
        graphics: Graphics,
        available: Int,
    ): String {
        if (settings.commit.mode != PanelWidthMode.AUTO_FIT.name && settings.commit.mode != PanelWidthMode.FIXED.name) {
            return SAMPLE_PATH
        }
        val paths = settings.paths
        return when (paths.mode) {
            CommitPathDisplayMode.TOOLTIP.name -> {
                "Main.kt · path on hover"
            }

            CommitPathDisplayMode.INLINE.name -> {
                CommitPathShortener.shorten(
                    CommitPathShorteningRequest(
                        SAMPLE_PATH,
                        graphics.fontMetrics.stringWidth(SAMPLE_PATH),
                        available,
                        paths.minimum,
                        paths.maximum,
                    ),
                )
            }

            else -> {
                "Unavailable: ${paths.mode ?: UNSET_MODE}"
            }
        }
    }

    private companion object {
        const val DETAIL_LINE = 3
        const val SAMPLE_WIDTH = 480
        const val SAMPLE_HEIGHT = 200
        const val MINIMUM_SAMPLE_WIDTH = 300
        const val SAMPLE_PATH = "project/src/main/kotlin/Main.kt"
    }
}

private const val MODE_WIDTH = 124
