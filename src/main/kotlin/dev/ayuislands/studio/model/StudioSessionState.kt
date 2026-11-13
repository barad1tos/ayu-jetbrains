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
    ) : CatalogIntent {
        init {
            require(before.id == after.id) { "Update cannot change profile identity" }
        }
    }

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
    ) : SharedIntent {
        init {
            require(before.key == after.key) { "Shared field identity cannot change" }
        }
    }

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
) {
    init {
        val draftIds = drafts.map { it.id }.toSet()
        require(draftIds.size == drafts.size) { "Duplicate draft identity" }
        require(selected in draftIds) { "Selected draft is missing" }
        for (intent in catalog) {
            when (intent) {
                is CatalogIntent.Create -> require(intent.draft in draftIds) { "Create intent draft is missing" }
                is CatalogIntent.Update -> require(intent.draft in draftIds) { "Update intent draft is missing" }
                is CatalogIntent.Rename, is CatalogIntent.Delete -> Unit
            }
        }
    }
}

internal enum class SessionAvailability { LIVE, RECOVERED, RETIRED }

internal data class StudioSessionRecord(
    val id: String,
    val generation: Long,
    val target: StudioTarget,
    val baseDocumentRevision: Long,
    val work: SessionWork,
    val availability: SessionAvailability,
)
