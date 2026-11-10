package dev.ayuislands.studio.session

import com.intellij.openapi.project.Project
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.ResolvedAccent
import dev.ayuislands.studio.model.StudioAcceptedInput
import dev.ayuislands.studio.model.StudioList

/** Confirmed project colors retain native project identity and the outer lease's census order. */
internal data class StudioProjectAccent(
    val project: Project,
    val accent: ResolvedAccent,
    val dependencies: StudioList<StudioAcceptedInput> = StudioList(emptyList()),
)

/** Shared focus may differ from its project's color; an inactive context has no resolved colors. */
internal data class StudioAccentResult(
    val context: AccentContext?,
    val focusedProject: Project?,
    val focused: ResolvedAccent?,
    val projects: StudioList<StudioProjectAccent>,
    val dependencies: StudioList<StudioAcceptedInput> = StudioList(emptyList()),
)
