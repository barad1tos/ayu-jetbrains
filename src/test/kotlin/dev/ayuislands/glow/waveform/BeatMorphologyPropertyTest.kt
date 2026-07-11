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

        assertTrue(morphology.valueAt(0.287f) > 0.9f, "R apex must dominate")
        assertTrue(morphology.valueAt(0.277f) < 0.2f, "Q-to-R rise must stay narrow")
        assertTrue(morphology.valueAt(0.299f) < 0.2f, "R-to-S fall must stay narrow")
    }

    @Test
    fun `randomized morphology features stay within eight percent of standard`() {
        val standard = featureMagnitudes(BeatMorphology.standard())

        repeat(50) { seed ->
            val varied = featureMagnitudes(BeatMorphology.random(Random(seed)))
            for (index in standard.indices) {
                val ratio = varied[index] / standard[index]
                assertTrue(
                    ratio in 0.92f..1.08f,
                    "Morphology feature $index for seed $seed varied by ${(ratio - 1f) * 100f}%",
                )
            }
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
}
