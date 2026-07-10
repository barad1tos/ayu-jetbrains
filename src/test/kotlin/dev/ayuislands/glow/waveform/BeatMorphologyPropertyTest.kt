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
}
