package dev.ayuislands.glow

import dev.ayuislands.glow.waveform.WaveformConfig

/** Explicit native playback input, retained independently from the original shutdown attempt. */
internal data class GlowPlaybackPlan(
    val overlays: GlowOverlaySnapshot,
    val animation: GlowAnimation,
    val routeConfig: WaveformConfig?,
    val environment: PlaybackEnvironment? = null,
)

/** Expected post-write values and exact surface identities, captured before native application. */
internal data class GlowPlaybackTarget(
    val overlay: RouteOverlay,
    val appearance: GlowPaneAppearance,
    val failure: GlowWaveformFailure?,
)

/** One frozen preparation and the progress of its retained native startup attempt. */
internal data class GlowPlaybackRecord(
    val intent: GlowPlaybackRequest.Activate,
    val phase: GlowCheckpointPhase,
)

internal sealed interface GlowPlaybackRequest {
    data object Restore : GlowPlaybackRequest

    class Activate(
        val animation: GlowAnimation,
        val routeConfig: WaveformConfig?,
        targets: List<GlowPlaybackTarget>,
        val environment: PlaybackEnvironment,
    ) : GlowPlaybackRequest {
        val targets: List<GlowPlaybackTarget> = java.util.List.copyOf(targets)
    }
}
