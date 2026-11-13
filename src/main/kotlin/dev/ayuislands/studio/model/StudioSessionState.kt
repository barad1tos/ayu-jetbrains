package dev.ayuislands.studio.model

@JvmInline
internal value class DraftId(
    val value: String,
)

internal data class DraftRecord(
    val id: DraftId,
    val source: ProfileReference?,
    val base: AcceptedAppearance,
    val appearance: AppearanceSnapshot,
    val sourceName: String? = null,
    val tracking: DraftTracking = DraftTracking(appearance),
)

internal sealed interface CatalogIntent {
    data class Create(
        val definition: ProfileDefinition.User,
        val draft: DraftId,
    ) : CatalogIntent

    data class Update(
        val before: ProfileDefinition.User,
        val after: ProfileDefinition.User,
        val draft: DraftId,
    ) : CatalogIntent

    data class Rename(
        val before: ProfileDefinition.User,
        val name: String,
    ) : CatalogIntent

    data class Delete(
        val before: ProfileDefinition.User,
    ) : CatalogIntent
}

internal sealed interface SharedIntent {
    data class Field(
        val before: StudioField,
        val after: StudioField,
    ) : SharedIntent

    data class SystemPolicy(
        val before: SystemAccentPolicy,
        val after: SystemAccentPolicy,
    ) : SharedIntent
}

internal data class SessionWork(
    val drafts: StudioList<DraftRecord>,
    val selected: DraftId,
    val catalog: StudioList<CatalogIntent>,
    val shared: StudioList<SharedIntent>,
)

internal enum class SessionAvailability { LIVE, RECOVERED, RETIRED }

internal data class StudioSessionRecord(
    val id: String,
    val generation: Long,
    val target: StudioTarget,
    val baseDocumentRevision: Long,
    val work: SessionWork,
    val availability: SessionAvailability,
)
