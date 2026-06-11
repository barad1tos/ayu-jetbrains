package dev.ayuislands.commitpanel

import kotlin.test.Test
import kotlin.test.assertEquals

class CommitPathShortenerTest {
    @Test
    fun `root level file path stays unchanged`() {
        assertEquals(
            "build.gradle.kts",
            shorten(
                path = "build.gradle.kts",
                fullRowWidth = 16,
                availableRowWidth = 8,
            ),
        )
    }

    @Test
    fun `full path stays unchanged when it fits and min is zero`() {
        assertEquals(
            "alpha/beta/gamma",
            shorten(
                path = "alpha/beta/gamma",
                fullRowWidth = 30,
                availableRowWidth = 40,
            ),
        )
    }

    @Test
    fun `min hidden levels always removes leading directories`() {
        assertEquals(
            ".../gamma/delta",
            shorten(
                path = "alpha/beta/gamma/delta",
                fullRowWidth = 40,
                availableRowWidth = 80,
                minHiddenLevels = 2,
                maxHiddenLevels = 5,
            ),
        )
    }

    @Test
    fun `dynamic shortening hides the first level that fits`() {
        assertEquals(
            ".../epsilon/zeta",
            shorten(
                path = "alpha/beta/gamma/delta/epsilon/zeta",
                fullRowWidth = 70,
                availableRowWidth = 55,
                minHiddenLevels = 0,
                maxHiddenLevels = 4,
            ),
        )
    }

    @Test
    fun `max hidden levels continues shortening after the first fitting candidate`() {
        assertEquals(
            ".../zeta",
            shorten(
                path = "alpha/beta/gamma/delta/epsilon/zeta",
                fullRowWidth = 70,
                availableRowWidth = 60,
                minHiddenLevels = 0,
                maxHiddenLevels = 5,
            ),
        )
    }

    @Test
    fun `shortening stops at max when row still does not fit`() {
        assertEquals(
            ".../epsilon/zeta",
            shorten(
                path = "alpha/beta/gamma/delta/epsilon/zeta",
                fullRowWidth = 70,
                availableRowWidth = 10,
                minHiddenLevels = 0,
                maxHiddenLevels = 4,
            ),
        )
    }

    @Test
    fun `negative values normalize to zero and max below min normalizes to min`() {
        assertEquals(
            ".../gamma/delta",
            shorten(
                path = "alpha/beta/gamma/delta",
                fullRowWidth = 30,
                availableRowWidth = 80,
                minHiddenLevels = 2,
                maxHiddenLevels = 1,
            ),
        )
        assertEquals(
            "alpha/beta/gamma",
            shorten(
                path = "alpha/beta/gamma",
                fullRowWidth = 20,
                availableRowWidth = 80,
                minHiddenLevels = -3,
                maxHiddenLevels = -1,
            ),
        )
    }

    @Test
    fun `leading and trailing whitespace are preserved`() {
        assertEquals(
            "  .../gamma/delta ",
            shorten(
                path = "  alpha/beta/gamma/delta ",
                fullRowWidth = 30,
                availableRowWidth = 80,
                minHiddenLevels = 2,
                maxHiddenLevels = 2,
            ),
        )
    }

    @Test
    fun `windows separators are shortened by directory level`() {
        assertEquals(
            "...\\gamma\\delta",
            shorten(
                path = "alpha\\beta\\gamma\\delta",
                fullRowWidth = 30,
                availableRowWidth = 80,
                minHiddenLevels = 2,
                maxHiddenLevels = 2,
            ),
        )
    }

    private fun shorten(
        path: String,
        fullRowWidth: Int,
        availableRowWidth: Int,
        minHiddenLevels: Int = 0,
        maxHiddenLevels: Int = 5,
    ): String =
        CommitPathShortener.shorten(
            CommitPathShorteningRequest(
                pathText = path,
                fullRowWidth = fullRowWidth,
                availableRowWidth = availableRowWidth,
                minHiddenLevels = minHiddenLevels,
                maxHiddenLevels = maxHiddenLevels,
                measureTextWidth = String::length,
            ),
        )
}
