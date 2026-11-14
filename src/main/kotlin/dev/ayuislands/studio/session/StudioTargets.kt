package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.AcceptedAppearance
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget

internal object StudioTargets {
    fun current(
        document: StudioDocument,
        target: StudioTarget,
    ): AcceptedAppearance? =
        when (target) {
            StudioTarget.Global -> document.currents.global
            is StudioTarget.Project ->
                document.currents.projects
                    .singleOrNull { it.key == target.key }
                    ?.current
        }

    fun replace(
        document: StudioDocument,
        target: StudioTarget,
        accepted: AcceptedAppearance,
    ): StudioDocument =
        when (target) {
            StudioTarget.Global -> document.copy(currents = document.currents.copy(global = accepted))
            is StudioTarget.Project -> {
                require(document.currents.projects.any { it.key == target.key }) { "Target has no accepted record" }
                document.copy(
                    currents =
                        document.currents.copy(
                            projects =
                                StudioList(
                                    document.currents.projects.map {
                                        if (it.key ==
                                            target.key
                                        ) {
                                            it.copy(current = accepted)
                                        } else {
                                            it
                                        }
                                    },
                                ),
                        ),
                )
            }
        }
}
