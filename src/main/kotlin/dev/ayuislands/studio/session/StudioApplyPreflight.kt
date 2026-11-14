package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.AcceptanceBlock
import dev.ayuislands.studio.model.AcceptanceLease
import dev.ayuislands.studio.model.AcceptanceReview
import dev.ayuislands.studio.model.ApplyCandidate
import dev.ayuislands.studio.model.ApplyContext
import dev.ayuislands.studio.model.ApplyPreflight
import dev.ayuislands.studio.model.ApplyRequest
import dev.ayuislands.studio.model.EffectLimitation
import dev.ayuislands.studio.model.EffectObservation
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FontAvailability
import dev.ayuislands.studio.model.GlowDependency
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioEffectRoute
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioGlowSelection
import dev.ayuislands.studio.model.StudioIntegrationSelection
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget

internal object StudioApplyPreflight {
    fun plan(
        request: ApplyRequest,
        latest: StudioDocument,
        context: ApplyContext,
        effects: EffectObservation,
    ): ApplyPreflight =
        when (val candidate = StudioApplyCandidate.plan(request, latest, context)) {
            is ApplyCandidate.Blocked -> ApplyPreflight.Blocked(candidate.reason)
            is ApplyCandidate.Review -> ApplyPreflight.Review(candidate.items)
            is ApplyCandidate.Ready -> preflight(request, latest, context, effects, candidate)
        }

    private fun preflight(
        request: ApplyRequest,
        latest: StudioDocument,
        context: ApplyContext,
        effects: EffectObservation,
        candidate: ApplyCandidate.Ready,
    ): ApplyPreflight {
        val target = context.observation.target
        val requested = StudioEffectPolicy.route(latest, candidate, target)
        val limitations = effects.limitations.filter { it.owner in requested.owners }
        val route = allowedRoute(requested, limitations)
        val font = requestedFont(candidate, route, target.target)
        if (font != null && StudioAcceptanceMerge.changed(effects.font.requested, font)) {
            return ApplyPreflight.Blocked(AcceptanceBlock.FONT_REFRESH)
        }
        val observed = effects.native.filter { it.owner in route.owners }
        val approved = observed.associateWith { change -> request.choices.native.singleOrNull { it.change == change } }
        val review = approved.filterValues { it == null }.keys.map(AcceptanceReview::Native)
        if (review.isNotEmpty()) return ApplyPreflight.Review(StudioList(review))

        if (font != null) {
            fontRequirement(effects.font.availability)?.let { return ApplyPreflight.Blocked(it) }
        }

        return ApplyPreflight.Ready(
            candidate = candidate,
            route = route,
            limitations = StudioList(limitations),
            native = StudioList(approved.values.filterNotNull()),
            lease = AcceptanceLease(latest.header.revision, target.generation, effects.capabilityRevision),
        )
    }

    private fun allowedRoute(
        requested: StudioEffectRoute,
        limitations: List<EffectLimitation>,
    ): StudioEffectRoute {
        val blocked = limitations.filter { it.blocksEffect }.map { it.owner }.toSet()
        val glow = allowedGlow(requested.glow, blocked)
        val owners = requested.owners.filter { it !in blocked && (it != EffectOwner.GLOW || requiresGlow(glow)) }
        return requested.copy(
            owners = StudioList(owners),
            workspace = if (EffectOwner.WORKSPACE in owners) requested.workspace else StudioList(emptyList()),
            integrations = if (EffectOwner.ACCENT in owners) requested.integrations else StudioIntegrationSelection(),
            glow = glow,
            syncAppearance = EffectOwner.SYSTEM in owners && requested.syncAppearance,
        )
    }

    private fun allowedGlow(
        selection: StudioGlowSelection,
        blocked: Set<EffectOwner>,
    ): StudioGlowSelection {
        val reclassify = EffectOwner.ACCENT !in blocked && selection.shouldReclassifyLanguage
        if (EffectOwner.GLOW in blocked) return StudioGlowSelection(shouldReclassifyLanguage = reclassify)
        return selection.copy(
            dependencies =
                StudioList(
                    selection.dependencies.filter {
                        it != GlowDependency.ACCENT ||
                            EffectOwner.ACCENT !in blocked
                    },
                ),
            shouldReclassifyLanguage = reclassify,
        )
    }

    private fun requiresGlow(selection: StudioGlowSelection): Boolean =
        selection.fields.isNotEmpty() || selection.dependencies.isNotEmpty()

    private fun requestedFont(
        candidate: ApplyCandidate.Ready,
        route: StudioEffectRoute,
        target: StudioTarget,
    ): StudioFields? {
        if (EffectOwner.FONT !in route.owners) return null
        val font = requireNotNull(StudioTargets.current(candidate.document, target)).appearance.font
        return font.takeIf {
            it.records.single { field -> field.key.name == "fontPresetEnabled" }.value == RawValue.BooleanValue(true)
        }
    }

    private fun fontRequirement(availability: FontAvailability): AcceptanceBlock? =
        when (availability) {
            FontAvailability.AVAILABLE -> null
            FontAvailability.MISSING_CURATED -> AcceptanceBlock.FONT_INSTALL
            FontAvailability.MISSING_CUSTOM -> AcceptanceBlock.FONT_SELECT
            FontAvailability.CORRUPTED -> AcceptanceBlock.FONT_REINSTALL
            FontAvailability.RESTART_REQUIRED -> AcceptanceBlock.FONT_RESTART
            FontAvailability.UNCONFIRMED -> AcceptanceBlock.FONT_REFRESH
        }
}
