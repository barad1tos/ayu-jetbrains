package dev.ayuislands.studio.model

import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowPlacement
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.glow.GlowShape
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.waveform.WaveformBaseline
import dev.ayuislands.glow.waveform.WaveformMovement

internal sealed interface StudioGlowEdit {
    sealed interface Waveform : StudioGlowEdit

    data class Enable(
        val enabled: Boolean,
    ) : StudioGlowEdit

    data class Preset(
        val value: GlowPreset,
    ) : StudioGlowEdit

    data class Style(
        val value: GlowStyle,
    ) : StudioGlowEdit

    data class Intensity(
        val value: Int,
    ) : StudioGlowEdit

    data class Width(
        val value: Int,
    ) : StudioGlowEdit

    data class Animation(
        val value: GlowAnimation,
    ) : StudioGlowEdit

    data class EditorPlacement(
        val value: GlowPlacement,
    ) : StudioGlowEdit

    data class ToolWindowPlacement(
        val value: GlowPlacement,
    ) : StudioGlowEdit

    data class Targets(
        val targets: StudioList<StudioGlowTarget>,
        val enabled: Boolean,
    ) : StudioGlowEdit

    data class Shape(
        val value: GlowShape,
    ) : Waveform

    data class Movement(
        val value: WaveformMovement,
    ) : Waveform

    data class Baseline(
        val value: WaveformBaseline,
    ) : Waveform

    data class TraceDensity(
        val value: Int,
    ) : Waveform

    data class TraceLength(
        val value: Int,
    ) : Waveform

    data class Amplitude(
        val value: Int,
    ) : Waveform

    data class WaveformIntensity(
        val value: Int,
    ) : Waveform

    data class LoopSeconds(
        val value: Float,
    ) : Waveform

    data object Reset : StudioGlowEdit
}
