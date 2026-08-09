package dev.ayuislands.settings

import dev.ayuislands.accent.runCatchingPreservingCancellation

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

        val result = runCatchingPreservingCancellation { content() }
        result
            .onSuccess {
                phase = Phase.OPEN
            }.onFailure { failure ->
                close().forEach { failure.addSuppressed(it.cause) }
            }
        result.getOrThrow()
    }

    fun include(
        vararg candidates: NamedSettingsParticipant,
        build: () -> Unit,
    ) {
        check(phase == Phase.BUILDING) { "Settings participants can only be included while building" }

        val result = runCatchingPreservingCancellation { build() }
        result.onFailure { failure ->
            dispose(candidates.asList()).forEach { failure.addSuppressed(it.cause) }
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
        val failures = dispose(participants)
        participants.clear()
        return failures
    }

    private fun dispose(entries: List<NamedSettingsParticipant>): List<SettingsCleanupFailure> =
        entries
            .asReversed()
            .mapNotNull { entry ->
                runCatchingPreservingCancellation { entry.participant.dispose() }
                    .exceptionOrNull()
                    ?.let { SettingsCleanupFailure(entry.name, it) }
            }

    private fun requireOpen() {
        check(phase == Phase.OPEN) { "Settings session is not open" }
    }
}
