package dev.ayuislands.glow.waveform

import kotlin.test.Test
import kotlin.test.assertEquals

class TypingCadenceTest {
    @Test
    fun `single keystroke measures no cadence and keeps resting trace rate`() {
        val cadence = TypingCadence().keystroke(nowMs = 1_000L)

        assertEquals(0f, cadence.strokesPerSecond, 0.001f)
        assertEquals(0f, cadence.currentRate(nowMs = 1_000L), 0.001f)
        assertEquals(1f, cadence.targetRate(nowMs = 1_000L), 0.001f)
    }

    @Test
    fun `steady typing converges on the keystroke rate`() {
        var cadence = TypingCadence()
        cadence = cadence.keystroke(nowMs = 0L)
        cadence = cadence.keystroke(nowMs = 100L)
        assertEquals(3f, cadence.strokesPerSecond, 0.001f)

        cadence = cadence.keystroke(nowMs = 200L)
        assertEquals(5.1f, cadence.strokesPerSecond, 0.001f)

        for (stroke in 3..12) {
            cadence = cadence.keystroke(nowMs = stroke * 100L)
        }
        assertEquals(10f, cadence.strokesPerSecond, 0.5f)
    }

    @Test
    fun `rate holds briefly then decays linearly to zero`() {
        var cadence = TypingCadence()
        cadence = cadence.keystroke(nowMs = 0L)
        cadence = cadence.keystroke(nowMs = 100L)
        val lastMs = 100L

        val holdEnd = lastMs + TypingCadence.CADENCE_HOLD_MS
        assertEquals(3f, cadence.currentRate(holdEnd), 0.001f)
        assertEquals(1.5f, cadence.currentRate(holdEnd + TypingCadence.CADENCE_DECAY_MS / 2), 0.001f)
        assertEquals(0f, cadence.currentRate(holdEnd + TypingCadence.CADENCE_DECAY_MS), 0.001f)
        assertEquals(0f, cadence.currentRate(holdEnd + TypingCadence.CADENCE_DECAY_MS * 2), 0.001f)
    }

    @Test
    fun `gap beyond reset threshold restarts measurement`() {
        var cadence = TypingCadence()
        cadence = cadence.keystroke(nowMs = 0L)
        cadence = cadence.keystroke(nowMs = 100L)
        assertEquals(3f, cadence.strokesPerSecond, 0.001f)

        cadence = cadence.keystroke(nowMs = 100L + TypingCadence.CADENCE_RESET_MS + 1L)

        assertEquals(0f, cadence.strokesPerSecond, 0.001f)
        assertEquals(1f, cadence.targetRate(nowMs = 100L + TypingCadence.CADENCE_RESET_MS + 1L), 0.001f)
    }

    @Test
    fun `target trace rate rises smoothly and clamps at the maximum`() {
        var halfRate = TypingCadence()
        halfRate = halfRate.keystroke(nowMs = 0L)
        halfRate = halfRate.keystroke(nowMs = 100L)
        assertEquals(1.75f, halfRate.targetRate(nowMs = 100L), 0.001f)

        var fullRate = TypingCadence()
        for (stroke in 0..20) {
            fullRate = fullRate.keystroke(nowMs = stroke * 50L)
        }
        assertEquals(
            TypingCadence.MAX_TRACE_RATE,
            fullRate.targetRate(nowMs = 20 * 50L),
            0.01f,
        )
    }

    @Test
    fun `burst intervals are clamped to the minimum interval`() {
        var cadence = TypingCadence()
        cadence = cadence.keystroke(nowMs = 0L)
        cadence = cadence.keystroke(nowMs = 1L)

        val clampedRate = TypingCadence.MS_PER_SECOND / TypingCadence.MIN_INTERVAL_MS
        assertEquals(TypingCadence.EMA_GAIN * clampedRate, cadence.strokesPerSecond, 0.001f)
    }
}
