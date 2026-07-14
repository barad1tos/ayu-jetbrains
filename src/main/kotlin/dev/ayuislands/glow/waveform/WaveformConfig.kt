package dev.ayuislands.glow.waveform

const val DEFAULT_WAVEFORM_AMPLITUDE = 10
const val MIN_WAVEFORM_AMPLITUDE = 8
const val MAX_WAVEFORM_AMPLITUDE = 24
const val DEFAULT_WAVEFORM_INTENSITY = 70
const val MIN_WAVEFORM_INTENSITY = 0
const val MAX_WAVEFORM_INTENSITY = 100
const val DEFAULT_WAVEFORM_LOOP_SECONDS = 2.8f
const val MIN_WAVEFORM_LOOP_SECONDS = 1.5f
const val MAX_WAVEFORM_LOOP_SECONDS = 6.0f
private const val MONITOR_IDLE_BRIGHTNESS = 0.85f
private const val STATIC_IDLE_BRIGHTNESS = 0.35f

/** Effective waveform settings consumed by the engine and painter. */
data class WaveformConfig(
    val motion: WaveformMotion = WaveformMotion.MONITOR,
    val direction: WaveformDirection = WaveformDirection.CLOCKWISE,
    val amplitude: Int = DEFAULT_WAVEFORM_AMPLITUDE,
    val intensity: Int = DEFAULT_WAVEFORM_INTENSITY,
    val loopSeconds: Float = DEFAULT_WAVEFORM_LOOP_SECONDS,
)

internal fun WaveformConfig.brightnessAt(energy: Float): Float {
    val idleBrightness =
        when (motion) {
            WaveformMotion.MONITOR -> MONITOR_IDLE_BRIGHTNESS
            WaveformMotion.STATIC_PULSE -> STATIC_IDLE_BRIGHTNESS
        }
    return idleBrightness + energy.coerceIn(0f, 1f) * (1f - idleBrightness)
}

internal fun Float.normalizedLoopSeconds(): Float =
    if (isNaN()) {
        DEFAULT_WAVEFORM_LOOP_SECONDS
    } else {
        coerceIn(MIN_WAVEFORM_LOOP_SECONDS, MAX_WAVEFORM_LOOP_SECONDS)
    }

internal fun smoothStep(progress: Float): Float {
    val value = progress.coerceIn(0f, 1f)
    return value * value * (SMOOTH_STEP_HIGH - SMOOTH_STEP_LOW * value)
}

private const val SMOOTH_STEP_HIGH = 3f
private const val SMOOTH_STEP_LOW = 2f

/** Direction along the clockwise-sampled perimeter. Values persist by [Enum.name]. */
enum class WaveformDirection(
    val displayName: String,
    val travelSign: Float,
) {
    CLOCKWISE("Clockwise", 1f),
    COUNTER_CLOCKWISE("Counter-clockwise", -1f),
    ;

    companion object {
        fun fromName(name: String?): WaveformDirection = entries.firstOrNull { it.name == name } ?: CLOCKWISE
    }
}

/** How the ECG waveform moves after editor input. Values persist by [Enum.name]. */
enum class WaveformMotion(
    val displayName: String,
) {
    MONITOR("Perimeter loop"),
    STATIC_PULSE("Static pulse"),
    ;

    companion object {
        fun fromName(name: String?): WaveformMotion = entries.firstOrNull { it.name == name } ?: MONITOR
    }
}
