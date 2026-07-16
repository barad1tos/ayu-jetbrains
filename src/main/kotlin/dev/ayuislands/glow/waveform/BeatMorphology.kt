package dev.ayuislands.glow.waveform

import kotlin.random.Random

/** One randomized P-QRS-T complex evaluated over a normalized `0..1` beat window. */
class BeatMorphology private constructor(
    private val pAmplitude: Float,
    private val qAmplitude: Float,
    private val rAmplitude: Float,
    private val sAmplitude: Float,
    private val tAmplitude: Float,
    private val stretch: Float,
    private val jitter: Float,
) {
    private val vertices =
        FEATURE_PHASES
            .map { phase -> (phase - jitter) * stretch }
            .filter { phase -> phase in 0f..1f }

    fun valueAt(time: Float): Float {
        if (time !in 0f..1f) return 0f
        val adjusted = time / stretch + jitter

        return when {
            adjusted < P_START -> 0f
            adjusted < P_APEX -> interpolate(adjusted, P_START, P_APEX, 0f, pAmplitude)
            adjusted < P_END -> interpolate(adjusted, P_APEX, P_END, pAmplitude, 0f)
            adjusted < QRS_START -> 0f
            adjusted < Q_APEX -> interpolate(adjusted, QRS_START, Q_APEX, 0f, -qAmplitude)
            adjusted < R_APEX -> interpolate(adjusted, Q_APEX, R_APEX, -qAmplitude, rAmplitude)
            adjusted < S_APEX -> interpolate(adjusted, R_APEX, S_APEX, rAmplitude, -sAmplitude)
            adjusted < QRS_END -> interpolate(adjusted, S_APEX, QRS_END, -sAmplitude, 0f)
            adjusted < T_START -> 0f
            adjusted < T_APEX -> interpolate(adjusted, T_START, T_APEX, 0f, tAmplitude)
            adjusted < T_END -> interpolate(adjusted, T_APEX, T_END, tAmplitude, 0f)
            else -> 0f
        }
    }

    internal fun vertexPhases(): List<Float> = vertices

    companion object {
        private data class Variation(
            val minimum: Float,
            val maximum: Float,
        )

        private val P_AMPLITUDE = Variation(0.09f, 0.16f)
        private val Q_AMPLITUDE = Variation(0.05f, 0.13f)
        private val R_AMPLITUDE = Variation(0.82f, 1.00f)
        private val S_AMPLITUDE = Variation(0.14f, 0.30f)
        private val T_AMPLITUDE = Variation(0.20f, 0.36f)
        private val STRETCH = Variation(0.90f, 1.10f)
        private val JITTER = Variation(-0.01f, 0.01f)

        private const val P_START = 0.06f
        private const val P_APEX = 0.10f
        private const val P_END = 0.15f
        private const val QRS_START = 0.235f
        private const val Q_APEX = 0.255f
        private const val R_APEX = 0.275f
        private const val S_APEX = 0.300f
        private const val QRS_END = 0.330f
        private const val T_START = 0.40f
        private const val T_APEX = 0.47f
        private const val T_END = 0.56f
        private val FEATURE_PHASES =
            listOf(
                P_START,
                P_APEX,
                P_END,
                QRS_START,
                Q_APEX,
                R_APEX,
                S_APEX,
                QRS_END,
                T_START,
                T_APEX,
                T_END,
            )

        fun random(random: Random = Random.Default): BeatMorphology =
            BeatMorphology(
                pAmplitude = random.sample(P_AMPLITUDE),
                qAmplitude = random.sample(Q_AMPLITUDE),
                rAmplitude = random.sample(R_AMPLITUDE),
                sAmplitude = random.sample(S_AMPLITUDE),
                tAmplitude = random.sample(T_AMPLITUDE),
                stretch = random.sample(STRETCH),
                jitter = random.sample(JITTER),
            )

        fun standard(): BeatMorphology =
            BeatMorphology(
                pAmplitude = P_AMPLITUDE.midpoint,
                qAmplitude = Q_AMPLITUDE.midpoint,
                rAmplitude = R_AMPLITUDE.midpoint,
                sAmplitude = S_AMPLITUDE.midpoint,
                tAmplitude = T_AMPLITUDE.midpoint,
                stretch = STRETCH.midpoint,
                jitter = JITTER.midpoint,
            )

        private val Variation.midpoint: Float
            get() = (minimum + maximum) / 2f

        private fun Random.sample(variation: Variation): Float =
            variation.minimum + nextFloat() * (variation.maximum - variation.minimum)

        private fun interpolate(
            time: Float,
            startTime: Float,
            endTime: Float,
            startValue: Float,
            endValue: Float,
        ): Float {
            val progress = (time - startTime) / (endTime - startTime)
            return startValue + (endValue - startValue) * progress
        }
    }
}
