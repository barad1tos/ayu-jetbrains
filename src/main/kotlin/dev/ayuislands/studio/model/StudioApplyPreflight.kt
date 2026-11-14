package dev.ayuislands.studio.model

internal enum class FontAvailability {
    AVAILABLE,
    MISSING_CURATED,
    MISSING_CUSTOM,
    CORRUPTED,
    RESTART_REQUIRED,
    UNCONFIRMED,
}

internal data class FontObservation(
    val requested: StudioFields,
    val availability: FontAvailability,
)

internal data class EffectObservation(
    val capabilityRevision: Long,
    val font: FontObservation? = null,
    val limitations: StudioList<EffectLimitation> = StudioList(emptyList()),
    val native: StudioList<NativeSurfaceChange> = StudioList(emptyList()),
)

internal data class NativeSurfaceChange(
    val owner: EffectOwner,
    val identity: String,
    val before: FieldValue,
    val actual: FieldValue,
    val requested: FieldValue,
)

internal data class NativeResolution(
    val change: NativeSurfaceChange,
    val choice: ConflictChoice,
)

/** Rechecked before preparing the durable operation and throughout native execution until commit. */
internal data class AcceptanceLease(
    val documentRevision: Long,
    val targetGeneration: Long,
    val capabilityRevision: Long,
)

internal sealed interface ApplyPreflight {
    data class Blocked(
        val reason: AcceptanceBlock,
    ) : ApplyPreflight

    data class Review(
        val items: StudioList<AcceptanceReview>,
    ) : ApplyPreflight

    data class Ready(
        val candidate: ApplyCandidate.Ready,
        val route: StudioEffectRoute,
        val limitations: StudioList<EffectLimitation>,
        val native: StudioList<NativeResolution>,
        val lease: AcceptanceLease,
    ) : ApplyPreflight
}
