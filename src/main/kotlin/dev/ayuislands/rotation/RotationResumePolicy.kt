package dev.ayuislands.rotation

import dev.ayuislands.studio.model.StudioList

internal data class RotationResumeInput(
    val intent: RotationIntent,
    val availability: RotationAvailability,
)

internal sealed interface RotationResumeChange {
    data object Observation : RotationResumeChange

    data class Accepted(
        val origin: RotationAcceptanceOrigin,
    ) : RotationResumeChange

    data class Handoff(
        val kind: RotationHandoffKind,
    ) : RotationResumeChange
}

internal enum class ExternalCadencePlan { KEEP, START_AT_ACCEPTANCE }

internal sealed interface RotationResumePlan {
    data class Off(
        val availability: RotationAvailability,
    ) : RotationResumePlan

    data object Preserve : RotationResumePlan

    data class Reconcile(
        val availability: RotationAvailability,
        val resume: RotationResume,
        val externalCadence: ExternalCadencePlan,
    ) : RotationResumePlan {
        val pauseReasons: StudioList<RotationPauseReason> get() = availability.pauseReasons
    }
}

/** Select the next idle obligation after a qualified context or eligibility change. */
internal fun planRotationResume(
    saved: RotationResume,
    previous: RotationResumeInput,
    current: RotationResumeInput,
    change: RotationResumeChange,
): RotationResumePlan {
    val origin = (change as? RotationResumeChange.Accepted)?.origin
    val handoff = (change as? RotationResumeChange.Handoff)?.kind
    if (handoff == RotationHandoffKind.CONTINUITY ||
        origin == RotationAcceptanceOrigin.CATALOG_ONLY
    ) {
        return RotationResumePlan.Preserve
    }
    val availability = current.availability.retainDefinitiveEntitlement(previous.availability)
    if (!current.intent.enabled) return RotationResumePlan.Off(availability)
    val requested = requestedRotationResume(previous.intent, current.intent, origin)
    val switchesContext = handoff == RotationHandoffKind.FOCUS || origin == RotationAcceptanceOrigin.PROFILE_ACTIVATION
    val interrupted = switchesContext && (!previous.isEligible() || availability.pauseReasons.isNotEmpty())
    val resume = selectRotationResume(saved, requested, availability, interrupted)
    val cadence =
        if (resume == RotationResume.ExternalCadence && availability.variant == null && requested != null) {
            ExternalCadencePlan.START_AT_ACCEPTANCE
        } else {
            ExternalCadencePlan.KEEP
        }
    return RotationResumePlan.Reconcile(availability, resume, cadence)
}

private fun RotationResumeInput.isEligible(): Boolean = intent.enabled && availability.pauseReasons.isEmpty()

private fun requestedRotationResume(
    previous: RotationIntent,
    current: RotationIntent,
    origin: RotationAcceptanceOrigin?,
): RotationResume? =
    when {
        origin == RotationAcceptanceOrigin.EXPLICIT_ROTATION_ENABLE && !previous.enabled ->
            RotationResume.ImmediateEnable
        origin == RotationAcceptanceOrigin.LOCAL_ROTATION_EDIT &&
            (previous.modeRaw != current.modeRaw || previous.intervalHoursRaw != current.intervalHoursRaw) ->
            RotationResume.ImmediateConfiguration
        else -> null
    }

private fun selectRotationResume(
    saved: RotationResume,
    requested: RotationResume?,
    availability: RotationAvailability,
    interrupted: Boolean,
): RotationResume {
    val starting = requested == RotationResume.ImmediateEnable
    val eligible = availability.pauseReasons.isEmpty()
    val retained = if (starting) RotationResume.KeepDeadline else saved
    return when {
        starting && eligible -> RotationResume.ImmediateEnable
        availability.effectiveFollowSystem -> RotationResume.FullInterval(FullIntervalReason.FOLLOW_SYSTEM)
        interrupted -> RotationResume.FullInterval(FullIntervalReason.INTERRUPTED_HANDOFF)
        retained is RotationResume.FullInterval -> retained
        availability.variant == null || (retained == RotationResume.ExternalCadence && !eligible) ->
            RotationResume.ExternalCadence
        else -> requested ?: retained
    }
}
