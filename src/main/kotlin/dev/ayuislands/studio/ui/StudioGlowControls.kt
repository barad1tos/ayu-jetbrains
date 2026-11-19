package dev.ayuislands.studio.ui

import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.ActionLink
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowPlacement
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.settings.GlowGroupPanel
import dev.ayuislands.settings.GlowPreview
import dev.ayuislands.settings.GlowSettings
import dev.ayuislands.settings.GlowVisibility
import dev.ayuislands.settings.PremiumFeatureGate
import dev.ayuislands.settings.WaveformControlBadges
import dev.ayuislands.settings.WaveformControlVisibility
import dev.ayuislands.settings.WaveformSettingsControls
import dev.ayuislands.settings.WaveformSettingsValue
import dev.ayuislands.settings.applyPremiumLock
import dev.ayuislands.settings.newFeatureBadgeLabel
import dev.ayuislands.studio.model.StudioGlowEdit
import dev.ayuislands.studio.model.StudioGlowTarget
import dev.ayuislands.studio.model.StudioList
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.ButtonGroup
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.JToggleButton

internal class StudioGlowControls(
    private val onEdit: (StudioGlowEdit) -> Unit,
    private val onUnlock: () -> Unit,
    private val onBadgeSeen: (String) -> Unit,
) : JPanel(BorderLayout()) {
    private var settings = GlowSettings()
    private var rendering = false
    private val visibility = GlowVisibility()
    private val editing = AtomicBooleanProperty(false)
    private val editingEffects = AtomicBooleanProperty(false)
    private val locked = AtomicBooleanProperty(false)
    private val preset = GlowChoice(GlowPreset.entries, GlowPreset::displayName) { emit(StudioGlowEdit.Preset(it)) }
    private val editorPlacement =
        GlowChoice(GlowPlacement.entries, GlowPlacement::displayName) { emit(StudioGlowEdit.EditorPlacement(it)) }
    private val toolPlacement =
        GlowChoice(GlowPlacement.entries, GlowPlacement::displayName) { emit(StudioGlowEdit.ToolWindowPlacement(it)) }
    private val enable = JCheckBox("Enable glow")
    private val contour = GlowGroupPanel()
    private val badges = mutableMapOf<String, Cell<JLabel>>()
    private val targets = mutableMapOf<StudioGlowTarget, JCheckBox>()
    private val style = ComboBox(GlowStyle.entries.map { it.displayName }.toTypedArray())
    private val animation = ComboBox(GlowAnimation.entries.map { it.displayName }.toTypedArray())
    private val intensity = JSlider(0, MAX_INTENSITY, GlowStyle.SOFT.defaultIntensity)
    private val width = JSlider(MIN_WIDTH, MAX_WIDTH, GlowStyle.SOFT.defaultWidth)
    private val intensityReadout = JLabel()
    private val widthReadout = JLabel()
    private val gate = PremiumFeatureGate("Glow", PRO_DESCRIPTION, "Unlock glow effects", isUnlocked = true)
    private val waveform =
        WaveformSettingsControls(
            initial = settings.waveformValue(),
            gate = gate,
            visibility = WaveformControlVisibility(visibility.waveform),
            badges =
                WaveformControlBadges(
                    attach = { row, id, _ -> row.badge(id) },
                    acknowledge = { id -> if (!rendering && editingEffects.get()) onBadgeSeen(id) },
                ),
            onChange = ::editWaveform,
        )

    init {
        enable.addActionListener { emit(StudioGlowEdit.Enable(enable.isSelected)) }
        style.addActionListener {
            GlowStyle.entries.firstOrNull { it.displayName == style.selectedItem }?.let {
                emit(
                    StudioGlowEdit.Style(it),
                )
            }
        }
        animation.addActionListener {
            GlowAnimation.entries
                .firstOrNull {
                    it.displayName == animation.selectedItem
                }?.let { emit(StudioGlowEdit.Animation(it)) }
        }
        intensity.addChangeListener { emit(StudioGlowEdit.Intensity(intensity.value)) }
        width.addChangeListener { emit(StudioGlowEdit.Width(width.value)) }
        contour.add(panel { buildControls() }.apply { isOpaque = false }, BorderLayout.CENTER)
        add(contour, BorderLayout.CENTER)
    }

    fun render(
        draft: GlowSettings,
        editable: Boolean,
        licensed: Boolean,
        pendingBadges: List<String>,
    ) {
        rendering = true
        try {
            settings = draft
            locked.set(!licensed)
            editing.set(editable && licensed)
            editingEffects.set(editing.get() && draft.enabled)
            visibility.refresh(draft, licensed)
            enable.isSelected = draft.enabled
            preset.render(draft.preset, editingEffects.get())
            editorPlacement.render(draft.editorPlacement, editingEffects.get())
            toolPlacement.render(draft.toolWindowPlacement, editingEffects.get())
            style.selectedItem = draft.style.displayName
            animation.selectedItem = draft.animation.displayName
            val activeGate = gate.copy(isUnlocked = licensed)
            intensity.applyPremiumLock(activeGate, editingEffects.get())
            width.applyPremiumLock(activeGate, editingEffects.get())
            intensity.display(draft.intensity.getValue(draft.style), 0, MAX_INTENSITY, intensityReadout)
            width.display(draft.width.getValue(draft.style), MIN_WIDTH, MAX_WIDTH, widthReadout)
            targets.forEach { (target, control) -> control.isSelected = draft.islandToggles.getValue(target.islandId) }
            waveform.refresh(draft.waveformValue())
            waveform.setEnabled(editingEffects.get())
            badges.forEach { (id, cell) -> cell.visible(id in pendingBadges) }
        } finally {
            rendering = false
        }
    }

    fun preview(value: GlowPreview) {
        contour.updatePreview(value)
    }

    private fun Panel.buildControls() {
        row { comment(PRO_DESCRIPTION) }.visibleIf(locked)
        row { action("Unlock Pro") { if (locked.get()) onUnlock() } }.visibleIf(locked)
        row {
            cell(enable).enabledIf(editing)
            action("Reset defaults") { emit(StudioGlowEdit.Reset) }.enabledIf(editing)
        }
        group("Style") {
            waveform.build(this)
            row("Preset") {
                cell(preset)
            }.visibleIf(visibility.solidShape)
            row("Style") { cell(style) }.visibleIf(visibility.solidControls).enabledIf(editingEffects)
            buildSlider("Intensity", intensity, intensityReadout, INTENSITY_TICK)
            buildSlider("Width (px)", width, widthReadout, WIDTH_TICK)
            row("Animation") { cell(animation) }.visibleIf(visibility.solidControls).enabledIf(editingEffects)
        }
        buildPlacement()
        buildTargets()
    }

    private fun Panel.buildSlider(
        title: String,
        slider: JSlider,
        readout: JLabel,
        tick: Int,
    ) {
        slider.majorTickSpacing = tick
        slider.paintTicks = true
        row(title) {
            cell(slider).resizableColumn().align(Align.FILL)
            cell(readout)
        }.visibleIf(visibility.solidControls).enabledIf(editingEffects)
    }

    private fun Panel.buildPlacement() {
        group("Placement") {
            row("Editor placement") {
                cell(editorPlacement)
                badge("glow-placement")
            }
            row("Tool window placement") { cell(toolPlacement) }
            row { comment("Island glows the full frame; Side edges glows only the left and right strips.") }
        }.visibleIf(visibility.solidShape)
    }

    private fun Panel.buildTargets() {
        collapsibleGroup("Targets") {
            row {
                action("Enable all") { setTargets(true) }
                action("Disable all") { setTargets(false) }
            }.enabledIf(editingEffects)
            row { comment("Waveform runs the full island perimeter.") }.visibleIf(visibility.waveform)
            threeColumnsRow({ label("Workspace").bold() }, { label("Output").bold() }, { label("VCS").bold() })
            threeColumnsRow({
                target(StudioGlowTarget.EDITOR)
            }, { target(StudioGlowTarget.TERMINAL) }, { target(StudioGlowTarget.GIT) })
            threeColumnsRow({
                target(StudioGlowTarget.PROJECT)
            }, { target(StudioGlowTarget.RUN) }, { target(StudioGlowTarget.SERVICES) })
            threeColumnsRow({}, { target(StudioGlowTarget.DEBUG) }, {})
        }.visibleIf(visibility.targets)
    }

    private fun Row.target(target: StudioGlowTarget) {
        val checkbox = JCheckBox(target.islandId)
        checkbox.addActionListener {
            emit(StudioGlowEdit.Targets(StudioList(listOf(target)), checkbox.isSelected))
        }
        targets[target] = checkbox
        cell(checkbox).enabledIf(editingEffects)
    }

    private fun setTargets(enabled: Boolean) {
        emit(StudioGlowEdit.Targets(StudioList(targets.keys), enabled))
    }

    private fun Row.badge(id: String) {
        badges[id] = cell(newFeatureBadgeLabel(pending = false)).visible(false)
    }

    private fun editWaveform(value: WaveformSettingsValue) {
        val previous = settings.waveformValue()
        val edit =
            when {
                value.shape != previous.shape -> StudioGlowEdit.Shape(value.shape)
                value.movement != previous.movement -> StudioGlowEdit.Movement(value.movement)
                value.baseline != previous.baseline -> StudioGlowEdit.Baseline(value.baseline)
                value.traceDensity != previous.traceDensity -> StudioGlowEdit.TraceDensity(value.traceDensity)
                value.traceLength != previous.traceLength -> StudioGlowEdit.TraceLength(value.traceLength)
                value.amplitude != previous.amplitude -> StudioGlowEdit.Amplitude(value.amplitude)
                value.intensity != previous.intensity -> StudioGlowEdit.WaveformIntensity(value.intensity)
                value.loopSeconds.toRawBits() != previous.loopSeconds.toRawBits() ->
                    StudioGlowEdit.LoopSeconds(
                        value.loopSeconds,
                    )
                else -> return
            }
        emit(edit)
    }

    private fun emit(edit: StudioGlowEdit) {
        if (rendering) return
        val permitted =
            when (edit) {
                is StudioGlowEdit.Enable, StudioGlowEdit.Reset -> editing.get()
                else -> editingEffects.get()
            }
        if (permitted) onEdit(edit)
    }

    private companion object {
        const val PRO_DESCRIPTION = "Glow is a Pro feature. Preview styles and waveform effects here."
        const val MAX_INTENSITY = 100
        const val MIN_WIDTH = 2
        const val MAX_WIDTH = 16
        const val INTENSITY_TICK = 25
        const val WIDTH_TICK = 2
    }
}

private class GlowChoice<T>(
    choices: List<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) : JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)) {
    private val buttons =
        choices.associateWith { choice ->
            JToggleButton(label(choice)).apply { addActionListener { onSelect(choice) } }
        }

    init {
        isOpaque = false
        val group = ButtonGroup()
        for (button in buttons.values) {
            group.add(button)
            add(button)
        }
    }

    fun render(
        selected: T,
        editable: Boolean,
    ) {
        buttons.getValue(selected).isSelected = true
        buttons.values.forEach { it.isEnabled = editable }
    }
}

private fun JSlider.display(
    current: Int,
    minimum: Int,
    maximum: Int,
    readout: JLabel,
) {
    this.minimum = minOf(minimum, current)
    this.maximum = maxOf(maximum, current)
    value = current
    readout.text = current.toString()
}

private fun Row.action(
    title: String,
    perform: () -> Unit,
): Cell<ActionLink> = link(title) { perform() }.applyToComponent { autoHideOnDisable = false }
