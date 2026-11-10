package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.DraftIntent
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.GlowDependency
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioDomain
import dev.ayuislands.studio.model.StudioGlowSelection
import dev.ayuislands.studio.model.StudioIntegrationSelection
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget

internal object StudioEffectPolicy {
    fun workspaceFields(
        before: StudioDocument,
        after: StudioDocument,
        effects: List<EffectOwner>,
    ): StudioList<FieldAddress> {
        if (EffectOwner.WORKSPACE !in effects) return StudioList(emptyList())
        val previous =
            before.shared.workspace.records
                .associate { it.key to it.value }
        return StudioList(
            after.shared.workspace.records
                .filter { previous[it.key] != it.value }
                .map { FieldAddress(it.key) },
        )
    }

    /** Enabling Follow requests the existing immediate sync; other system edits only persist intent. */
    fun shouldSyncAppearance(
        before: StudioDocument,
        after: StudioDocument,
    ): Boolean = !followsSystemAppearance(before) && followsSystemAppearance(after)

    fun integrationSelection(
        before: StudioDocument,
        after: StudioDocument,
        effects: List<EffectOwner>,
    ): StudioIntegrationSelection {
        if (EffectOwner.ACCENT !in effects) return StudioIntegrationSelection()
        val previous = StudioPluginsEditing.settings(before.shared.plugins).integrations
        val requested = StudioPluginsEditing.settings(after.shared.plugins).integrations
        return StudioIntegrationSelection(
            enablesCodeGlance = !previous.codeGlanceEnabled && requested.codeGlanceEnabled,
            enablesIndentRainbow = !previous.indentEnabled && requested.indentEnabled,
        )
    }

    fun requested(
        before: StudioDocument,
        after: StudioDocument,
        draft: DraftRecord,
        target: StudioTarget,
        glow: StudioGlowSelection,
    ): StudioList<EffectOwner> {
        val owners = glowOwners(glow).toMutableSet()
        val current = requireNotNull(StudioTargets.current(before, target))
        val proposed = requireNotNull(StudioTargets.current(after, target))
        val reapply = StudioAcceptanceMerge.reapplyOwners(draft)
        for (domain in StudioDomain.entries) {
            if (domain == StudioDomain.GLOW) continue
            val changed =
                StudioAcceptanceMerge.changed(
                    StudioAcceptanceMerge.fields(current.appearance, domain),
                    StudioAcceptanceMerge.fields(proposed.appearance, domain),
                )
            if (draft.tracking.intent == DraftIntent.FULL_PROFILE ||
                domain in reapply ||
                changed
            ) {
                owners.add(owner(domain))
            }
        }
        if (StudioAcceptanceMerge.changed(
                before.shared.workspace,
                after.shared.workspace,
            )
        ) {
            owners.add(EffectOwner.WORKSPACE)
        }
        owners.addAll(pluginOwners(before, after))
        if (StudioAcceptanceMerge.changed(before.shared.system, after.shared.system)) owners.add(EffectOwner.SYSTEM)
        if (StudioAcceptanceMerge.changed(before.shared.mappings, after.shared.mappings) ||
            before.shared.systemAccentPolicy != after.shared.systemAccentPolicy
        ) {
            owners.add(EffectOwner.ACCENT)
        }
        return StudioList(EffectOwner.entries.filter { it in owners })
    }

    private fun pluginOwners(
        before: StudioDocument,
        after: StudioDocument,
    ): Set<EffectOwner> {
        val previous = StudioPluginsEditing.settings(before.shared.plugins)
        val requested = StudioPluginsEditing.settings(after.shared.plugins)
        return buildSet {
            if (previous.integrations.ignoreColorsEnabled != requested.integrations.ignoreColorsEnabled) {
                add(EffectOwner.SYNTAX)
            }
            val integrations =
                previous.integrations.copy(
                    ignoreColorsEnabled = requested.integrations.ignoreColorsEnabled,
                )
            if (previous.external != requested.external ||
                integrations != requested.integrations
            ) {
                add(EffectOwner.ACCENT)
            }
        }
    }

    private fun glowOwners(glow: StudioGlowSelection): Set<EffectOwner> =
        buildSet {
            if (GlowDependency.ACCENT in glow.dependencies) add(EffectOwner.ACCENT)
            if (glow.fields.isNotEmpty() || glow.dependencies.isNotEmpty()) add(EffectOwner.GLOW)
        }

    private fun followsSystemAppearance(document: StudioDocument): Boolean =
        document.shared.system.records
            .single { it.key.name == "followSystemAppearance" }
            .value == RawValue.BooleanValue(true)

    private fun owner(domain: StudioDomain): EffectOwner =
        when (domain) {
            StudioDomain.ACCENT -> EffectOwner.ACCENT
            StudioDomain.FONT -> EffectOwner.FONT
            StudioDomain.GLOW -> EffectOwner.GLOW
            StudioDomain.SYNTAX -> EffectOwner.SYNTAX
            StudioDomain.VCS -> EffectOwner.VCS
        }
}
