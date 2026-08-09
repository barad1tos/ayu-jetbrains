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

    fun close(): List<SettingsCleanupFailure> {
        if (phase == Phase.CLOSED) return emptyList()

        phase = Phase.CLOSED
        return try {
            dispose(participants)
        } finally {
            participants.clear()
        }
    }

    private fun dispose(entries: List<NamedSettingsParticipant>): List<SettingsCleanupFailure> {
        val failures = mutableListOf<SettingsCleanupFailure>()
        var cancellation: Throwable? = null

        for (entry in entries.asReversed()) {
            try {
                runCatchingPreservingCancellation { entry.participant.dispose() }
                    .exceptionOrNull()
                    ?.let { failures += SettingsCleanupFailure(entry.name, it) }
            } catch (failure: ProcessCanceledException) {
                cancellation = recordCancellation(cancellation, failure)
            } catch (failure: CancellationException) {
                cancellation = recordCancellation(cancellation, failure)
            }
        }

        cancellation?.let { failure ->
            failures.forEach { failure.addSuppressed(it.cause) }
            throw failure
        }
        return failures
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
            failure.addSuppressed(cleanupCancellation)
        } catch (cleanupCancellation: CancellationException) {
            failure.addSuppressed(cleanupCancellation)
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

    private fun recordCancellation(
        recorded: Throwable?,
        next: Throwable,
    ): Throwable = recorded?.also { it.addSuppressed(next) } ?: next

    private fun requireOpen() {
        check(phase == Phase.OPEN) { "Settings session is not open" }
    }

    private fun requireBuilding() {
        check(phase == Phase.BUILDING) { "Settings session closed while building" }
    }
}
