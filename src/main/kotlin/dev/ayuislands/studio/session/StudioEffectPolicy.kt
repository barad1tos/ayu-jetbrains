package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.ApplyCandidate
import dev.ayuislands.studio.model.DraftIntent
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.GlowDependency
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioDomain
import dev.ayuislands.studio.model.StudioEffectRoute
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioIntegrationSelection
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.TargetObservation
import dev.ayuislands.studio.model.TargetPresence

/** Projects accepted values onto existing effect owners without accessing any native state. */
internal object StudioEffectPolicy {
    fun route(
        before: StudioDocument,
        candidate: ApplyCandidate.Ready,
        observed: TargetObservation,
    ): StudioEffectRoute {
        if (observed.presence != TargetPresence.ACTIVE) return StudioEffectRoute()
        val after = candidate.document
        val owners = appearanceOwners(before, candidate, observed)
        val glow = StudioGlowSelectionPolicy.select(before, after, candidate.draft, observed.target)
        if (glow.fields.isNotEmpty() || glow.dependencies.isNotEmpty()) owners.add(EffectOwner.GLOW)
        if (GlowDependency.ACCENT in glow.dependencies) owners.add(EffectOwner.ACCENT)

        val workspace = changedFields(before.shared.workspace, after.shared.workspace)
        if (workspace.isNotEmpty()) owners.add(EffectOwner.WORKSPACE)
        if (StudioAcceptanceMerge.changed(before.shared.system, after.shared.system)) owners.add(EffectOwner.SYSTEM)
        if (StudioAcceptanceMerge.changed(before.shared.mappings, after.shared.mappings) ||
            before.shared.systemAccentPolicy != after.shared.systemAccentPolicy
        ) {
            owners.add(EffectOwner.ACCENT)
        }
        owners.addAll(pluginOwners(before.shared.plugins, after.shared.plugins))

        return StudioEffectRoute(
            owners = StudioList(EffectOwner.entries.filter { it in owners }),
            workspace = workspace,
            integrations = integrations(before.shared.plugins, after.shared.plugins),
            glow = glow,
            syncAppearance = StudioGlowSelectionPolicy.shouldSyncAppearance(before, after),
        )
    }

    private fun appearanceOwners(
        before: StudioDocument,
        candidate: ApplyCandidate.Ready,
        observed: TargetObservation,
    ): MutableSet<EffectOwner> {
        val current = requireNotNull(StudioTargets.current(before, observed.target)).appearance
        val proposed = requireNotNull(StudioTargets.current(candidate.document, observed.target)).appearance
        val draft = candidate.draft
        val reapply = StudioAcceptanceMerge.reapplyOwners(draft)
        return buildSet {
            for (domain in StudioDomain.entries) {
                if (domain == StudioDomain.GLOW) continue
                val previous = StudioAcceptanceMerge.fields(current, domain)
                val requested = StudioAcceptanceMerge.fields(proposed, domain)
                if (draft.tracking.intent == DraftIntent.FULL_PROFILE ||
                    domain in reapply ||
                    StudioAcceptanceMerge.changed(previous, requested)
                ) {
                    add(owner(domain))
                }
            }
        }.toMutableSet()
    }

    private fun changedFields(
        before: StudioFields,
        after: StudioFields,
    ): StudioList<FieldAddress> {
        val previous = before.records.associate { it.key to it.value }
        return StudioList(after.records.filter { previous[it.key] != it.value }.map { FieldAddress(it.key) })
    }

    private fun pluginOwners(
        before: StudioFields,
        after: StudioFields,
    ): Set<EffectOwner> =
        changedFields(before, after).mapTo(mutableSetOf()) {
            if (it.field.name == "ignorePluginSyntaxColorsEnabled") EffectOwner.SYNTAX else EffectOwner.ACCENT
        }

    private fun integrations(
        before: StudioFields,
        after: StudioFields,
    ): StudioIntegrationSelection =
        StudioIntegrationSelection(
            enablesCodeGlance = newlyEnabled(before, after, "cgpIntegrationEnabled"),
            enablesIndentRainbow = newlyEnabled(before, after, "irIntegrationEnabled"),
        )

    private fun newlyEnabled(
        before: StudioFields,
        after: StudioFields,
        field: String,
    ): Boolean =
        before.records.single { it.key.name == field }.value == RawValue.BooleanValue(false) &&
            after.records.single { it.key.name == field }.value == RawValue.BooleanValue(true)

    private fun owner(domain: StudioDomain): EffectOwner =
        when (domain) {
            StudioDomain.ACCENT -> EffectOwner.ACCENT
            StudioDomain.FONT -> EffectOwner.FONT
            StudioDomain.GLOW -> EffectOwner.GLOW
            StudioDomain.SYNTAX -> EffectOwner.SYNTAX
            StudioDomain.VCS -> EffectOwner.VCS
        }
}
