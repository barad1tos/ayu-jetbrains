package dev.ayuislands.glow

import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.glow.waveform.WaveformMovement

/** Explicit native targets remain fixed through scheduled route refreshes. */
internal class RoutePlayback(
    val config: WaveformConfig,
    targets: List<RouteOverlay>,
) {
    val targets: List<RouteOverlay> = java.util.List.copyOf(targets)
}

/** Native observation validates retained identities before the pure presentation qualification. */
internal fun captureRouteAppearance(
    playback: RoutePlayback,
    available: List<RouteOverlay>,
): GlowPaneAppearance? {
    val ids = playback.targets.map { it.id }
    check(ids.distinct().size == ids.size) { "Duplicate Glow route targets" }
    for (target in playback.targets) {
        check(available.any { sameRouteTarget(it, target) }) { "Glow route target was replaced: ${target.id}" }
        check(target.pane.waveformFailure == null) { "Glow route target has an unresolved failure: ${target.id}" }
    }
    return qualifyRouteAppearance(playback.config, playback.targets.map { it.pane.captureAppearance() })
}

internal fun sameRouteTarget(
    actual: RouteOverlay,
    expected: RouteOverlay,
): Boolean =
    actual.id == expected.id &&
        actual.pane === expected.pane &&
        actual.host === expected.host &&
        actual.layeredPane === expected.layeredPane

/** The shared renderer may represent only peers with the same actual style and waveform configuration. */
internal fun qualifyRouteAppearance(
    config: WaveformConfig,
    appearances: List<GlowPaneAppearance>,
): GlowPaneAppearance? {
    check(config.movement == WaveformMovement.CHAOTIC) { "Shared Glow playback requires chaotic movement" }
    val first = appearances.firstOrNull() ?: return null
    val expected = first.copy(shape = GlowShape.WAVEFORM, waveform = config, isVisible = true)
    val renderedFields = GlowPaneField.entries.filterNot { it == GlowPaneField.PLACEMENT || it == GlowPaneField.COLOR }
    check(
        appearances.all { actual ->
            sameColor(actual.color, expected.color) &&
                renderedFields.all { GlowPaneFields.matches(it, actual, expected) }
        },
    ) {
        "Glow route targets have incompatible native appearances"
    }
    return first
}
