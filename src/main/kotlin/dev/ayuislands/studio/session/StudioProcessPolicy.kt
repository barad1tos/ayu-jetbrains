package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.model.StudioProcessRelation

/** Compares provenance of temporary native objects, never persistence or process liveness. */
internal object StudioProcessPolicy {
    fun compare(
        recorded: StudioProcess,
        current: StudioProcess?,
    ): StudioProcessRelation =
        when {
            current == null -> StudioProcessRelation.UNCONFIRMED
            recorded.pid != current.pid -> StudioProcessRelation.DIFFERENT
            recorded.startedAt == null || current.startedAt == null -> StudioProcessRelation.UNCONFIRMED
            recorded.startedAt == current.startedAt -> StudioProcessRelation.SAME
            else -> StudioProcessRelation.DIFFERENT
        }
}
