package dev.ayuislands.studio.runtime

import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.rotation.ContrastAwareColorGenerator
import dev.ayuislands.rotation.RotationRuntimeCommand
import dev.ayuislands.rotation.RotationRuntimeSnapshot
import dev.ayuislands.studio.model.AcceptanceLease
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.session.ApplyExecution
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.UUID

internal data class RotationObservation(
    val snapshot: RotationRuntimeSnapshot,
    val lease: AcceptanceLease,
)

internal sealed interface RotationCommandOutcome {
    data class Executed(
        val execution: ApplyExecution,
        val step: RotationStepWork? = null,
    ) : RotationCommandOutcome

    data class Rejected(
        val admission: RotationStepAdmission,
    ) : RotationCommandOutcome
}

/** Runs emitted commands outside the scheduler callback, through the shared Apply lane. */
internal class StudioRotationCommands(
    private val store: StudioAppearanceStore,
    private val submitSchedule: suspend (execute: suspend () -> RotationCommandOutcome) -> RotationCommandOutcome,
    private val ioDispatcher: CoroutineDispatcher,
    private val observe: suspend () -> RotationObservation?,
    private val submitStep: suspend (prepare: suspend () -> RotationStepAdmission) -> RotationCommandOutcome,
    private val operationId: () -> UUID = UUID::randomUUID,
    private val randomColor: (AyuVariant) -> String = ContrastAwareColorGenerator::generate,
) {
    suspend fun execute(command: RotationRuntimeCommand.Transaction): RotationCommandOutcome =
        when (command) {
            is RotationRuntimeCommand.PersistCheckpoint -> persist(command)
            is RotationRuntimeCommand.StepDue -> submitStep { prepareStep(command) }
        }

    private suspend fun persist(command: RotationRuntimeCommand.PersistCheckpoint): RotationCommandOutcome =
        submitSchedule {
            val current =
                observe() ?: return@submitSchedule RotationCommandOutcome.Rejected(RotationStepAdmission.Stale)
            val execution =
                withContext(ioDispatcher) {
                    commitRotationSchedule(
                        store,
                        command,
                        current.snapshot,
                        current.lease.documentRevision,
                        operationId(),
                    )
                }
            RotationCommandOutcome.Executed(execution)
        }

    private suspend fun prepareStep(command: RotationRuntimeCommand.StepDue): RotationStepAdmission {
        val current = observe() ?: return RotationStepAdmission.Stale
        val attempt = RotationStepAttempt(operationId())
        val admitted = prepare(command, current, attempt)
        if (admitted !is RotationStepAdmission.NeedsRandom) return admitted

        val color = randomColor(admitted.request.variant)
        val refreshed = observe() ?: return RotationStepAdmission.Stale
        if (current != refreshed) return RotationStepAdmission.Stale
        return prepare(command, refreshed, attempt.copy(randomHex = color))
    }

    private suspend fun prepare(
        command: RotationRuntimeCommand.StepDue,
        current: RotationObservation,
        attempt: RotationStepAttempt,
    ): RotationStepAdmission =
        withContext(ioDispatcher) {
            prepareRotationStep(store, command, current.snapshot, current.lease, attempt)
        }
}
