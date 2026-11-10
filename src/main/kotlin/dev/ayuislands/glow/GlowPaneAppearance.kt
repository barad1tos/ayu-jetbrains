package dev.ayuislands.glow

import dev.ayuislands.glow.waveform.WaveformConfig
import java.awt.Color

/** Installed appearance of one pane, excluding animation progress and host geometry. */
internal data class GlowPaneAppearance(
    val color: Color,
    val style: GlowStyle,
    val intensity: Int,
    val width: Int,
    val placement: GlowPlacement,
    val shape: GlowShape,
    val waveform: WaveformConfig,
    val isVisible: Boolean,
)

/** Immutable native evidence; Color identity remains with the retained checkpoint. */
internal data class GlowPaneRecord(
    val before: GlowPaneAppearance,
    val planned: GlowPaneAppearance,
    val phase: GlowCheckpointPhase,
    val fields: Map<GlowPaneField, GlowFieldState>,
)
