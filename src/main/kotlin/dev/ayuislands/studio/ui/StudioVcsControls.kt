package dev.ayuislands.studio.ui

import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.observable.properties.AtomicProperty
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.settings.PremiumFeatureGate
import dev.ayuislands.settings.VcsColorPreviewComponent
import dev.ayuislands.settings.VcsPreviewIntensities
import dev.ayuislands.settings.VcsSection
import dev.ayuislands.settings.applyPremiumLock
import dev.ayuislands.studio.model.StudioVcsEdit
import dev.ayuislands.studio.session.StudioVcsSettings
import dev.ayuislands.vcs.VcsColorCategory
import dev.ayuislands.vcs.VcsColorPreset
import dev.ayuislands.vcs.VcsColorSnapshot
import dev.ayuislands.vcs.VcsIntensity
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.SwingConstants

/** Native controls consume a draft presentation and report explicit EDT gestures to the host. */
internal class StudioVcsControls(
    expanded: Map<VcsSection, Boolean>,
    disclose: (VcsSection, Boolean) -> Unit,
    unlock: () -> Unit,
    private val onEdit: (StudioVcsEdit) -> Unit,
) : JPanel(BorderLayout()) {
    private val enabledToggle = JBCheckBox("Enable VCS color customization").apply { isEnabled = false }
    private val sample = VcsColorPreviewComponent(AyuVariant.MIRAGE)
    private val stockColors = AtomicBooleanProperty(false)
    private val presetsEditable = AtomicBooleanProperty(false)
    private val sectionsVisible = AtomicBooleanProperty(false)
    private val locked = AtomicBooleanProperty(false)
    private val groups = VcsSection.entries.associateWith { VcsPresetControls(it, ::emit) }
    private var isRendering = false

    init {
        enabledToggle.addActionListener { emit(StudioVcsEdit.Enable(enabledToggle.isSelected)) }
        sample.accessibleContext?.accessibleName = "VCS color preview"
        add(
            panel {
                row { comment(vcsGate(false).lockedDescription) }.visibleIf(locked)
                row { link("Unlock") { if (locked.get()) unlock() } }.visibleIf(locked)
                row { cell(enabledToggle) }
                row { cell(sample).resizableColumn().align(Align.FILL) }
                row { label("Stock VCS colors") }.visibleIf(stockColors)
                for ((section, controls) in groups) {
                    val group = collapsibleGroup(section.title) { controls.addRows(this, presetsEditable) }
                    group.expanded = expanded.getValue(section)
                    group.addExpandedListener { disclose(section, it) }
                    group.visibleIf(sectionsVisible)
                }
            },
            BorderLayout.CENTER,
        )
    }

    fun render(
        settings: StudioVcsSettings,
        isEditable: Boolean,
        isLicensed: Boolean,
    ) {
        isRendering = true
        try {
            val gate = vcsGate(isLicensed)
            presetsEditable.set(isEditable && isLicensed && settings.enabled)
            locked.set(!isLicensed)
            enabledToggle.isSelected = settings.enabled
            enabledToggle.applyPremiumLock(gate, isEditable)
            sectionsVisible.set(settings.enabled || !isLicensed)
            for (group in groups.values) group.render(settings, gate, isEditable)
        } finally {
            isRendering = false
        }
    }

    fun showPreview(
        variant: AyuVariant,
        snapshot: VcsColorSnapshot,
    ) {
        val intensities =
            if (snapshot.enabled) {
                VcsPreviewIntensities(
                    diffViewer = snapshot.intensityFor(VcsColorCategory.DIFF_VIEWER).percent,
                    projectView = snapshot.intensityFor(VcsColorCategory.PROJECT_VIEW_FILE_STATUS).percent,
                    editorGutter = snapshot.intensityFor(VcsColorCategory.EDITOR_GUTTER).percent,
                    conflictMarkers = snapshot.intensityFor(VcsColorCategory.CONFLICT_MARKERS).percent,
                    blameGutter = snapshot.intensityFor(VcsColorCategory.BLAME_GUTTER).percent,
                )
            } else {
                null
            }
        sample.updatePreview(variant, intensities)
        stockColors.set(!snapshot.enabled)
    }

    private fun emit(edit: StudioVcsEdit) {
        if (!isRendering && enabledToggle.isEnabled) onEdit(edit)
    }
}

private class VcsPresetControls(
    private val section: VcsSection,
    private val onEdit: (StudioVcsEdit) -> Unit,
) {
    private val selectedPreset = AtomicProperty<VcsColorPreset?>(null)
    private val customVisible = AtomicBooleanProperty(false)
    private val unavailable = AtomicBooleanProperty(false)
    private val unavailablePreset = JLabel("Preset unavailable")
    private val intensities =
        section.sliders.associate { (category, title) ->
            category to VcsIntensityControl(title) { value -> onEdit(StudioVcsEdit.Intensity(category, value)) }
        }

    fun addRows(
        panel: Panel,
        editing: AtomicBooleanProperty,
    ) = with(panel) {
        selectedPreset.afterChange { preset ->
            if (preset != null && editing.get()) onEdit(StudioVcsEdit.Preset(section, preset))
        }
        row("Preset:") {
            segmentedButton<VcsColorPreset?>(VcsColorPreset.entries) { text = it?.displayName.orEmpty() }
                .bind(selectedPreset)
        }.enabledIf(editing)
        row { cell(unavailablePreset) }.visibleIf(unavailable)
        for (intensity in intensities.values) {
            row { cell(intensity.component).resizableColumn().align(Align.FILL) }.visibleIf(customVisible)
        }
    }

    fun render(
        settings: StudioVcsSettings,
        gate: PremiumFeatureGate,
        isEditable: Boolean,
    ) {
        val name = settings.presets.getValue(section)
        val preset = VcsColorPreset.entries.firstOrNull { it.name == name }
        selectedPreset.set(preset)
        unavailable.set(preset == null)
        // A literal prefix keeps Swing from treating an imported name as HTML.
        unavailablePreset.toolTipText = if (preset == null) name?.let { "Saved preset: $it" } else null
        unavailablePreset.accessibleContext.accessibleDescription = unavailablePreset.toolTipText
        customVisible.set(preset == VcsColorPreset.CUSTOM || !gate.isUnlocked)
        for ((category, intensity) in intensities) {
            intensity.render(settings.intensities.getValue(category), gate, isEditable && settings.enabled)
        }
    }
}

private class VcsIntensityControl(
    title: String,
    onChange: (VcsIntensity) -> Unit,
) {
    private val slider = JSlider(VcsIntensity.MIN, VcsIntensity.MAX)
    private val readout = JLabel("", SwingConstants.RIGHT)
    private val label = JLabel(title).apply { labelFor = slider }
    private val spacing = StudioUiTokens.spacing()

    val component =
        JPanel(StudioResponsiveLayout()).apply {
            isOpaque = false
            add(label)
            add(
                JPanel(BorderLayout(spacing.fieldGap, 0)).apply {
                    isOpaque = false
                    add(slider, BorderLayout.CENTER)
                    add(readout, BorderLayout.EAST)
                },
            )
        }

    init {
        slider.addChangeListener { if (slider.isEnabled) onChange(VcsIntensity.of(slider.value)) }
        val labelMetrics = label.getFontMetrics(label.font)
        val labelWidth = VcsSection.entries.flatMap { it.sliders }.maxOf { labelMetrics.stringWidth(it.second) }
        label.preferredSize = Dimension(labelWidth, label.preferredSize.height)
    }

    fun render(
        rawValue: Int,
        gate: PremiumFeatureGate,
        isEditable: Boolean,
    ) {
        val displayed = VcsIntensity.of(rawValue).percent
        slider.value = displayed
        slider.applyPremiumLock(gate, isEditable)
        readout.text = displayed.toString()
        readout.toolTipText = if (displayed == rawValue) null else "Saved intensity: $rawValue"
        val metrics = readout.getFontMetrics(readout.font)
        val width = maxOf(metrics.stringWidth(VcsIntensity.MAX.toString()), metrics.stringWidth(readout.text))
        readout.preferredSize = Dimension(width, metrics.height)
        readout.minimumSize = readout.preferredSize
    }
}

private fun vcsGate(isLicensed: Boolean): PremiumFeatureGate =
    PremiumFeatureGate(
        featureName = "VCS color customization",
        lockedDescription = "VCS color customization is a Pro feature. Preview its controls here.",
        requestMessage = "Unlock VCS color intensity customization",
        isUnlocked = isLicensed,
    )
