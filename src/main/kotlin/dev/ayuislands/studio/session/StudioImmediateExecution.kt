package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.AcceptanceBlock
import dev.ayuislands.studio.model.AcceptanceOrigin
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.ApplyFailure
import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.StudioList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The command remains application-owned until both native work and its draft disposition are acknowledged. */
internal sealed interface ImmediateExecutionResult {
    data class Complete(
        val acceptance: AcceptanceResult,
    ) : ImmediateExecutionResult

    data class Pending(
        val operationId: String,
        val acceptance: AcceptanceResult,
        val failure: ApplyFailure? = null,
    ) : ImmediateExecutionResult
}

/** Owns the lifetime of an immediate command; acceptance and journal writes keep their existing owners. */
internal class StudioImmediateExecution(
    private val coordinator: StudioApplyCoordinator,
    private val finishRecord: suspend (String, SessionAvailability) -> DraftWriteResult,
) {
    private val commands = Mutex()
    private var pending: PendingCommand? = null

    suspend fun submit(request: AcceptanceRequest): ImmediateExecutionResult =
        commands.withLock {
            pending?.let { return@withLock it.result }
            if (!supports(request)) return@withLock invalidRequest()
            try {
                settle(request, coordinator.submit(request))
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    coordinator.outcome(request.transactionId)?.let { settle(request, it) }
                }
                throw cancelled
            }
        }

    /** Retries the retained operation or its status write, never the original appearance gesture. */
    suspend fun retry(operationId: String): ImmediateExecutionResult =
        commands.withLock {
            val retained =
                pending?.takeIf { it.request.transactionId == operationId }
                    ?: return@withLock invalidRequest()
            retryRetained(retained)
        }

    /** Returns null when there is no retained owner; otherwise recovers that exact operation. */
    suspend fun retryPending(): ImmediateExecutionResult? = commands.withLock { pending?.let { retryRetained(it) } }

    private suspend fun retryRetained(retained: PendingCommand): ImmediateExecutionResult =
        try {
            val previous = retained.result.acceptance
            val result =
                if (needsRecovery(
                        previous,
                    )
                ) {
                    coordinator.retryRecovery(retained.request.transactionId)
                } else {
                    previous
                }
            settle(retained.request, result)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                coordinator.outcome(retained.request.transactionId)?.let { settle(retained.request, it) }
            }
            throw cancelled
        }

    private suspend fun settle(
        request: AcceptanceRequest,
        result: AcceptanceResult,
    ): ImmediateExecutionResult =
        withContext(NonCancellable) {
            val retained = PendingCommand(request, ImmediateExecutionResult.Pending(request.transactionId, result))
            pending = retained
            if (needsRecovery(result)) return@withContext retained.result
            val disposition =
                when (result) {
                    is AcceptanceResult.Accepted, AcceptanceResult.NoChanges -> SessionAvailability.RETIRED
                    else -> SessionAvailability.RECOVERED
                }
            when (val written = finishRecord(request.session.id, disposition)) {
                is DraftWriteResult.Failed -> {
                    val failed = retained.copy(result = retained.result.copy(failure = ApplyFailure(written.code)))
                    pending = failed
                    failed.result
                }

                is DraftWriteResult.Saved -> {
                    coordinator.acknowledge(request.transactionId)
                    pending = null
                    ImmediateExecutionResult.Complete(result)
                }
            }
        }

    private fun needsRecovery(result: AcceptanceResult): Boolean =
        result is AcceptanceResult.AcceptedCleanupPending || result is AcceptanceResult.RecoveryRequired

    private fun supports(request: AcceptanceRequest): Boolean =
        when (request.origin) {
            AcceptanceOrigin.QUICK_SWITCHER -> request.initialization == null
            AcceptanceOrigin.INITIALIZATION -> {
                request.initialization != null && !StudioAcceptancePlanner.hasPending(request.session.work)
            }
            AcceptanceOrigin.SETTINGS -> false
        }

    private fun invalidRequest(): ImmediateExecutionResult =
        ImmediateExecutionResult.Complete(
            AcceptanceResult.Blocked(StudioList(listOf(AcceptanceBlock.INVALID_REQUEST))),
        )

    private data class PendingCommand(
        val request: AcceptanceRequest,
        val result: ImmediateExecutionResult.Pending,
    )
}
