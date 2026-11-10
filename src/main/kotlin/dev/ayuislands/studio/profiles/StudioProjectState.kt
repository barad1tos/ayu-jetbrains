package dev.ayuislands.studio.profiles

import dev.ayuislands.studio.migration.StudioFieldMapping
import dev.ayuislands.studio.model.AcceptedAppearance
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioProjectRecord

internal object StudioProjectState {
    fun initialize(
        document: StudioDocument,
        key: StudioProjectKey,
        displayName: String,
        accepted: AcceptedAppearance,
    ): StudioDocument {
        if (document.currents.projects.any { it.key == key }) return document
        val independent =
            accepted.copy(
                appearance = StudioFieldMapping.copyAppearance(accepted.appearance),
                source = null,
            )
        val project = StudioProjectRecord(key, displayName, independent)
        return document.copy(
            currents =
                document.currents.copy(
                    projects =
                        StudioList(
                            document.currents.projects + project,
                        ),
                ),
        )
    }
}
