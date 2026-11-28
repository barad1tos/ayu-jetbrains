package dev.ayuislands.rotation

import java.util.UUID

/** Counts verified restored attempts within one running service. */
internal class RotationFailureBudget private constructor(
    private val serviceGeneration: Long,
    private val restoredAttempt: UUID?,
    private val restoredFailures: Int,
) {
    val fault: RuntimeFault.SessionCircuitBreaker?
        get() = RuntimeFault.SessionCircuitBreaker.takeIf { restoredFailures == ROTATION_FAILURE_LIMIT }

    companion object {
        fun afterReadback(
            previous: RotationFailureBudget?,
            current: RotationRuntimeSnapshot,
        ): RotationFailureBudget {
            val service = current.generation.service
            val attempt = current.checkpoint.retry?.restoredAttempt
            val failures =
                when {
                    previous?.serviceGeneration != service -> 0
                    !current.intent.enabled || current.availability.pauseReasons.isNotEmpty() -> 0
                    attempt == null -> 0
                    attempt == previous.restoredAttempt -> previous.restoredFailures
                    else -> (previous.restoredFailures + 1).coerceAtMost(ROTATION_FAILURE_LIMIT)
                }
            return RotationFailureBudget(service, attempt, failures)
        }
    }
}
