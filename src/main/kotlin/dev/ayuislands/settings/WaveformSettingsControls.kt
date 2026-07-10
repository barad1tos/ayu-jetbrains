package dev.ayuislands.settings

import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import dev.ayuislands.glow.GlowShape
import dev.ayuislands.glow.waveform.MAX_WAVEFORM_AMPLITUDE
import dev.ayuislands.glow.waveform.MAX_WAVEFORM_INTENSITY
import dev.ayuislands.glow.waveform.MIN_WAVEFORM_AMPLITUDE
import dev.ayuislands.glow.waveform.MIN_WAVEFORM_INTENSITY
import dev.ayuislands.glow.waveform.WaveformDirection
import dev.ayuislands.glow.waveform.WaveformMotion
import java.awt.event.ActionListener
import javax.swing.DefaultComboBoxModel
import javax.swing.JLabel
import javax.swing.JSlider

internal data class WaveformSettingsValue(
    val shape: GlowShape,
    val motion: WaveformMotion,
    val direction: WaveformDirection,
    val amplitude: Int,
    val intensity: Int,
)

internal data class WaveformControlVisibility(
    val waveform: AtomicBooleanProperty,
    val direction: AtomicBooleanProperty,
)

internal class WaveformSettingsControls(
    initial: WaveformSettingsValue,
    private val gate: PremiumFeatureGate,
    private val visibility: WaveformControlVisibility,
    private val onChange: (WaveformSettingsValue) -> Unit,
) {
    internal var shapeCombo: ComboBox<String>? = null
    internal var motionCombo: ComboBox<String>? = null
    internal var directionCombo: ComboBox<String>? = null
    internal var amplitudeSlider: JSlider? = null
    internal var intensitySlider: JSlider? = null

    private var amplitudeLabel: JLabel? = null
    private var intensityLabel: JLabel? = null
    private var value = initial
    private var refreshing = false

    fun build(group: Panel) {
        buildShapeRow(group)
        buildMotionRow(group)
        buildDirectionRow(group)
        buildSlider(
            group,
            WaveformSliderSpec(
                label = "Amplitude (px)",
                range = MIN_WAVEFORM_AMPLITUDE..MAX_WAVEFORM_AMPLITUDE,
                initialValue = value.amplitude,
                majorTick = AMPLITUDE_MAJOR_TICK,
                onChange = { update(value.copy(amplitude = it)) },
                onCreated = { slider, label ->
                    amplitudeSlider = slider
                    amplitudeLabel = label
                },
            ),
        )
        buildSlider(
            group,
            WaveformSliderSpec(
                label = "Intensity",
                range = MIN_WAVEFORM_INTENSITY..MAX_WAVEFORM_INTENSITY,
                initialValue = value.intensity,
                majorTick = INTENSITY_MAJOR_TICK,
                onChange = { update(value.copy(intensity = it)) },
                onCreated = { slider, label ->
                    intensitySlider = slider
                    intensityLabel = label
                },
            ),
        )
    }

    fun refresh(newValue: WaveformSettingsValue) {
        refreshing = true
        value = newValue
        shapeCombo?.selectedItem = value.shape.displayName
        motionCombo?.selectedItem = value.motion.displayName
        directionCombo?.selectedItem = value.direction.displayName
        amplitudeSlider?.value = value.amplitude
        intensitySlider?.value = value.intensity
        amplitudeLabel?.text = "${value.amplitude}"
        intensityLabel?.text = "${value.intensity}"
        refreshing = false
    }

    fun setEnabled(enabled: Boolean) {
        shapeCombo?.isEnabled = enabled
        motionCombo?.isEnabled = enabled
        directionCombo?.isEnabled = enabled
        amplitudeSlider?.isEnabled = enabled
        intensitySlider?.isEnabled = enabled
    }

    private fun buildShapeRow(group: Panel) {
        group.row {
            label("Shape")
            val combo = enumCombo(GlowShape.entries.map { it.displayName })
            combo.selectedItem = value.shape.displayName
            combo.addActionListener(
                guardedAction {
                    val selected = combo.selectedItem as? String ?: return@guardedAction
                    update(value.copy(shape = GlowShape.entries.first { it.displayName == selected }))
                },
            )
            shapeCombo = combo
            cell(combo)
            newFeatureBadge("glow-waveform")
        }
    }

    private fun buildMotionRow(group: Panel) {
        group
            .row {
                label("Motion")
                val combo = enumCombo(WaveformMotion.entries.map { it.displayName })
                combo.selectedItem = value.motion.displayName
                combo.addActionListener(
                    guardedAction {
                        val selected = combo.selectedItem as? String ?: return@guardedAction
                        update(value.copy(motion = WaveformMotion.entries.first { it.displayName == selected }))
                    },
                )
                motionCombo = combo
                cell(combo)
            }.visibleIf(visibility.waveform)
    }

    private fun buildDirectionRow(group: Panel) {
        group
            .row {
                label("Direction")
                val combo = enumCombo(WaveformDirection.entries.map { it.displayName })
                combo.selectedItem = value.direction.displayName
                combo.addActionListener(
                    guardedAction {
                        val selected = combo.selectedItem as? String ?: return@guardedAction
                        update(value.copy(direction = WaveformDirection.entries.first { it.displayName == selected }))
                    },
                )
                directionCombo = combo
                cell(combo)
            }.visibleIf(visibility.direction)
    }

    private fun buildSlider(
        group: Panel,
        spec: WaveformSliderSpec,
    ) {
        group
            .row {
                label(spec.label)
                val slider = JSlider(spec.range.first, spec.range.last, spec.initialValue)
                slider.paintTicks = true
                slider.majorTickSpacing = spec.majorTick
                slider.applyPremiumLock(gate, enabledWhenUnlocked = true)
                val label = JLabel("${slider.value}")
                slider.addChangeListener {
                    if (!refreshing && gate.isUnlocked) spec.onChange(slider.value)
                    label.text = "${slider.value}"
                }
                spec.onCreated(slider, label)
                cell(slider).resizableColumn().align(Align.FILL)
                cell(label)
            }.visibleIf(visibility.waveform)
    }

    private fun update(newValue: WaveformSettingsValue) {
        if (refreshing || !gate.isUnlocked) return
        value = newValue
        onChange(value)
    }

    private fun enumCombo(items: List<String>): ComboBox<String> =
        ComboBox(DefaultComboBoxModel(items.toTypedArray())).also { it.isEnabled = gate.isUnlocked }

    private fun guardedAction(action: () -> Unit): ActionListener =
        ActionListener {
            if (!refreshing && gate.isUnlocked) action()
        }

    private data class WaveformSliderSpec(
        val label: String,
        val range: IntRange,
        val initialValue: Int,
        val majorTick: Int,
        val onChange: (Int) -> Unit,
        val onCreated: (JSlider, JLabel) -> Unit,
    )

    private companion object {
        const val AMPLITUDE_MAJOR_TICK = 2
        const val INTENSITY_MAJOR_TICK = 25
    }
}
