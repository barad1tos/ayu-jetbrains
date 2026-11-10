package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.CatalogIntent
import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProfileChange
import dev.ayuislands.studio.model.StudioProfileChoice
import dev.ayuislands.studio.model.StudioProfileKind
import dev.ayuislands.studio.model.StudioProfileRow
import dev.ayuislands.studio.model.StudioProfileView
import dev.ayuislands.studio.model.StudioSessionState

internal object StudioProfileProjection {
    fun project(
        document: StudioDocument,
        state: StudioSessionState,
    ): StudioProfileView {
        val work = state.record?.work
        val intents = work?.catalog.orEmpty()
        val profiles = document.catalog + intents.filterIsInstance<CatalogIntent.Create>().map { it.definition }
        val labels = StudioProfileLabels.project(document, work)
        val current = StudioTargets.current(document, state.identity.target)
        val profileRows =
            profiles.map { profile ->
                val pending = intents.filter { StudioCatalogStaging.profileId(it) == profile.id }
                StudioProfileRow(
                    StudioProfileChoice.Profile(profile.id),
                    labels.getValue(StudioProfileChoice.Profile(profile.id)),
                    if (profile is ProfileDefinition.Factory) StudioProfileKind.FACTORY else StudioProfileKind.USER,
                    StudioList(pending.map(::change)),
                    isCurrent = current?.source?.id == profile.id,
                    // This probe is never dispatched. Permission checks the phase,
                    // not draft allocation or payload validity.
                    isEnabled =
                        work != null &&
                            SessionEdits.permits(state.phase, SessionIntent.Browse(profile.id, work.selected)),
                )
            }
        val profileIds = profiles.map { it.id }.toSet()
        val detachedRows =
            work?.drafts.orEmpty().filter { it.source?.id !in profileIds }.map { draft ->
                StudioProfileRow(
                    StudioProfileChoice.Draft(draft.id),
                    labels.getValue(StudioProfileChoice.Draft(draft.id)),
                    StudioProfileKind.DETACHED,
                    StudioList(emptyList()),
                    isCurrent = false,
                    isEnabled = SessionEdits.permits(state.phase, SessionIntent.EditDraft(draft.id)),
                )
            }
        val selectedDraft = work?.drafts?.singleOrNull { it.id == work.selected }
        val selected =
            selectedDraft?.let { draft ->
                val source = draft.source?.id
                if (source in
                    profileIds
                ) {
                    StudioProfileChoice.Profile(requireNotNull(source))
                } else {
                    StudioProfileChoice.Draft(draft.id)
                }
            }
        return StudioProfileView(
            StudioList(profileRows + detachedRows),
            selected,
            document.catalog.singleOrNull { it.id == current?.source?.id }?.name
                ?: StudioProfileLabels.CURRENT_APPEARANCE,
            state.view.preview,
            isPreviewEnabled =
                work != null && SessionEdits.permits(state.phase, SessionIntent.Preview(state.view.preview)),
            hasPendingChanges = work != null && StudioAcceptancePlanner.hasPending(work),
        )
    }

    private fun change(intent: CatalogIntent): StudioProfileChange =
        when (intent) {
            is CatalogIntent.Create -> StudioProfileChange.CREATE
            is CatalogIntent.Rename -> StudioProfileChange.RENAME
            is CatalogIntent.Update -> StudioProfileChange.UPDATE
            is CatalogIntent.Delete -> StudioProfileChange.DELETE
        }
}
