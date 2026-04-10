package dev.ayuislands.toolwindow

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoFitCalculatorPropertyTest {
    @Test
    fun `calculateDesiredWidth result is between minWidth and max of minWidth and maxWidth`() =
        runBlocking {
            checkAll(
                Arb.int(0..2000),
                Arb.int(50..1000),
                Arb.int(50..1000),
            ) { maxRowWidth, maxWidth, minWidth ->
                val result = AutoFitCalculator.calculateDesiredWidth(maxRowWidth, maxWidth, minWidth)
                val effectiveMax = max(minWidth, maxWidth)
                assertTrue(
                    result >= minWidth,
                    "Result $result must be >= minWidth $minWidth " +
                        "(maxRowWidth=$maxRowWidth, maxWidth=$maxWidth)",
                )
                assertTrue(
                    result <= effectiveMax,
                    "Result $result must be <= max(minWidth, maxWidth)=$effectiveMax " +
                        "(maxRowWidth=$maxRowWidth, maxWidth=$maxWidth)",
                )
            }
        }

    @Test
    fun `calculateDesiredWidth adds padding to maxRowWidth`() =
        runBlocking {
            checkAll(
                Arb.int(0..500),
                Arb.int(1000..2000),
                Arb.int(0..100),
            ) { maxRowWidth, maxWidth, minWidth ->
                val result = AutoFitCalculator.calculateDesiredWidth(maxRowWidth, maxWidth, minWidth)
                val expected = maxRowWidth + AutoFitCalculator.AUTOFIT_PADDING
                assertTrue(
                    result >= expected || result == minWidth,
                    "Result $result should be at least maxRowWidth+padding=$expected " +
                        "or clamped to minWidth=$minWidth",
                )
            }
        }

    @Test
    fun `isJitterOnly is symmetric`() =
        runBlocking {
            checkAll(
                Arb.int(0..2000),
                Arb.int(0..2000),
            ) { current, desired ->
                val forward = AutoFitCalculator.isJitterOnly(current, desired)
                val backward = AutoFitCalculator.isJitterOnly(desired, current)
                assertEquals(
                    forward,
                    backward,
                    "isJitterOnly must be symmetric: ($current,$desired)=$forward vs ($desired,$current)=$backward",
                )
            }
        }

    @Test
    fun `isJitterOnly returns true when diff is within threshold`() =
        runBlocking {
            checkAll(Arb.int(0..2000)) { baseWidth ->
                for (delta in 0..AutoFitCalculator.JITTER_THRESHOLD) {
                    assertTrue(
                        AutoFitCalculator.isJitterOnly(baseWidth, baseWidth + delta),
                        "Diff of $delta should be jitter (base=$baseWidth)",
                    )
                    assertTrue(
                        AutoFitCalculator.isJitterOnly(baseWidth + delta, baseWidth),
                        "Reverse diff of $delta should also be jitter (base=$baseWidth)",
                    )
                }
            }
        }

    @Test
    fun `isJitterOnly returns false when diff exceeds threshold`() =
        runBlocking {
            checkAll(
                Arb.int(0..2000),
                Arb.int(AutoFitCalculator.JITTER_THRESHOLD + 1..500),
            ) { baseWidth, delta ->
                assertFalse(
                    AutoFitCalculator.isJitterOnly(baseWidth, baseWidth + delta),
                    "Diff of $delta exceeds threshold, should not be jitter (base=$baseWidth)",
                )
            }
        }

    @Test
    fun `isJitterOnly matches abs diff against JITTER_THRESHOLD`() =
        runBlocking {
            checkAll(
                Arb.int(0..2000),
                Arb.int(0..2000),
            ) { current, desired ->
                val result = AutoFitCalculator.isJitterOnly(current, desired)
                val expectedResult = abs(desired - current) <= AutoFitCalculator.JITTER_THRESHOLD
                assertEquals(
                    expectedResult,
                    result,
                    "isJitterOnly($current, $desired) should equal " +
                        "(abs(${desired - current}) <= ${AutoFitCalculator.JITTER_THRESHOLD})",
                )
            }
        }
}
