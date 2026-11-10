package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.DraftIntent
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.GlowDependency
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioGlowSelection
import dev.ayuislands.studio.model.StudioGlowSources
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.SystemAccentPolicy

/** Selects accepted intent; the native owner still resolves actual color, eligibility and foreign edits. */
internal object StudioGlowSelectionPolicy {
    fun select(
        before: StudioDocument,
        after: StudioDocument,
        draft: DraftRecord,
        target: StudioTarget,
    ): StudioGlowSelection {
        val current = requireNotNull(StudioTargets.current(before, target)).appearance
        val proposed = requireNotNull(StudioTargets.current(after, target)).appearance
        val fields = selectFields(current.glow, proposed.glow, draft)
        val hasFollowChange =
            StudioAccentPolicy.followsSystem(current, before.shared.systemAccentPolicy) !=
                StudioAccentPolicy.followsSystem(proposed, after.shared.systemAccentPolicy)
        val hasThemeSwitch = StudioEffectPolicy.shouldSyncAppearance(before, after)
        val hasColorChange =
            draft.tracking.intent == DraftIntent.FULL_PROFILE ||
                hasFollowChange ||
                hasThemeSwitch ||
                changed(current.accent, proposed.accent, StudioGlowSources.accents) ||
                reappliesColor(draft, after.shared.systemAccentPolicy) ||
                requestsRotation(current.accent, proposed.accent, draft.tracking.intent) ||
                changed(before.shared.plugins, after.shared.plugins, StudioGlowSources.plugins) ||
                changed(before.shared.mappings, after.shared.mappings, StudioGlowSources.mappings)
        val dependencies = mutableListOf<GlowDependency>()
        if (hasColorChange) dependencies.add(GlowDependency.ACCENT)
        if (hasThemeSwitch || externalAllowed(before.shared.plugins) != externalAllowed(after.shared.plugins)) {
            dependencies.add(GlowDependency.AVAILABILITY)
        }
        return StudioGlowSelection(
            fields,
            StudioList(dependencies),
            StudioAccentProjection.reclassifiesLanguage(before.shared.mappings, after.shared.mappings),
        )
    }

    private fun selectFields(
        before: StudioFields,
        after: StudioFields,
        draft: DraftRecord,
    ): StudioList<FieldAddress> {
        val previous = before.records.associateBy { it.key }
        val isFullProfile = draft.tracking.intent == DraftIntent.FULL_PROFILE
        val selected =
            after.records
                .filter { isFullProfile || it.value != previous.getValue(it.key).value }
                .map { FieldAddress(it.key) }
        val reapplied = draft.tracking.reapply.filter { it.field in previous }
        return StudioList((selected + reapplied).distinct())
    }

    private fun reappliesColor(
        draft: DraftRecord,
        policy: SystemAccentPolicy,
    ): Boolean =
        draft.tracking.reapply.any {
            it.field.name in StudioGlowSources.accents ||
                (it.field.name == "followSystemAccent" && policy == SystemAccentPolicy.BY_PROFILE)
        }

    private fun requestsRotation(
        before: StudioFields,
        after: StudioFields,
        intent: DraftIntent,
    ): Boolean =
        intent != DraftIntent.FULL_PROFILE &&
            enabled(after, "accentRotationEnabled") &&
            (!enabled(before, "accentRotationEnabled") || changed(before, after, StudioGlowSources.rotation))

    private fun externalAllowed(fields: StudioFields): Boolean =
        enabled(fields, "externalThemeEnhancementsEnabled") && enabled(fields, "externalThemeGlowEnabled")

    private fun enabled(
        fields: StudioFields,
        name: String,
    ): Boolean = fields.records.single { it.key.name == name }.value == RawValue.BooleanValue(true)

    private fun changed(
        before: StudioFields,
        after: StudioFields,
        names: List<String>,
    ): Boolean =
        names.any { name ->
            before.records.single { it.key.name == name }.value != after.records.single { it.key.name == name }.value
        }
}
