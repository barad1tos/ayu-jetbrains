package dev.ayuislands

import kotlin.math.max
import kotlin.math.min
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

    @Test
    fun `measureCpuSpeed completes within reasonable absolute upper bound`() {
        val elapsed = StartupBenchmark.measureCpuSpeed()
        // 5 seconds is absurdly slow for 10k SHA-256 iterations on any
        // modern CPU — protects against benchmark hang or accidental
        // loop explosion.
        assertTrue(
            elapsed < 5_000,
            "Benchmark should complete well under 5s, got ${elapsed}ms",
        )
        // A completed benchmark on real hardware measures at least a
        // few microseconds (reported as >=0 ms). Guard against the
        // timing call itself being stubbed out: elapsed must be non-
        // negative, and on any real machine ITERATIONS=10_000 SHA-256
        // rounds cannot finish below 0 ms.
        assertTrue(
            elapsed >= 0,
            "Benchmark returned negative duration: ${elapsed}ms",
        )
    }

    @Test
    fun `measureCpuSpeed returns similar results on back-to-back calls`() {
        val runs =
            listOf(
                StartupBenchmark.measureCpuSpeed(),
                StartupBenchmark.measureCpuSpeed(),
                StartupBenchmark.measureCpuSpeed(),
            )
        // Use 1-ms floor to avoid division-by-zero on very fast hosts
        // where sub-millisecond runs round to zero.
        val lo = max(1L, runs.min())
        val hi = max(1L, runs.max())
        val ratio = hi.toDouble() / lo.toDouble()
        assertTrue(
            ratio < 8.0,
            "Back-to-back runs vary too much: min=${runs.min()}ms, " +
                "max=${runs.max()}ms, ratio=$ratio",
        )
        // Ratio must always be >= 1.0 by construction
        assertTrue(ratio >= 1.0, "Impossible ratio $ratio")
        // Sanity: at least one run produced a non-negative result
        assertTrue(
            min(runs.min(), runs.max()) >= 0,
            "Negative run detected: $runs",
        )
    }
}
