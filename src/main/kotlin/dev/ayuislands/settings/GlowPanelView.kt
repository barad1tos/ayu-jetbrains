package dev.ayuislands.settings

import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowPlacement
import dev.ayuislands.glow.GlowPreset
import dev.ayuislands.glow.GlowShape
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.waveform.WaveformBaseline
import dev.ayuislands.glow.waveform.WaveformMovement

/** Requested controls and the independently selected local Current/Draft contour. */
internal data class GlowPanelView(
    val settings: GlowSettings,
    val preview: GlowPreview,
    val isEditable: Boolean,
    val isLicensed: Boolean,
    val pendingBadges: Set<String>,
)

/** One explicit native action, without persistence or runtime ownership. */
internal sealed interface GlowPanelEvent {
    data class Enable(
        val value: Boolean,
    ) : GlowPanelEvent

    data class Preset(
        val value: GlowPreset,
    ) : GlowPanelEvent

    data class Style(
        val value: GlowStyle,
    ) : GlowPanelEvent

    data class Intensity(
        val value: Int,
    ) : GlowPanelEvent

    data class Width(
        val value: Int,
    ) : GlowPanelEvent

    data class Animation(
        val value: GlowAnimation,
    ) : GlowPanelEvent

    data class EditorPlacement(
        val value: GlowPlacement,
    ) : GlowPanelEvent

    data class ToolWindowPlacement(
        val value: GlowPlacement,
    ) : GlowPanelEvent

    data class Island(
        val id: String,
        val value: Boolean,
    ) : GlowPanelEvent

    data class AllIslands(
        val value: Boolean,
    ) : GlowPanelEvent

    data class Shape(
        val value: GlowShape,
    ) : WaveformPanelEvent

    data class Movement(
        val value: WaveformMovement,
    ) : WaveformPanelEvent

    data class Baseline(
        val value: WaveformBaseline,
    ) : WaveformPanelEvent

    data class TraceDensity(
        val value: Int,
    ) : WaveformPanelEvent

    data class TraceLength(
        val value: Int,
    ) : WaveformPanelEvent

    data class Amplitude(
        val value: Int,
    ) : WaveformPanelEvent

    data class WaveformIntensity(
        val value: Int,
    ) : WaveformPanelEvent

    data class LoopSeconds(
        val value: Float,
    ) : WaveformPanelEvent

    data class BadgeSeen(
        val id: String,
    ) : GlowPanelEvent

    data object Reset : GlowPanelEvent

    data object Unlock : GlowPanelEvent
}

/** Waveform controls cannot report changes to dormant solid settings. */
internal sealed interface WaveformPanelEvent : GlowPanelEvent
