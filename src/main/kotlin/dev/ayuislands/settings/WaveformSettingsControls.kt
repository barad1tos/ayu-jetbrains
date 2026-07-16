package dev.ayuislands.settings

import com.intellij.openapi.observable.properties.AtomicBooleanProperty
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.DslComponentProperty
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.SegmentedButton
import com.intellij.ui.dsl.gridLayout.UnscaledGaps
import com.intellij.util.ui.JBUI
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
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ActionListener
import java.util.Locale
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.SwingConstants
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
    internal var densitySegmentedButton: SegmentedButton<Int>? = null
    internal var traceLengthSlider: JSlider? = null
    internal var amplitudeSlider: JSlider? = null
    internal var intensitySlider: JSlider? = null
    internal var loopSlider: JSlider? = null

    private var amplitudeLabel: JLabel? = null
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
        buildDensityRow(group)
        buildSlider(
            group,
            WaveformSliderSpec(
                label = "Trace length (px)",
                range = MIN_TRACE_LENGTH..MAX_TRACE_LENGTH,
                initialValue = value.traceLength.coerceIn(MIN_TRACE_LENGTH, MAX_TRACE_LENGTH),
                tickValues = MIN_TRACE_LENGTH..MAX_TRACE_LENGTH step 40,
                majorTickValues = 200..MAX_TRACE_LENGTH step 200,
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
                tickValues = MIN_WAVEFORM_AMPLITUDE..MAX_WAVEFORM_AMPLITUDE step 2,
                majorTickValues = MIN_WAVEFORM_AMPLITUDE..MAX_WAVEFORM_AMPLITUDE step 8,
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
                tickValues = MIN_WAVEFORM_INTENSITY..MAX_WAVEFORM_INTENSITY step 10,
                majorTickValues = MIN_WAVEFORM_INTENSITY..MAX_WAVEFORM_INTENSITY step 50,
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
        densitySegmentedButton?.selectedItem = displayedDensity
        val displayedTraceLength = value.traceLength.coerceIn(MIN_TRACE_LENGTH, MAX_TRACE_LENGTH)
        traceLengthSlider?.value = displayedTraceLength
        val displayedAmplitude = value.amplitude.coerceIn(MIN_WAVEFORM_AMPLITUDE, MAX_WAVEFORM_AMPLITUDE)
        amplitudeSlider?.value = displayedAmplitude
        val displayedIntensity = value.intensity.coerceIn(MIN_WAVEFORM_INTENSITY, MAX_WAVEFORM_INTENSITY)
        val displayedLoopSeconds = value.loopSeconds.normalizedLoopSeconds()
        intensitySlider?.value = displayedIntensity
        loopSlider?.value = secondsToTenths(displayedLoopSeconds)
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
        densitySegmentedButton?.enabled(enabled)
        traceLengthSlider?.isEnabled = enabled
        amplitudeSlider?.isEnabled = enabled
        intensitySlider?.isEnabled = enabled
        loopSlider?.isEnabled = enabled
    }

    private fun buildShapeRow(group: Panel) {
        group.row("Shape") {
            val combo = enumCombo(GlowShape.entries.map { it.displayName })
            combo.selectedItem = value.shape.displayName
            combo.addActionListener(
                guardedAction {
                    val selected = combo.selectedItem as? String ?: return@guardedAction
                    update(value.copy(shape = GlowShape.entries.first { it.displayName == selected }))
                },
            )
            shapeCombo = combo
            cell(combo).widthGroup(WAVEFORM_COMBO_GROUP)
            newFeatureBadge("glow-waveform")
        }
    }

    private fun buildDirectionRow(group: Panel) {
        group
            .row("Direction") {
                val combo = enumCombo(WaveformDirection.entries.map { it.displayName })
                combo.selectedItem = value.direction.displayName
                combo.addActionListener(
                    guardedAction {
                        val selected = combo.selectedItem as? String ?: return@guardedAction
                        update(value.copy(direction = WaveformDirection.entries.first { it.displayName == selected }))
                    },
                )
                directionCombo = combo
                cell(combo).widthGroup(WAVEFORM_COMBO_GROUP)
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
                cell(combo).widthGroup(WAVEFORM_COMBO_GROUP)
            }.visibleIf(visibility.waveform)
    }

    private fun buildLoopRow(group: Panel) {
        group
            .row("Loop duration") {
                val displayedSeconds = value.loopSeconds.normalizedLoopSeconds()
                val slider =
                    JSlider(
                        secondsToTenths(MIN_WAVEFORM_LOOP_SECONDS),
                        secondsToTenths(MAX_WAVEFORM_LOOP_SECONDS),
                        secondsToTenths(displayedSeconds),
                    )
                slider.paintTicks = false
                slider.applyPremiumLock(gate, enabledWhenUnlocked = true)
                val label = JLabel(formatSeconds(displayedSeconds)).apply { horizontalAlignment = SwingConstants.RIGHT }
                slider.addChangeListener {
                    val seconds = slider.value / TENTHS_PER_SECOND
                    if (!refreshing && gate.isUnlocked) update(value.copy(loopSeconds = seconds))
                    label.text = formatSeconds(seconds)
                }
                loopSlider = slider
                loopLabel = label
                cell(
                    sliderRail(
                        slider,
                        secondsToTenths(LOOP_TICK_SECONDS)..secondsToTenths(MAX_WAVEFORM_LOOP_SECONDS) step
                            secondsToTenths(LOOP_TICK_SECONDS),
                        secondsToTenths(LOOP_MAJOR_SECONDS)..secondsToTenths(MAX_WAVEFORM_LOOP_SECONDS) step
                            secondsToTenths(LOOP_MAJOR_SECONDS),
                    ),
                ).resizableColumn().align(Align.FILL)
                cell(label)
                    .widthGroup(WAVEFORM_VALUE_GROUP)
                    .customize(UnscaledGaps(0, 0, 0, VALUE_RIGHT_INSET))
            }.visibleIf(visibility.waveform)
    }

    private fun buildDensityRow(group: Panel) {
        group
            .row("Spike density") {
                val segmented =
                    segmentedButton((MIN_TRACE_DENSITY..MAX_TRACE_DENSITY).toList()) { density ->
                        text = "$density×"
                        if (!gate.isUnlocked) toolTipText = gate.tooltip
                    }
                segmented.selectedItem = value.traceDensity.coerceIn(MIN_TRACE_DENSITY, MAX_TRACE_DENSITY)
                segmented.enabled(gate.isUnlocked)
                @Suppress("UnstableApiUsage")
                segmented.whenItemSelected { density ->
                    update(value.copy(traceDensity = density))
                }
                segmented.align(Align.FILL).resizableColumn()
                cell(JLabel(""))
                    .widthGroup(WAVEFORM_VALUE_GROUP)
                    .customize(UnscaledGaps(0, 0, 0, VALUE_RIGHT_INSET))
                densitySegmentedButton = segmented
            }.visibleIf(visibility.waveform)
    }

    private fun buildSlider(
        group: Panel,
        spec: WaveformSliderSpec,
    ) {
        group
            .row(spec.label) {
                val slider = JSlider(spec.range.first, spec.range.last, spec.initialValue)
                slider.paintTicks = false
                slider.applyPremiumLock(gate, enabledWhenUnlocked = true)
                val label = JLabel("${slider.value}").apply { horizontalAlignment = SwingConstants.RIGHT }
                slider.addChangeListener {
                    if (!refreshing && gate.isUnlocked) spec.onChange(slider.value)
                    label.text = "${slider.value}"
                }
                spec.onCreated(slider, label)
                cell(sliderRail(slider, spec.tickValues, spec.majorTickValues)).resizableColumn().align(Align.FILL)
                cell(label)
                    .widthGroup(WAVEFORM_VALUE_GROUP)
                    .customize(UnscaledGaps(0, 0, 0, VALUE_RIGHT_INSET))
            }.visibleIf(spec.visibleWhen)
    }

    private fun sliderRail(
        slider: JSlider,
        tickValues: IntProgression,
        majorTickValues: IntProgression,
    ): JPanel =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            putClientProperty(DslComponentProperty.INTERACTIVE_COMPONENT, slider)
            add(slider, BorderLayout.CENTER)
            add(SliderTickStrip(slider, tickValues, majorTickValues), BorderLayout.SOUTH)
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
        val tickValues: IntProgression,
        val majorTickValues: IntProgression,
        val visibleWhen: AtomicBooleanProperty,
        val onChange: (Int) -> Unit,
        val onCreated: (JSlider, JLabel) -> Unit,
    )

    private companion object {
        const val LOOP_MAJOR_SECONDS = 10f
        const val LOOP_TICK_SECONDS = 2f
        const val TENTHS_PER_SECOND = 10f
        const val WAVEFORM_COMBO_GROUP = "waveform-combo"
        const val WAVEFORM_VALUE_GROUP = "waveform-value"
        const val VALUE_RIGHT_INSET = 12
    }
}

internal class SliderTickStrip(
    private val slider: JSlider,
    private val tickValues: IntProgression,
    private val majorTickValues: IntProgression,
) : JComponent() {
    init {
        isFocusable = false
        isOpaque = false
        preferredSize = Dimension(0, JBUI.scale(MAJOR_TICK_HEIGHT))
        minimumSize = preferredSize
        slider.addPropertyChangeListener("enabled") { repaint() }
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        if (tickValues.isEmpty() || width <= 0 || slider.maximum <= slider.minimum) return

        val graphics2D = graphics.create() as Graphics2D
        try {
            graphics2D.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
            graphics2D.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_NORMALIZE)
            graphics2D.color = if (slider.isEnabled) TICK_COLOR else DISABLED_TICK_COLOR
            graphics2D.stroke = BasicStroke(JBUI.scale(1).toFloat())
            paintTicks(graphics2D)
        } finally {
            graphics2D.dispose()
        }
    }

    private fun paintTicks(graphics: Graphics2D) {
        val trackInset = JBUI.scale(TRACK_INSET)
        val trackWidth = (width - trackInset * 2 - 1).coerceAtLeast(0)
        val sliderSpan = slider.maximum - slider.minimum
        for (value in tickValues) {
            val fraction = (value - slider.minimum).toFloat() / sliderSpan
            val x = trackInset + (trackWidth * fraction).roundToInt()
            val height = if (value in majorTickValues) MAJOR_TICK_HEIGHT else MINOR_TICK_HEIGHT
            graphics.drawLine(x, 0, x, (JBUI.scale(height) - 1).coerceAtLeast(0))
        }
    }

    private companion object {
        val TICK_COLOR = JBColor.namedColor("Slider.tickColor", JBColor.GRAY)
        val DISABLED_TICK_COLOR = JBColor.namedColor("Component.disabledBorderColor", JBColor.GRAY)

        const val MAJOR_TICK_HEIGHT = 7
        const val MINOR_TICK_HEIGHT = 4
        const val TRACK_INSET = 7
    }
}
