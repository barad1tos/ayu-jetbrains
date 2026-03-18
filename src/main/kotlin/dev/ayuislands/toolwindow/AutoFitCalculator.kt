package dev.ayuislands.toolwindow

import java.awt.Component
import java.awt.Container
import kotlin.math.abs

/** Pure calculation utilities for tool window auto-fit, extracted for testability. */
object AutoFitCalculator {
    const val AUTOFIT_PADDING = 20
    const val JITTER_THRESHOLD = 8

    const val MIN_PROJECT_AUTOFIT_WIDTH = 253
    const val MIN_COMMIT_AUTOFIT_WIDTH = 269
    const val MIN_GIT_AUTOFIT_WIDTH = 200
    const val MIN_FIXED_WIDTH = 100

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

    fun findFirstOfType(
        component: Component,
        type: Class<*>,
    ): Component? {
        if (type.isInstance(component)) return component
        if (component is Container) {
            for (child in component.components) {
                val found = findFirstOfType(child, type)
                if (found != null) return found
            }
        }
        return null
    }

    fun findAllOfType(
        component: Component,
        type: Class<*>,
    ): List<Component> {
        val results = mutableListOf<Component>()
        collectOfType(component, type, results)
        return results
    }

    private fun collectOfType(
        component: Component,
        type: Class<*>,
        results: MutableList<Component>,
    ) {
        if (type.isInstance(component)) results.add(component)
        if (component is Container) {
            for (child in component.components) {
                collectOfType(child, type, results)
            }
        }
    }
}
