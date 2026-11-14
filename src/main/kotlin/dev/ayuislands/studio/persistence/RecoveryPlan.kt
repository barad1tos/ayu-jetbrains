package dev.ayuislands.studio.persistence

import dev.ayuislands.rotation.RotationCandidate
import dev.ayuislands.rotation.RotationHandoffKind
import dev.ayuislands.studio.model.EffectLimitation
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioNativeChange
import dev.ayuislands.studio.model.StudioTarget
import java.util.UUID

internal data class RecoveryPlan(
    val target: StudioTarget,
    val owners: StudioList<EffectOwner>,
    val limitations: StudioList<EffectLimitation> = StudioList(emptyList()),
    val rotationStep: RotationCandidate? = null,
    val purpose: RecoveryPurpose = RecoveryPurpose.UserAcceptance,
)

/** Distinguishes automatic work without inferring meaning from opaque transaction identifiers. */
internal sealed interface RecoveryPurpose {
    data object UserAcceptance : RecoveryPurpose

    data object Metadata : RecoveryPurpose

    sealed interface Automatic : RecoveryPurpose {
        val operationId: UUID
    }

    data class RotationStep(
        override val operationId: UUID,
    ) : Automatic

    data class NativeChange(
        override val operationId: UUID,
        val change: StudioNativeChange,
    ) : Automatic

    data class ContextHandoff(
        override val operationId: UUID,
        val kind: RotationHandoffKind,
    ) : Automatic

    data class RotationSchedule(
        override val operationId: UUID,
    ) : Automatic
}
