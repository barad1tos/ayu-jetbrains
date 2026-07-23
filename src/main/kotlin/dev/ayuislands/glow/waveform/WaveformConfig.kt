package dev.ayuislands.glow.waveform

const val DEFAULT_WAVEFORM_AMPLITUDE = 24
const val MIN_WAVEFORM_AMPLITUDE = 1
const val MAX_WAVEFORM_AMPLITUDE = 40
const val DEFAULT_WAVEFORM_INTENSITY = 100
const val MIN_WAVEFORM_INTENSITY = 0
const val MAX_WAVEFORM_INTENSITY = 200
const val DEFAULT_LOOP_SECONDS = 20f
const val MIN_WAVEFORM_LOOP_SECONDS = 1.5f
const val MAX_WAVEFORM_LOOP_SECONDS = 40f
const val DEFAULT_TRACE_DENSITY = 1
const val MIN_TRACE_DENSITY = 1
const val MAX_TRACE_DENSITY = 4
const val DEFAULT_TRACE_LENGTH = 199
const val MIN_TRACE_LENGTH = 120
const val MAX_TRACE_LENGTH = 800
internal const val BASE_COMPLEX_COUNT = 4
internal const val TRACE_ANCHOR_PHASE = 0.275f
internal const val TRACE_PHASE_SPAN = 0.76f
private const val IDLE_BRIGHTNESS = 0.85f

data class WaveformConfig(
    val movement: WaveformMovement = WaveformMovement.CLOCKWISE,
    val baseline: WaveformBaseline = WaveformBaseline.CENTERED,
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
        fun fromName(name: String?): WaveformBaseline = entries.firstOrNull { it.name == name } ?: CENTERED
    }
}

internal fun smoothStep(progress: Float): Float {
    val value = progress.coerceIn(0f, 1f)
    return value * value * (SMOOTH_STEP_HIGH - SMOOTH_STEP_LOW * value)
}

private const val SMOOTH_STEP_HIGH = 3f
private const val SMOOTH_STEP_LOW = 2f

/** User-selected perimeter movement. Values persist by [Enum.name]. */
enum class WaveformMovement(
    val displayName: String,
) {
    CLOCKWISE("Clockwise"),
    COUNTER_CLOCKWISE("Counter-clockwise"),
    CHAOTIC("Chaotic"),
    ;

    companion object {
        fun fromName(name: String?): WaveformMovement = entries.firstOrNull { it.name == name } ?: CLOCKWISE
    }
}

enum class TravelDirection(
    val travelSign: Float,
) {
    CLOCKWISE(1f),
    COUNTER_CLOCKWISE(-1f),
}

internal val WaveformMovement.fixedDirection: TravelDirection?
    get() =
        when (this) {
            WaveformMovement.CLOCKWISE -> TravelDirection.CLOCKWISE
            WaveformMovement.COUNTER_CLOCKWISE -> TravelDirection.COUNTER_CLOCKWISE
            WaveformMovement.CHAOTIC -> null
        }
