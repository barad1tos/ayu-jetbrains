package dev.ayuislands

import kotlin.test.Test
import kotlin.test.assertTrue

class StartupBenchmarkTest {
    @Test
    fun `measureCpuSpeed returns positive duration`() {
        val elapsed = StartupBenchmark.measureCpuSpeed()
        assertTrue(elapsed >= 0, "Benchmark should return non-negative duration, got $elapsed")
    }

    @Test
    fun `measureCpuSpeed is deterministic within reasonable bounds`() {
        val first = StartupBenchmark.measureCpuSpeed()
        val second = StartupBenchmark.measureCpuSpeed()
        // Both should be in the same order of magnitude (within 10x)
        assertTrue(
            second < first * 10 + 1,
            "Second run ($second ms) should be within 10x of first ($first ms)",
        )
    }
}
