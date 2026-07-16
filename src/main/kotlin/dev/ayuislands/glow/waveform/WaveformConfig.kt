package dev.ayuislands.glow.waveform

const val DEFAULT_WAVEFORM_AMPLITUDE = 10
const val MIN_WAVEFORM_AMPLITUDE = 8
const val MAX_WAVEFORM_AMPLITUDE = 24
const val DEFAULT_WAVEFORM_INTENSITY = 70
const val MIN_WAVEFORM_INTENSITY = 0
const val MAX_WAVEFORM_INTENSITY = 100
const val DEFAULT_LOOP_SECONDS = 30f
const val MIN_WAVEFORM_LOOP_SECONDS = 1.5f
const val MAX_WAVEFORM_LOOP_SECONDS = 40f
const val DEFAULT_TRACE_DENSITY = 1
const val MIN_TRACE_DENSITY = 1
const val MAX_TRACE_DENSITY = 4
const val DEFAULT_TRACE_LENGTH = 167
const val MIN_TRACE_LENGTH = 120
const val MAX_TRACE_LENGTH = 800
internal const val BASE_COMPLEX_COUNT = 4
internal const val TRACE_ANCHOR_PHASE = 0.275f
internal const val TRACE_PHASE_SPAN = 0.76f
private const val IDLE_BRIGHTNESS = 0.85f

/** Effective waveform settings consumed by the engine and painter. */
data class WaveformConfig(
    val direction: WaveformDirection = WaveformDirection.CLOCKWISE,
    val baseline: WaveformBaseline = WaveformBaseline.OUTSIDE,
    val amplitude: Int = DEFAULT_WAVEFORM_AMPLITUDE,
    val intensity: Int = DEFAULT_WAVEFORM_INTENSITY,
    val loopSeconds: Float = DEFAULT_LOOP_SECONDS,
    val traceDensity: Int = DEFAULT_TRACE_DENSITY,
    val traceLength: Int = DEFAULT_TRACE_LENGTH,
)

internal val WaveformConfig.traceComplexCount: Int
    get() = BASE_COMPLEX_COUNT * traceDensity.coerceIn(MIN_TRACE_DENSITY, MAX_TRACE_DENSITY)

internal val WaveformConfig.effectiveTraceLength: Int
    get() = traceLength.coerceIn(MIN_TRACE_LENGTH, MAX_TRACE_LENGTH)

internal fun WaveformConfig.brightnessAt(energy: Float): Float =
    IDLE_BRIGHTNESS + energy.coerceIn(0f, 1f) * (1f - IDLE_BRIGHTNESS)

internal fun Float.normalizedLoopSeconds(): Float =
    if (isNaN()) {
        DEFAULT_LOOP_SECONDS
    } else {
        coerceIn(MIN_WAVEFORM_LOOP_SECONDS, MAX_WAVEFORM_LOOP_SECONDS)
    }

/** Where the ECG baseline sits relative to the solid glow band. Values persist by [Enum.name]. */
enum class WaveformBaseline(
    val displayName: String,
) {
    OUTSIDE("Outside edge"),
    CENTERED("Centered on border"),
    ;

    companion object {
        fun fromName(name: String?): WaveformBaseline = entries.firstOrNull { it.name == name } ?: OUTSIDE
    }
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
