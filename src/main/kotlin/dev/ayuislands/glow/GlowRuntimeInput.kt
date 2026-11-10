package dev.ayuislands.glow

import dev.ayuislands.accent.AccentHex
import dev.ayuislands.glow.waveform.MAX_TRACE_DENSITY
import dev.ayuislands.glow.waveform.MAX_TRACE_LENGTH
import dev.ayuislands.glow.waveform.MAX_WAVEFORM_AMPLITUDE
import dev.ayuislands.glow.waveform.MAX_WAVEFORM_INTENSITY
import dev.ayuislands.glow.waveform.MIN_TRACE_DENSITY
import dev.ayuislands.glow.waveform.MIN_TRACE_LENGTH
import dev.ayuislands.glow.waveform.MIN_WAVEFORM_AMPLITUDE
import dev.ayuislands.glow.waveform.MIN_WAVEFORM_INTENSITY
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.glow.waveform.normalizedLoopSeconds
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.GlowSettings
import dev.ayuislands.settings.loadGlowSettings
import dev.ayuislands.studio.persistence.AyuSettingsView

internal data class GlowRuntimeInput(
    val settings: GlowSettings,
    val externalAllowed: Boolean,
    val accent: AccentHex?,
)

internal fun loadGlowRuntimeInput(state: AyuIslandsState): GlowRuntimeInput =
    GlowRuntimeInput(
        settings = loadGlowSettings(state),
        externalAllowed = state.isExternalGlowAllowed(),
        accent = state.trustedCachedAccent(),
    )

internal fun loadGlowRuntimeInput(state: AyuSettingsView): GlowRuntimeInput =
    GlowRuntimeInput(state.glowSettings(), state.isExternalGlowAllowed(), state.trustedCachedAccent())

internal fun resolveWaveformConfig(settings: GlowSettings): WaveformConfig =
    WaveformConfig(
        movement = settings.waveformMovement,
        baseline = settings.waveformBaseline,
        traceDensity = settings.waveformTraceDensity.coerceIn(MIN_TRACE_DENSITY, MAX_TRACE_DENSITY),
        traceLength = settings.waveformTraceLength.coerceIn(MIN_TRACE_LENGTH, MAX_TRACE_LENGTH),
        amplitude = settings.waveformAmplitude.coerceIn(MIN_WAVEFORM_AMPLITUDE, MAX_WAVEFORM_AMPLITUDE),
        intensity = settings.waveformIntensity.coerceIn(MIN_WAVEFORM_INTENSITY, MAX_WAVEFORM_INTENSITY),
        loopSeconds = settings.waveformLoopSeconds.normalizedLoopSeconds(),
    )
