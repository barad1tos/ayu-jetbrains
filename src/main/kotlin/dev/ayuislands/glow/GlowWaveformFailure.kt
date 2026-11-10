package dev.ayuislands.glow

import dev.ayuislands.glow.waveform.WaveformEngine

/** One native failure occurrence; equal-looking failures never imply the same recovery ownership. */
internal class GlowWaveformFailure(
    val origin: GlowFailureOrigin,
    val cause: RuntimeException?,
)

internal sealed interface GlowFailureOrigin {
    data class Local(
        val engine: WaveformEngine?,
    ) : GlowFailureOrigin

    data class Route(
        val playback: RoutePlayback?,
    ) : GlowFailureOrigin
}

/** Capture-only restoration evidence must be acknowledged by the outer owner before native writes. */
internal data class GlowFailureRestore(
    val observed: GlowWaveformFailure?,
    val outcome: GlowFieldState,
)

internal data class GlowFailureRecord(
    val before: GlowWaveformFailure?,
    val phase: GlowCheckpointPhase,
    val state: GlowFieldState,
    val cleared: GlowWaveformFailure?,
    val restoration: GlowFailureRestore?,
)
