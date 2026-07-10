package dev.ayuislands.glow.waveform

const val DEFAULT_WAVEFORM_AMPLITUDE = 10
const val MIN_WAVEFORM_AMPLITUDE = 6
const val MAX_WAVEFORM_AMPLITUDE = 16
const val DEFAULT_WAVEFORM_INTENSITY = 70
const val MIN_WAVEFORM_INTENSITY = 0
const val MAX_WAVEFORM_INTENSITY = 100

/** Effective waveform settings consumed by the engine and painter. */
data class WaveformConfig(
    val motion: WaveformMotion = WaveformMotion.MONITOR,
    val direction: WaveformDirection = WaveformDirection.CLOCKWISE,
    val amplitude: Int = DEFAULT_WAVEFORM_AMPLITUDE,
    val intensity: Int = DEFAULT_WAVEFORM_INTENSITY,
)

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
    MONITOR("Live monitor"),
    STATIC_PULSE("Static pulse"),
    ;

    companion object {
        fun fromName(name: String?): WaveformMotion = entries.firstOrNull { it.name == name } ?: MONITOR
    }
}
