package dev.ayuislands.settings

import com.intellij.openapi.progress.ProcessCanceledException
import kotlin.coroutines.cancellation.CancellationException

internal fun runCleanupSteps(vararg steps: () -> Unit) {
    runCleanupSteps(steps.asIterable())
}

internal fun runCleanupSteps(steps: Iterable<() -> Unit>) {
    var primaryFailure: RuntimeException? = null
    steps.forEach { step ->
        try {
            step()
        } catch (failure: RuntimeException) {
            primaryFailure = primaryFailure.combine(failure)
        }
    }
    primaryFailure?.let { throw it }
}

private fun RuntimeException?.combine(next: RuntimeException): RuntimeException {
    val current = this ?: return next
    val primary = if (!current.isCancellation() && next.isCancellation()) next else current
    val secondary = if (primary === current) next else current
    if (primary !== secondary) primary.addSuppressed(secondary)
    return primary
}

private fun RuntimeException.isCancellation(): Boolean =
    this is ProcessCanceledException || this is CancellationException
