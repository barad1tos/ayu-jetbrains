package dev.ayuislands.settings

import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Panel
import dev.ayuislands.glow.GlowShape
import dev.ayuislands.glow.waveform.MAX_TRACE_DENSITY
import dev.ayuislands.glow.waveform.MAX_TRACE_LENGTH
import dev.ayuislands.glow.waveform.MAX_WAVEFORM_AMPLITUDE
import dev.ayuislands.glow.waveform.MAX_WAVEFORM_INTENSITY
import dev.ayuislands.glow.waveform.MAX_WAVEFORM_LOOP_SECONDS
import dev.ayuislands.glow.waveform.MIN_TRACE_DENSITY
import dev.ayuislands.glow.waveform.MIN_TRACE_LENGTH
import dev.ayuislands.glow.waveform.MIN_WAVEFORM_AMPLITUDE
import dev.ayuislands.glow.waveform.MIN_WAVEFORM_INTENSITY
import dev.ayuislands.glow.waveform.MIN_WAVEFORM_LOOP_SECONDS
import dev.ayuislands.glow.waveform.WaveformBaseline
import dev.ayuislands.glow.waveform.WaveformDirection
import dev.ayuislands.glow.waveform.normalizedLoopSeconds
import java.awt.event.ActionListener
import java.util.Locale
import javax.swing.DefaultComboBoxModel
import javax.swing.JLabel
import javax.swing.JSlider
import kotlin.math.roundToInt

internal data class WaveformSettingsValue(
    val shape: GlowShape,
    val direction: WaveformDirection,
    val baseline: WaveformBaseline,
    val traceDensity: Int,
    val traceLength: Int,
    val amplitude: Int,
    val intensity: Int,
    val loopSeconds: Float,
)

internal data class WaveformControlVisibility(
    val waveform: AtomicBooleanProperty,
)

internal class WaveformSettingsControls(
    initial: WaveformSettingsValue,
    private val gate: PremiumFeatureGate,
    private val visibility: WaveformControlVisibility,
    private val onChange: (WaveformSettingsValue) -> Unit,
) {
    internal var shapeCombo: ComboBox<String>? = null
    internal var directionCombo: ComboBox<String>? = null
    internal var baselineCombo: ComboBox<String>? = null
    internal var densitySlider: JSlider? = null
    internal var traceLengthSlider: JSlider? = null
    internal var amplitudeSlider: JSlider? = null
    internal var intensitySlider: JSlider? = null
    internal var loopSlider: JSlider? = null

    private var amplitudeLabel: JLabel? = null
    private var densityLabel: JLabel? = null
    private var traceLengthLabel: JLabel? = null
    private var intensityLabel: JLabel? = null
    private var loopLabel: JLabel? = null
    private var value = initial
    private var refreshing = false

    fun build(group: Panel) {
        buildShapeRow(group)
        buildDirectionRow(group)
        buildBaselineRow(group)
        buildLoopRow(group)
        buildSlider(
            group,
            WaveformSliderSpec(
                label = "Spike density",
                range = MIN_TRACE_DENSITY..MAX_TRACE_DENSITY,
                initialValue = value.traceDensity.coerceIn(MIN_TRACE_DENSITY, MAX_TRACE_DENSITY),
                majorTick = DENSITY_MAJOR_TICK,
                visibleWhen = visibility.waveform,
                formatValue = { "$it×" },
                onChange = { update(value.copy(traceDensity = it)) },
                onCreated = { slider, label ->
                    densitySlider = slider
                    densityLabel = label
                },
            ),
        )
        buildSlider(
            group,
            WaveformSliderSpec(
                label = "Trace length (px)",
                range = MIN_TRACE_LENGTH..MAX_TRACE_LENGTH,
                initialValue = value.traceLength.coerceIn(MIN_TRACE_LENGTH, MAX_TRACE_LENGTH),
                majorTick = TRACE_LENGTH_TICK,
                visibleWhen = visibility.waveform,
                onChange = { update(value.copy(traceLength = it)) },
                onCreated = { slider, label ->
                    traceLengthSlider = slider
                    traceLengthLabel = label
                },
            ),
        )
        buildSlider(
            group,
            WaveformSliderSpec(
                label = "Amplitude (px)",
                range = MIN_WAVEFORM_AMPLITUDE..MAX_WAVEFORM_AMPLITUDE,
                initialValue = value.amplitude.coerceIn(MIN_WAVEFORM_AMPLITUDE, MAX_WAVEFORM_AMPLITUDE),
                majorTick = AMPLITUDE_MAJOR_TICK,
                visibleWhen = visibility.waveform,
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
                initialValue = value.intensity.coerceIn(MIN_WAVEFORM_INTENSITY, MAX_WAVEFORM_INTENSITY),
                majorTick = INTENSITY_MAJOR_TICK,
                visibleWhen = visibility.waveform,
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
        directionCombo?.selectedItem = value.direction.displayName
        baselineCombo?.selectedItem = value.baseline.displayName
        val displayedDensity = value.traceDensity.coerceIn(MIN_TRACE_DENSITY, MAX_TRACE_DENSITY)
        densitySlider?.value = displayedDensity
        val displayedTraceLength = value.traceLength.coerceIn(MIN_TRACE_LENGTH, MAX_TRACE_LENGTH)
        traceLengthSlider?.value = displayedTraceLength
        val displayedAmplitude = value.amplitude.coerceIn(MIN_WAVEFORM_AMPLITUDE, MAX_WAVEFORM_AMPLITUDE)
        amplitudeSlider?.value = displayedAmplitude
        val displayedIntensity = value.intensity.coerceIn(MIN_WAVEFORM_INTENSITY, MAX_WAVEFORM_INTENSITY)
        val displayedLoopSeconds = value.loopSeconds.normalizedLoopSeconds()
        intensitySlider?.value = displayedIntensity
        loopSlider?.value = secondsToTenths(displayedLoopSeconds)
        densityLabel?.text = "$displayedDensity×"
        traceLengthLabel?.text = "$displayedTraceLength"
        amplitudeLabel?.text = "$displayedAmplitude"
        intensityLabel?.text = "$displayedIntensity"
        loopLabel?.text = formatSeconds(displayedLoopSeconds)
        refreshing = false
    }

    fun setEnabled(enabled: Boolean) {
        shapeCombo?.isEnabled = enabled
        directionCombo?.isEnabled = enabled
        baselineCombo?.isEnabled = enabled
        densitySlider?.isEnabled = enabled
        traceLengthSlider?.isEnabled = enabled
        amplitudeSlider?.isEnabled = enabled
        intensitySlider?.isEnabled = enabled
        loopSlider?.isEnabled = enabled
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
            }.visibleIf(visibility.waveform)
    }

    private fun buildBaselineRow(group: Panel) {
        group
            .row("Trace position") {
                val combo = enumCombo(WaveformBaseline.entries.map { it.displayName })
                combo.selectedItem = value.baseline.displayName
                combo.addActionListener(
                    guardedAction {
                        val selected = combo.selectedItem as? String ?: return@guardedAction
                        update(value.copy(baseline = WaveformBaseline.entries.first { it.displayName == selected }))
                    },
                )
                baselineCombo = combo
                cell(combo)
            }.visibleIf(visibility.waveform)
    }

    private fun buildLoopRow(group: Panel) {
        group
            .row {
                label("Loop duration")
                val displayedSeconds = value.loopSeconds.normalizedLoopSeconds()
                val slider =
                    JSlider(
                        secondsToTenths(MIN_WAVEFORM_LOOP_SECONDS),
                        secondsToTenths(MAX_WAVEFORM_LOOP_SECONDS),
                        secondsToTenths(displayedSeconds),
                    )
                slider.paintTicks = true
                slider.majorTickSpacing = LOOP_MAJOR_TICK
                slider.minorTickSpacing = LOOP_MINOR_TICK
                slider.applyPremiumLock(gate, enabledWhenUnlocked = true)
                val label = JLabel(formatSeconds(displayedSeconds))
                slider.addChangeListener {
                    val seconds = slider.value / TENTHS_PER_SECOND
                    if (!refreshing && gate.isUnlocked) update(value.copy(loopSeconds = seconds))
                    label.text = formatSeconds(seconds)
                }
                loopSlider = slider
                loopLabel = label
                cell(slider).resizableColumn().align(Align.FILL)
                cell(label)
            }.visibleIf(visibility.waveform)
    }

    private fun buildSlider(
        group: Panel,
        spec: WaveformSliderSpec,
    ) {
        group
            .row(spec.label) {
                val slider = JSlider(spec.range.first, spec.range.last, spec.initialValue)
                slider.paintTicks = true
                slider.majorTickSpacing = spec.majorTick
                slider.applyPremiumLock(gate, enabledWhenUnlocked = true)
                val label = JLabel(spec.formatValue(slider.value))
                slider.addChangeListener {
                    if (!refreshing && gate.isUnlocked) spec.onChange(slider.value)
                    label.text = spec.formatValue(slider.value)
                }
                spec.onCreated(slider, label)
                cell(slider).resizableColumn().align(Align.FILL)
                cell(label)
            }.visibleIf(spec.visibleWhen)
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

    private fun secondsToTenths(seconds: Float): Int = (seconds * TENTHS_PER_SECOND).roundToInt()

    private fun formatSeconds(seconds: Float): String = String.format(Locale.ROOT, "%.1f s", seconds)

    private data class WaveformSliderSpec(
        val label: String,
        val range: IntRange,
        val initialValue: Int,
        val majorTick: Int,
        val visibleWhen: AtomicBooleanProperty,
        val formatValue: (Int) -> String = { "$it" },
        val onChange: (Int) -> Unit,
        val onCreated: (JSlider, JLabel) -> Unit,
    )

    private companion object {
        const val AMPLITUDE_MAJOR_TICK = 2
        const val DENSITY_MAJOR_TICK = 1
        const val INTENSITY_MAJOR_TICK = 25
        const val LOOP_MAJOR_TICK = 50
        const val LOOP_MINOR_TICK = 10
        const val TENTHS_PER_SECOND = 10f
        const val TRACE_LENGTH_TICK = 100
    }
}
