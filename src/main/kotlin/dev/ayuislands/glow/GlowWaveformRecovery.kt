package dev.ayuislands.glow

import dev.ayuislands.glow.waveform.WaveformConfig

/** Uses native config equality; a raw NaN payload change alone is not a renderer retry. */
internal fun shouldRetryWaveform(
    previousShape: GlowShape,
    previousConfig: WaveformConfig,
    shape: GlowShape,
    config: WaveformConfig,
): Boolean = shape == GlowShape.WAVEFORM && (previousShape != GlowShape.WAVEFORM || previousConfig != config)
