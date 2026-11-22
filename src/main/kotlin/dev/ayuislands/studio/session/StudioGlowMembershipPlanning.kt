package dev.ayuislands.studio.session

import dev.ayuislands.glow.GlowIsland
import dev.ayuislands.settings.GlowSettings
import dev.ayuislands.studio.model.GlowDependency
import dev.ayuislands.studio.model.StudioGlowField
import dev.ayuislands.studio.model.StudioGlowSelection
import dev.ayuislands.studio.model.StudioGlowTarget
import dev.ayuislands.studio.model.StudioList

/** Resolves the island toggles selected by Apply against the observed native IDs. */
internal object StudioGlowMembershipPlanning {
    data class Delta(
        val additions: StudioList<String>,
        val retirements: StudioList<String>,
    )

    class Selection internal constructor(
        private val selected: Map<GlowIsland, Boolean>,
    ) {
        val enabled: Set<GlowIsland> get() = selected.filterValues { it }.keys

        fun plan(
            mounted: List<String>,
            candidates: Set<String>,
        ): Delta {
            val installed = mounted.toSet()
            return Delta(
                StudioList(candidates.filter { it !in installed && selected[GlowIsland.fromId(it)] == true }),
                StudioList(mounted.filter { selected[GlowIsland.fromId(it)] == false }),
            )
        }
    }

    fun select(
        selection: StudioGlowSelection,
        settings: GlowSettings,
        available: Boolean,
    ): Selection? {
        val keys = selection.selectedSettingKeys()
        val includesAll = StudioGlowField.ENABLED.key in keys || GlowDependency.AVAILABILITY in selection.dependencies
        val enabled = available && settings.enabled
        val selected =
            StudioGlowTarget.entries
                .filter { includesAll || it.field.key in keys }
                .associate { it.island to (enabled && settings.islandToggles[it.islandId] == true) }
        return selected.takeIf { it.isNotEmpty() }?.let(::Selection)
    }
}
