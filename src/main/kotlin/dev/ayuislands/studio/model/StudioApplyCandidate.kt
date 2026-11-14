package dev.ayuislands.studio.model

internal enum class ApplyOrigin { SETTINGS, QUICK_SWITCHER }

internal data class ApplyRequest(
    val session: StudioSessionRecord,
    val targetGeneration: Long,
    val origin: ApplyOrigin = ApplyOrigin.SETTINGS,
    val choices: AcceptanceChoices = AcceptanceChoices(),
)

internal data class ApplyContext(
    val observation: AcceptanceObservation,
    val liveSessions: StudioList<StudioSessionRecord> = StudioList(emptyList()),
)

internal sealed interface ApplyCandidate {
    data class Blocked(
        val reason: AcceptanceBlock,
    ) : ApplyCandidate

    data class Review(
        val items: StudioList<AcceptanceReview>,
    ) : ApplyCandidate

    data class Ready(
        val document: StudioDocument,
        val draft: DraftRecord,
        val hasWork: Boolean,
    ) : ApplyCandidate
}
