package dev.ayuislands.toolwindow

import kotlin.math.abs

/** Pure calculation utilities for tool window auto-fit, extracted for testability. */
object AutoFitCalculator {
    const val AUTOFIT_PADDING = 20
    const val JITTER_THRESHOLD = 8

    fun calculateDesiredWidth(
        maxRowWidth: Int,
        maxWidth: Int,
        minWidth: Int,
    ): Int =
        (maxRowWidth + AUTOFIT_PADDING)
            .coerceAtMost(maxWidth)
            .coerceAtLeast(minWidth)

    fun isJitterOnly(
        currentWidth: Int,
        desiredWidth: Int,
    ): Boolean = abs(desiredWidth - currentWidth) <= JITTER_THRESHOLD
}
