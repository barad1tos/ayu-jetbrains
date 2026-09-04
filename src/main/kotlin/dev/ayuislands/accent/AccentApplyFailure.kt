package dev.ayuislands.accent

import com.intellij.openapi.progress.ProcessCanceledException
import java.util.concurrent.CancellationException

internal fun captureAccentFailure(
    step: AccentApplyStep,
    operation: String,
    rollback: () -> Unit = {},
    action: () -> Unit,
): AccentApplyStepFailure? =
    try {
        action()
        null
    } catch (failure: RuntimeException) {
        try {
            rollback()
        } catch (recovery: RuntimeException) {
            if (recovery !== failure) {
                if (recovery.isAccentCancellation() && !failure.isAccentCancellation()) {
                    recovery.addSuppressed(failure)
                    throw recovery
                }
                failure.addSuppressed(recovery)
            }
        }
        if (failure.isAccentCancellation()) throw failure
        AccentApplyStepFailure(step, IllegalStateException(operation, failure))
    }

private fun RuntimeException.isAccentCancellation(): Boolean =
    this is ProcessCanceledException || this is CancellationException
