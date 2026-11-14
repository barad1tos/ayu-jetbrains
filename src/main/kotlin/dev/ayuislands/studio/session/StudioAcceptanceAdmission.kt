package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.AcceptanceBlock
import dev.ayuislands.studio.model.AcceptanceObservation
import dev.ayuislands.studio.model.AuthorityStatus
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.TargetPresence

internal object StudioAcceptanceAdmission {
    fun block(
        session: StudioSessionRecord,
        targetGeneration: Long,
        latest: StudioDocument,
        observation: AcceptanceObservation,
        requiresActive: Boolean = session.work.shared.isNotEmpty(),
    ): AcceptanceBlock? {
        val saved = latest.sessions.singleOrNull { it.id == session.id }
        val observed = observation.target
        return when {
            observation.authority == AuthorityStatus.READ_ONLY -> AcceptanceBlock.READ_ONLY
            observation.authority == AuthorityStatus.RECOVERY -> AcceptanceBlock.RECOVERY
            observed.presence == TargetPresence.UNAVAILABLE || StudioTargets.current(latest, session.target) == null ->
                AcceptanceBlock.TARGET_UNAVAILABLE
            observed.target != session.target || observed.generation != targetGeneration ->
                AcceptanceBlock.TARGET_REPLACED
            stale(session, saved) -> AcceptanceBlock.STALE_SESSION
            observed.presence == TargetPresence.INACTIVE && requiresActive -> AcceptanceBlock.ACTIVATE_TARGET
            else -> invalidSession(session)
        }
    }

    private fun stale(
        request: StudioSessionRecord,
        saved: StudioSessionRecord?,
    ): Boolean =
        request.availability != SessionAvailability.LIVE ||
            (
                saved != null &&
                    (
                        saved.availability != SessionAvailability.LIVE ||
                            saved.target != request.target ||
                            saved.generation > request.generation
                    )
            )

    private fun invalidSession(session: StudioSessionRecord): AcceptanceBlock? =
        try {
            StudioSessionValidation.validate(session)
            null
        } catch (_: IllegalArgumentException) {
            AcceptanceBlock.INVALID_REQUEST
        }
}
