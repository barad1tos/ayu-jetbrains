package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.AcceptanceChoices
import dev.ayuislands.studio.model.AcceptanceReview
import dev.ayuislands.studio.model.ConflictChoice
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldPart
import dev.ayuislands.studio.model.PolicyConflict
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.SharedPreferences
import dev.ayuislands.studio.model.StudioField
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList

internal object StudioSharedMerge {
    fun merge(
        latest: SharedPreferences,
        intents: List<SharedIntent>,
        choices: AcceptanceChoices,
        review: MutableList<AcceptanceReview>,
    ): SharedPreferences {
        var result = latest
        for (intent in intents) {
            result =
                when (intent) {
                    is SharedIntent.Field -> mergeField(result, intent, choices, review)
                    is SharedIntent.SystemPolicy -> mergePolicy(result, intent, choices, review)
                }
        }
        return result
    }

    private fun mergeField(
        latest: SharedPreferences,
        intent: SharedIntent.Field,
        choices: AcceptanceChoices,
        review: MutableList<AcceptanceReview>,
    ): SharedPreferences {
        val current =
            listOf(latest.plugins, latest.workspace, latest.system, latest.mappings).flatMap { it.records }.single {
                it.key ==
                    intent.before.key
            }
        val result =
            StudioConflictPolicy.merge(
                StudioFields(StudioList(listOf(intent.before))),
                StudioFields(StudioList(listOf(intent.after))),
                StudioFields(StudioList(listOf(current))),
                StudioList(addresses(intent)),
                choices.fields,
            )
        review.addAll(result.conflicts.map(AcceptanceReview::Field))
        val replacement = result.fields.records.single()
        return latest.copy(
            plugins = replace(latest.plugins, replacement),
            workspace = replace(latest.workspace, replacement),
            system = replace(latest.system, replacement),
            mappings = replace(latest.mappings, replacement),
        )
    }

    private fun mergePolicy(
        latest: SharedPreferences,
        intent: SharedIntent.SystemPolicy,
        choices: AcceptanceChoices,
        review: MutableList<AcceptanceReview>,
    ): SharedPreferences {
        val conflict = PolicyConflict(intent.before, latest.systemAccentPolicy, intent.after)
        val value =
            when {
                intent.after == latest.systemAccentPolicy -> latest.systemAccentPolicy
                intent.before == latest.systemAccentPolicy -> intent.after
                else ->
                    when (choices.policy?.takeIf { it.conflict == conflict }?.choice) {
                        ConflictChoice.KEEP_LATEST -> latest.systemAccentPolicy
                        ConflictChoice.USE_MINE -> intent.after
                        null -> {
                            review.add(AcceptanceReview.Policy(conflict))
                            latest.systemAccentPolicy
                        }
                    }
            }
        return latest.copy(systemAccentPolicy = value)
    }

    private fun addresses(intent: SharedIntent.Field): List<FieldAddress> {
        val before = intent.before.value as? RawValue.OrderedMap
        val after = intent.after.value as? RawValue.OrderedMap
        if (before == null || after == null) return listOf(FieldAddress(intent.before.key))
        val keys = (before.entries.map { it.key } + after.entries.map { it.key }).distinct()
        val changed =
            keys.filter { key ->
                before.entries.singleOrNull { it.key == key } !=
                    after.entries.singleOrNull { it.key == key }
            }
        val addresses = changed.map { FieldAddress(intent.before.key, FieldPart.MapEntry(it)) }
        val oldOrder = before.entries.map { it.key }.filter { key -> after.entries.any { it.key == key } }
        val newOrder = after.entries.map { it.key }.filter { key -> before.entries.any { it.key == key } }
        return if (oldOrder == newOrder) addresses else addresses + FieldAddress(intent.before.key, FieldPart.MapOrder)
    }

    private fun replace(
        fields: StudioFields,
        replacement: StudioField,
    ): StudioFields =
        fields.copy(
            records = StudioList(fields.records.map { if (it.key == replacement.key) replacement else it }),
        )
}
