package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.SharedPreferences
import dev.ayuislands.studio.model.StudioField
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSessionState

/** Pending shared preferences are a view of explicit intents, never another accepted authority. */
internal object StudioSettingsProjection {
    fun shared(
        accepted: SharedPreferences,
        intents: List<SharedIntent>,
    ): SharedPreferences =
        intents.fold(accepted) { current, intent ->
            when (intent) {
                is SharedIntent.SystemPolicy -> {
                    current.copy(systemAccentPolicy = intent.after)
                }

                is SharedIntent.Field -> {
                    current.copy(
                        plugins = replace(current.plugins, intent.after),
                        workspace = replace(current.workspace, intent.after),
                        system = replace(current.system, intent.after),
                        mappings = replace(current.mappings, intent.after),
                    )
                }
            }
        }

    fun isModified(state: StudioSessionState): Boolean =
        state.phase is SessionPhase.Processing ||
            state.phase is SessionPhase.Reviewing ||
            state.record?.work?.let(StudioAcceptancePlanner::hasPending) == true

    private fun replace(
        fields: StudioFields,
        replacement: StudioField,
    ): StudioFields =
        fields.copy(records = StudioList(fields.records.map { if (it.key == replacement.key) replacement else it }))
}
