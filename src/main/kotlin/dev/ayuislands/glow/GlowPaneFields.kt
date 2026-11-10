package dev.ayuislands.glow

import dev.ayuislands.glow.waveform.WaveformConfig

internal enum class GlowPaneField {
    COLOR,
    STYLE,
    INTENSITY,
    WIDTH,
    PLACEMENT,
    SHAPE,
    MOVEMENT,
    BASELINE,
    AMPLITUDE,
    WAVEFORM_INTENSITY,
    LOOP_SECONDS,
    TRACE_DENSITY,
    TRACE_LENGTH,
    VISIBILITY,
}

/** Field comparisons and merges never read or mutate a Swing component. */
internal object GlowPaneFields {
    fun matches(
        field: GlowPaneField,
        actual: GlowPaneAppearance,
        expected: GlowPaneAppearance,
    ): Boolean =
        when (field) {
            GlowPaneField.COLOR -> actual.color === expected.color
            GlowPaneField.STYLE -> actual.style == expected.style
            GlowPaneField.INTENSITY -> actual.intensity == expected.intensity
            GlowPaneField.WIDTH -> actual.width == expected.width
            GlowPaneField.PLACEMENT -> actual.placement == expected.placement
            GlowPaneField.SHAPE -> actual.shape == expected.shape
            GlowPaneField.VISIBILITY -> actual.isVisible == expected.isVisible
            GlowPaneField.MOVEMENT,
            GlowPaneField.BASELINE,
            GlowPaneField.AMPLITUDE,
            GlowPaneField.WAVEFORM_INTENSITY,
            GlowPaneField.LOOP_SECONDS,
            GlowPaneField.TRACE_DENSITY,
            GlowPaneField.TRACE_LENGTH,
            -> matchesWaveform(field, actual.waveform, expected.waveform)
        }

    fun replace(
        field: GlowPaneField,
        current: GlowPaneAppearance,
        source: GlowPaneAppearance,
    ): GlowPaneAppearance =
        when (field) {
            GlowPaneField.COLOR -> current.copy(color = source.color)
            GlowPaneField.STYLE -> current.copy(style = source.style)
            GlowPaneField.INTENSITY -> current.copy(intensity = source.intensity)
            GlowPaneField.WIDTH -> current.copy(width = source.width)
            GlowPaneField.PLACEMENT -> current.copy(placement = source.placement)
            GlowPaneField.SHAPE -> current.copy(shape = source.shape)
            GlowPaneField.VISIBILITY -> current.copy(isVisible = source.isVisible)
            GlowPaneField.MOVEMENT,
            GlowPaneField.BASELINE,
            GlowPaneField.AMPLITUDE,
            GlowPaneField.WAVEFORM_INTENSITY,
            GlowPaneField.LOOP_SECONDS,
            GlowPaneField.TRACE_DENSITY,
            GlowPaneField.TRACE_LENGTH,
            -> current.copy(waveform = replaceWaveform(field, current.waveform, source.waveform))
        }

    private fun matchesWaveform(
        field: GlowPaneField,
        actual: WaveformConfig,
        expected: WaveformConfig,
    ): Boolean =
        when (field) {
            GlowPaneField.MOVEMENT -> actual.movement == expected.movement
            GlowPaneField.BASELINE -> actual.baseline == expected.baseline
            GlowPaneField.AMPLITUDE -> actual.amplitude == expected.amplitude
            GlowPaneField.WAVEFORM_INTENSITY -> actual.intensity == expected.intensity
            GlowPaneField.LOOP_SECONDS -> actual.loopSeconds.toRawBits() == expected.loopSeconds.toRawBits()
            GlowPaneField.TRACE_DENSITY -> actual.traceDensity == expected.traceDensity
            GlowPaneField.TRACE_LENGTH -> actual.traceLength == expected.traceLength
            else -> error("Not a waveform field: $field")
        }

    private fun replaceWaveform(
        field: GlowPaneField,
        current: WaveformConfig,
        source: WaveformConfig,
    ): WaveformConfig =
        when (field) {
            GlowPaneField.MOVEMENT -> current.copy(movement = source.movement)
            GlowPaneField.BASELINE -> current.copy(baseline = source.baseline)
            GlowPaneField.AMPLITUDE -> current.copy(amplitude = source.amplitude)
            GlowPaneField.WAVEFORM_INTENSITY -> current.copy(intensity = source.intensity)
            GlowPaneField.LOOP_SECONDS -> current.copy(loopSeconds = source.loopSeconds)
            GlowPaneField.TRACE_DENSITY -> current.copy(traceDensity = source.traceDensity)
            GlowPaneField.TRACE_LENGTH -> current.copy(traceLength = source.traceLength)
            else -> error("Not a waveform field: $field")
        }
}
