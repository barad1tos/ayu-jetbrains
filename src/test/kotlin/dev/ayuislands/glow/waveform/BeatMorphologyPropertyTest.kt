package dev.ayuislands.glow.waveform

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BeatMorphologyPropertyTest {
    @Test
    fun `same seed produces the same morphology across the beat window`() {
        val first = BeatMorphology.random(Random(42))
        val second = BeatMorphology.random(Random(42))

        for (sample in 0..1_000) {
            val time = sample / 1_000f
            assertEquals(first.valueAt(time), second.valueAt(time), 0.000_001f)
        }
        assertEquals(0f, first.valueAt(-0.01f))
        assertEquals(0f, first.valueAt(1.01f))
    }

    @Test
    fun `randomized morphology stays bounded and differs across seeds`() {
        val first = BeatMorphology.random(Random(7))
        val second = BeatMorphology.random(Random(8))
        val firstValues = (0..2_000).map { first.valueAt(it / 2_000f) }
        val secondValues = (0..2_000).map { second.valueAt(it / 2_000f) }

        assertTrue(firstValues.all { it in -0.30f..1.10f })
        assertTrue(firstValues.any { it < 0f }, "Q/S deflections must dip below the baseline")
        assertTrue(firstValues.any { it > 0.8f }, "R peak must dominate the complex")
        assertNotEquals(firstValues, secondValues)
    }

    @Test
    fun `standard R complex has one narrow dominant apex`() {
        val morphology = BeatMorphology.standard()

        assertTrue(morphology.valueAt(0.275f) > 0.9f, "R apex must dominate")
        assertTrue(morphology.valueAt(0.265f) < 0.5f, "Q-to-R rise must stay narrow")
        assertTrue(morphology.valueAt(0.2875f) < 0.5f, "R-to-S fall must stay narrow")
    }

    @Test
    fun `standard ECG connects landmarks with straight angular segments`() {
        val morphology = BeatMorphology.standard()

        assertLinearKnot(morphology, before = 0.06f, knot = 0.10f, after = 0.15f)
        assertLinearKnot(morphology, before = 0.235f, knot = 0.255f, after = 0.275f)
        assertLinearKnot(morphology, before = 0.255f, knot = 0.275f, after = 0.300f)
        assertLinearKnot(morphology, before = 0.275f, knot = 0.300f, after = 0.330f)
        assertLinearKnot(morphology, before = 0.40f, knot = 0.47f, after = 0.56f)
    }

    @Test
    fun `randomized morphology uses broad independent feature ranges`() {
        val observed = mutableListOf<List<Float>>()

        repeat(50) { seed ->
            val features = featureMagnitudes(BeatMorphology.random(Random(seed)))
            for ((index, range) in FEATURE_RANGES.withIndex()) {
                assertTrue(features[index] in range, "Morphology feature $index for seed $seed was ${features[index]}")
            }
            observed += features
        }

        for ((index, minimumSpread) in MINIMUM_FEATURE_SPREADS.withIndex()) {
            val values = observed.map { features -> features[index] }
            assertTrue(
                values.max() - values.min() >= minimumSpread,
                "Morphology feature $index varied by only ${values.max() - values.min()}",
            )
        }
    }

    private fun featureMagnitudes(morphology: BeatMorphology): List<Float> {
        val samples = (0..20_000).map { sample -> sample / 20_000f to morphology.valueAt(sample / 20_000f) }
        return listOf(
            samples.filter { (time) -> time <= 0.22f }.maxOf { (_, value) -> value },
            -samples.filter { (time) -> time in 0.20f..0.40f }.minOf { (_, value) -> value },
            samples.filter { (time) -> time in 0.22f..0.40f }.maxOf { (_, value) -> value },
            samples.filter { (time) -> time in 0.40f..0.75f }.maxOf { (_, value) -> value },
        )
    }

    private fun assertLinearKnot(
        morphology: BeatMorphology,
        before: Float,
        knot: Float,
        after: Float,
    ) {
        val beforeValue = morphology.valueAt(before)
        val knotValue = morphology.valueAt(knot)
        val afterValue = morphology.valueAt(after)
        val firstMidpoint = morphology.valueAt((before + knot) / 2f)
        val secondMidpoint = morphology.valueAt((knot + after) / 2f)

        assertEquals((beforeValue + knotValue) / 2f, firstMidpoint, LINEAR_TOLERANCE)
        assertEquals((knotValue + afterValue) / 2f, secondMidpoint, LINEAR_TOLERANCE)
    }

    private companion object {
        const val LINEAR_TOLERANCE = 0.0001f
        val FEATURE_RANGES =
            listOf(
                0.089f..0.161f,
                0.139f..0.301f,
                0.819f..1.001f,
                0.199f..0.361f,
            )
        val MINIMUM_FEATURE_SPREADS = listOf(0.04f, 0.10f, 0.11f, 0.10f)
    }
}
