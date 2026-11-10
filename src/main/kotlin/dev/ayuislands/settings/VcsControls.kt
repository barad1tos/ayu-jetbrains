package dev.ayuislands.settings

import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.observable.properties.AtomicProperty
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.SegmentedButton
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import dev.ayuislands.studio.ui.StudioResponsiveLayout
import dev.ayuislands.vcs.VcsColorCategory
import dev.ayuislands.vcs.VcsColorPreset
import dev.ayuislands.vcs.VcsIntensity
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.SwingConstants

/** Native VCS controls consume explicit observations and emit gestures without touching runtime or persistence. */
internal class VcsControls(
    initialPreview: VcsPreviewInput,
    expanded: Map<VcsSection, Boolean>,
    private val onEvent: (VcsPanelEvent) -> Unit,
) : JPanel(BorderLayout()) {
    private var rendered: VcsPanelView? = null
    private var isRendering = false
    private val editing = AtomicBooleanProperty(false)
    private val locked = AtomicBooleanProperty(false)
    private val sectionsVisible = AtomicBooleanProperty(false)
    private val sampleVisible = AtomicBooleanProperty(false)
    private val disabledVisible = AtomicBooleanProperty(false)
    private val selections = VcsSection.entries.associateWith { AtomicProperty<VcsColorPreset?>(null) }
    private val customVisible = VcsSection.entries.associateWith { AtomicBooleanProperty(false) }
    private val unavailableVisible = VcsSection.entries.associateWith { AtomicBooleanProperty(false) }
    private val descriptions = VcsSection.entries.associateWith { JLabel() }
    private val presets = mutableMapOf<VcsSection, SegmentedButton<VcsColorPreset?>>()
    private val sliders = mutableMapOf<VcsColorCategory, JSlider>()
    private val readouts = mutableMapOf<VcsColorCategory, JLabel>()
    private val enable = JBCheckBox("Enable VCS color customization")
    private val sample = VcsColorPreviewComponent(initialPreview.variant, initialPreview.intensities)

    init {
        enable.addActionListener { emit(VcsPanelEvent.Enabled(enable.isSelected)) }
        selections.forEach { (section, selection) ->
            selection.afterChange { preset -> preset?.let { emit(VcsPanelEvent.Preset(section, it)) } }
        }
        add(panel { buildRows(expanded) }, BorderLayout.CENTER)
    }

    fun render(
        view: VcsPanelView,
        preview: VcsPreviewInput,
    ) {
        isRendering = true
        try {
            rendered = view
            val premiumGate = vcsPremiumGate(view.isLicensed)
            editing.set(view.isEditable && view.isLicensed)
            locked.set(!view.isLicensed)
            enable.isSelected = view.settings.enabled
            enable.applyPremiumLock(premiumGate, view.isEditable)
            sectionsVisible.set(view.settings.enabled || !view.isLicensed)
            for (section in VcsSection.entries) {
                val rawName = presetName(view.settings, section)
                val preset = VcsColorPreset.entries.firstOrNull { it.name == rawName }
                selections.getValue(section).set(preset)
                customVisible.getValue(section).set(preset == VcsColorPreset.CUSTOM || !view.isLicensed)
                unavailableVisible.getValue(section).set(preset == null)
                descriptions.getValue(section).apply {
                    text = rawName?.let { "Preset '$it' is unavailable" } ?: "No preset is selected"
                    toolTipText = text
                    accessibleContext.accessibleName = text
                    minimumSize = Dimension(0, preferredSize.height)
                }
            }
            for ((category, slider) in sliders) {
                val value = view.settings.intensities.getValue(category)
                slider.value = value
                slider.applyPremiumLock(premiumGate, view.isEditable)
                slider.accessibleContext.accessibleName = "${slider.name} $value"
                readouts.getValue(category).text = value.toString()
            }
            alignReadouts()
            sample.updatePreview(preview.variant, preview.intensities)
            sampleVisible.set(preview.isVisible && preview.isEnabled)
            disabledVisible.set(preview.isVisible && !preview.isEnabled)
            revalidate()
            repaint()
        } finally {
            isRendering = false
        }
    }

    private fun Panel.buildRows(expanded: Map<VcsSection, Boolean>) {
        row {
            comment(vcsPremiumGate(false).lockedDescription)
        }.visibleIf(locked)
        row { link("Unlock") { emit(VcsPanelEvent.Unlock) } }.visibleIf(locked)
        row { cell(enable) }
        row { cell(sample).resizableColumn().align(Align.FILL) }.visibleIf(sampleVisible)
        row { label("VCS customization is off") }.visibleIf(disabledVisible)
        for (section in VcsSection.entries) {
            val disclosure =
                collapsibleGroup(section.title) {
                    row("Preset:") {
                        presets[section] =
                            segmentedButton<VcsColorPreset?>(
                                VcsColorPreset.entries,
                            ) { text = it?.displayName.orEmpty() }
                                .maxButtonsCount(VcsColorPreset.entries.size)
                                .bind(selections.getValue(section))
                    }.enabledIf(editing)
                    row { cell(descriptions.getValue(section)).resizableColumn().align(Align.FILL) }
                        .visibleIf(unavailableVisible.getValue(section))
                    for ((category, label) in section.sliders) {
                        row { cell(intensityControl(category, label)).resizableColumn().align(Align.FILL) }
                            .visibleIf(customVisible.getValue(section))
                    }
                }
            disclosure.expanded = expanded.getValue(section)
            disclosure.addExpandedListener { emit(VcsPanelEvent.Expanded(section, it)) }
            disclosure.visibleIf(sectionsVisible)
        }
    }

    private fun intensityControl(
        category: VcsColorCategory,
        title: String,
    ): JPanel {
        val slider =
            JSlider(VcsIntensity.MIN, VcsIntensity.MAX).apply {
                name = title
                paintTicks = true
                majorTickSpacing = INTENSITY_MAJOR_TICK
                minorTickSpacing = INTENSITY_MINOR_TICK
                addChangeListener { emit(VcsPanelEvent.Intensity(category, value)) }
            }
        sliders[category] = slider
        val readout = JLabel("", SwingConstants.RIGHT)
        readouts[category] = readout
        val control =
            JPanel(BorderLayout(JBUI.scale(CONTROL_GAP), 0)).apply {
                isOpaque = false
                add(slider, BorderLayout.CENTER)
                add(readout, BorderLayout.EAST)
            }
        val label =
            JLabel(title).apply {
                labelFor = slider
                val metrics = getFontMetrics(font)
                val labelWidth = VcsSection.entries.flatMap { it.sliders }.maxOf { metrics.stringWidth(it.second) }
                preferredSize = Dimension(labelWidth, preferredSize.height)
                minimumSize = preferredSize
            }
        return JPanel(StudioResponsiveLayout()).apply {
            isOpaque = false
            add(label)
            add(control)
        }
    }

    private fun alignReadouts() {
        val readoutWidth = readouts.values.maxOf { it.getFontMetrics(it.font).stringWidth(it.text) }
        readouts.values.forEach { label ->
            label.preferredSize = Dimension(readoutWidth, label.getFontMetrics(label.font).height)
            label.minimumSize = label.preferredSize
        }
    }

    private fun emit(event: VcsPanelEvent) {
        val view = rendered ?: return
        if (isRendering) return
        val isUiAction = event is VcsPanelEvent.Expanded || event == VcsPanelEvent.Unlock
        if (isUiAction || (view.isEditable && view.isLicensed)) onEvent(event)
    }

    private companion object {
        const val INTENSITY_MAJOR_TICK = 25
        const val INTENSITY_MINOR_TICK = 5
        const val CONTROL_GAP = 8
    }
}

private fun presetName(
    settings: VcsPanelSettings,
    section: VcsSection,
): String? =
    when (section) {
        VcsSection.DIFF -> settings.diffPreset
        VcsSection.MERGE -> settings.mergePreset
        VcsSection.BLAME -> settings.blamePreset
    }

/**
 * Selector for the three VCS panel sections. Encapsulates each section's
 * user-visible title and the list of per-category sliders it owns in Custom
 * mode.
 */
internal enum class VcsSection(
    val title: String,
    val sliders: List<Pair<VcsColorCategory, String>>,
) {
    DIFF(
        title = "Diff and File Status",
        sliders =
            listOf(
                VcsColorCategory.DIFF_VIEWER to "Diff viewer:",
                VcsColorCategory.PROJECT_VIEW_FILE_STATUS to "Project View:",
                VcsColorCategory.EDITOR_GUTTER to "Editor gutter:",
            ),
    ),
    MERGE(
        title = "Merge and Conflict",
        sliders = listOf(VcsColorCategory.CONFLICT_MARKERS to "Conflict markers:"),
    ),
    BLAME(
        title = "Blame and History",
        sliders = listOf(VcsColorCategory.BLAME_GUTTER to "Blame gutter:"),
    ),
}
