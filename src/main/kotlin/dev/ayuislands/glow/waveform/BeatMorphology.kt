package dev.ayuislands.glow.waveform

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
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
    fun valueAt(time: Float): Float {
        if (time !in 0f..1f) return 0f
        val adjusted = time / stretch + jitter

        segment(adjusted, P_PHASE)?.let { return pAmplitude * sin(PI * it).toFloat() }
        segment(adjusted, Q_PHASE)?.let { return -qAmplitude * sin(PI * it).toFloat() }
        segment(adjusted, R_PHASE)?.let { progress ->
            val triangle = if (progress < RISE_FRACTION) progress / RISE_FRACTION else (1f - progress) / FALL_FRACTION
            return rAmplitude * triangle
        }
        segment(adjusted, S_PHASE)?.let { return -sAmplitude * sin(PI * it).toFloat() }
        segment(adjusted, T_PHASE)?.let { progress ->
            return tAmplitude * sin(PI * progress.toDouble().pow(T_WAVE_POWER)).toFloat()
        }
        return 0f
    }

    companion object {
        private data class Phase(
            val start: Float,
            val end: Float,
        )

        private data class Variation(
            val minimum: Float,
            val maximum: Float,
        )

        private val P_PHASE = Phase(0.06f, 0.16f)
        private val Q_PHASE = Phase(0.245f, 0.279f)
        private val R_PHASE = Phase(0.279f, 0.299f)
        private val S_PHASE = Phase(0.299f, 0.335f)
        private val T_PHASE = Phase(0.46f, 0.66f)

        private val P_AMPLITUDE = around(0.125f)
        private val Q_AMPLITUDE = around(0.09f)
        private val R_AMPLITUDE = around(0.96f)
        private val S_AMPLITUDE = around(0.22f)
        private val T_AMPLITUDE = around(0.28f)
        private val STRETCH = Variation(0.96f, 1.04f)
        private val JITTER = Variation(-0.004f, 0.004f)

        private const val RISE_FRACTION = 0.42f
        private const val FALL_FRACTION = 0.58f
        private const val T_WAVE_POWER = 0.75
        private const val VARIATION_FRACTION = 0.08f

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

        private fun around(value: Float): Variation =
            Variation(
                minimum = value * (1f - VARIATION_FRACTION),
                maximum = value * (1f + VARIATION_FRACTION),
            )

        private fun segment(
            time: Float,
            phase: Phase,
        ): Float? =
            if (time >= phase.start && time < phase.end) {
                (time - phase.start) / (phase.end - phase.start)
            } else {
                null
            }
    }
}
