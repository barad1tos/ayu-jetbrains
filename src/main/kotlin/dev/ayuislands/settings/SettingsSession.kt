package dev.ayuislands.settings

import com.intellij.openapi.progress.ProcessCanceledException
import dev.ayuislands.accent.runCatchingPreservingCancellation
import kotlin.coroutines.cancellation.CancellationException

internal data class NamedSettingsParticipant(
    val name: String,
    val participant: SettingsParticipant,
)

internal fun namedParticipant(
    name: String,
    participant: SettingsParticipant,
): NamedSettingsParticipant = NamedSettingsParticipant(name, participant)

internal sealed interface SettingsApplyResult {
    data object Applied : SettingsApplyResult

    data class Failed(
        val failed: String,
        /** Includes the failing section; an attempt does not prove its changes were saved. */
        val attempted: List<String>,
        val skipped: List<String>,
        val cause: Throwable,
    ) : SettingsApplyResult
}

internal data class SettingsCleanupFailure(
    val participant: String,
    val cause: Throwable,
)

internal class SettingsSession(
    private val afterSuccessfulApply: () -> Unit = {},
) {
    private enum class Phase {
        NEW,
        BUILDING,
        OPEN,
        CLOSED,
    }

    private var phase = Phase.NEW
    private val participants = mutableListOf<NamedSettingsParticipant>()

    val participantNames: List<String>
        get() = participants.map { it.name }

    val isClosed: Boolean
        get() = phase == Phase.CLOSED

    fun build(content: SettingsSession.() -> Unit) {
        check(phase == Phase.NEW) { "Settings session can only be built once" }
        phase = Phase.BUILDING

        val result = runBuildStep(cleanup = ::close) { content() }
        result
            .onSuccess {
                requireBuilding()
                phase = Phase.OPEN
            }.onFailure { failure -> cleanupAfterFailure(failure, ::close) }
        result.getOrThrow()
    }

    fun include(
        vararg candidates: NamedSettingsParticipant,
        build: () -> Unit,
    ) {
        check(phase == Phase.BUILDING) { "Settings participants can only be included while building" }

        val cleanup = { dispose(candidates.asList()) }
        val result =
            runBuildStep(cleanup) {
                build()
                requireBuilding()
            }
        result.onFailure { failure ->
            cleanupAfterFailure(failure, cleanup)
        }
        result.getOrThrow()

        participants += candidates
    }

    fun isModified(): Boolean {
        requireOpen()
        return participants.any { it.participant.isModified() }
    }

    fun apply(): SettingsApplyResult {
        requireOpen()

        for ((index, entry) in participants.withIndex()) {
            val failure =
                runCatchingPreservingCancellation { entry.participant.apply() }
                    .exceptionOrNull()
            if (failure != null) {
                return SettingsApplyResult.Failed(
                    failed = entry.name,
                    attempted = participants.take(index + 1).map { it.name },
                    skipped = participants.drop(index + 1).map { it.name },
                    cause = failure,
                )
            }
        }

        afterSuccessfulApply()
        return SettingsApplyResult.Applied
    }

    fun reset() {
        requireOpen()
        participants.forEach { it.participant.reset() }
    }

    fun cancel(): List<SettingsCleanupFailure> {
        requireOpen()
        return cleanUp(participants, SettingsParticipant::cancel)
    }

    fun close(): List<SettingsCleanupFailure> {
        if (phase == Phase.CLOSED) return emptyList()

        phase = Phase.CLOSED
        return try {
            dispose(participants)
        } finally {
            participants.clear()
        }
    }

    private fun dispose(entries: List<NamedSettingsParticipant>): List<SettingsCleanupFailure> =
        cleanUp(entries, SettingsParticipant::dispose)

    private fun cleanUp(
        entries: List<NamedSettingsParticipant>,
        operation: (SettingsParticipant) -> Unit,
    ): List<SettingsCleanupFailure> {
        val failures = mutableListOf<SettingsCleanupFailure>()
        cleanUpFrom(entries.lastIndex, entries, operation, failures)
        return failures
    }

    private fun cleanUpFrom(
        index: Int,
        entries: List<NamedSettingsParticipant>,
        operation: (SettingsParticipant) -> Unit,
        failures: MutableList<SettingsCleanupFailure>,
    ) {
        if (index < 0) return
        val (name, participant) = entries[index]
        val operationFailure =
            try {
                runCatchingPreservingCancellation { operation(participant) }
                    .exceptionOrNull()
            } finally {
                cleanUpFrom(index - 1, entries, operation, failures)
            }
        operationFailure?.let { failures += SettingsCleanupFailure(name, it) }
    }

    private fun runBuildStep(
        cleanup: () -> List<SettingsCleanupFailure>,
        block: () -> Unit,
    ): Result<Unit> =
        try {
            runCatchingPreservingCancellation(block)
        } catch (failure: ProcessCanceledException) {
            cleanupAfterCancellation(failure, cleanup)
            throw failure
        } catch (failure: CancellationException) {
            cleanupAfterCancellation(failure, cleanup)
            throw failure
        }

    private fun cleanupAfterCancellation(
        failure: Throwable,
        cleanup: () -> List<SettingsCleanupFailure>,
    ) {
        try {
            cleanup().forEach { failure.addSuppressed(it.cause) }
        } catch (cleanupCancellation: ProcessCanceledException) {
            if (cleanupCancellation !== failure) cleanupCancellation.addSuppressed(failure)
            throw cleanupCancellation
        } catch (cleanupCancellation: CancellationException) {
            if (cleanupCancellation !== failure) cleanupCancellation.addSuppressed(failure)
            throw cleanupCancellation
        }
    }

    private fun cleanupAfterFailure(
        failure: Throwable,
        cleanup: () -> List<SettingsCleanupFailure>,
    ) {
        try {
            cleanup().forEach { failure.addSuppressed(it.cause) }
        } catch (cleanupCancellation: ProcessCanceledException) {
            cleanupCancellation.addSuppressed(failure)
            throw cleanupCancellation
        } catch (cleanupCancellation: CancellationException) {
            cleanupCancellation.addSuppressed(failure)
            throw cleanupCancellation
        }
    }

    private fun requireOpen() {
        check(phase == Phase.OPEN) { "Settings session is not open" }
    }

    private fun requireBuilding() {
        check(phase == Phase.BUILDING) { "Settings session closed while building" }
    }
}
