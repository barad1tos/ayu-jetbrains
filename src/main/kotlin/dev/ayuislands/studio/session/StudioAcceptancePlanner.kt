package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.AcceptanceBlock
import dev.ayuislands.studio.model.AcceptanceChoices
import dev.ayuislands.studio.model.AcceptanceContext
import dev.ayuislands.studio.model.AcceptanceLease
import dev.ayuislands.studio.model.AcceptanceOrigin
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.AcceptancePlanResult
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.AcceptanceReview
import dev.ayuislands.studio.model.AuthorityStatus
import dev.ayuislands.studio.model.CatalogIntent
import dev.ayuislands.studio.model.CatalogPlan
import dev.ayuislands.studio.model.DraftIntent
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FontAvailability
import dev.ayuislands.studio.model.NativeResolution
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.SessionWork
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioGlowSelection
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioThemeChange
import dev.ayuislands.studio.model.TargetPresence

internal object StudioAcceptancePlanner {
    fun plan(
        request: AcceptanceRequest,
        latest: StudioDocument,
        context: AcceptanceContext,
    ): AcceptancePlanResult {
        val blocked = blockers(request, latest, context)
        if (blocked.isNotEmpty()) return AcceptancePlanResult.Blocked(StudioList(blocked))
        val review = mutableListOf<AcceptanceReview>()
        if (request.origin == AcceptanceOrigin.QUICK_SWITCHER) {
            review.addAll(
                (latest.sessions + context.liveSessions)
                    .associateBy { it.id }
                    .values
                    .filter {
                        it.id != request.session.id &&
                            it.availability == SessionAvailability.LIVE &&
                            hasPending(it.work)
                    }.map { AcceptanceReview.LiveSession(it.id) },
            )
        }
        val work = request.session.work
        review.addAll(
            StudioDraftPolicy
                .hiddenDirty(work)
                .filterNot {
                    captured(it, work.catalog)
                }.map { AcceptanceReview.HiddenDraft(it.id) },
        )
        val withCatalog = resolveCatalog(request, latest, review)
        val draft = work.drafts.single { it.id == work.selected }
        val current = requireNotNull(StudioTargets.current(latest, request.session.target))
        val appearance = StudioAcceptanceMerge.appearance(draft, current.appearance, request.choices, review)
        val shared = StudioSharedMerge.merge(latest.shared, work.shared, request.choices, review)
        val source = draft.source?.takeIf { reference -> withCatalog.catalog.any { it.id == reference.id } }
        val accepted = current.copy(appearance = appearance, revision = current.revision + 1, source = source)
        var candidate = withCatalog.copy(shared = shared)
        if (StudioAcceptanceMerge.hasAppearanceIntent(draft)) {
            candidate =
                StudioTargets.replace(candidate, request.session.target, accepted)
        }
        val initialized = StudioInitializationPolicy.apply(request, latest, candidate)
        val hasInitialization = initialized != candidate
        candidate = initialized
        candidate = StudioContinuationPolicy.accept(latest, candidate, draft, request.session.target)
        val glow =
            if (context.target.presence == TargetPresence.ACTIVE) {
                StudioGlowSelectionPolicy.select(latest, candidate, draft, request.session.target)
            } else {
                StudioGlowSelection()
            }
        val requested =
            if (context.target.presence == TargetPresence.ACTIVE) {
                StudioEffectPolicy.requested(latest, candidate, draft, request.session.target, glow)
            } else {
                StudioList(emptyList())
            }
        val limitations = context.capabilities.limitations.filter { it.owner in requested }
        val effects = requested.filter { owner -> limitations.none { it.owner == owner && it.blocksEffect } }
        val native = nativeChoices(context, effects, request.choices, review)
        if (review.isNotEmpty()) return AcceptancePlanResult.ReviewRequired(StudioList(review))
        val fontBlock = fontRequirement(candidate, request, effects, context)
        if (fontBlock != null) return AcceptancePlanResult.Blocked(StudioList(listOf(fontBlock)))
        if (!hasPending(work) && !hasInitialization) return AcceptancePlanResult.Ready(AcceptancePlan.Unchanged)
        candidate = acceptWork(candidate, request)
        return AcceptancePlanResult.Ready(
            AcceptancePlan.Commit(
                candidate,
                StudioList(effects),
                AcceptanceLease(latest.header.revision, context.target.generation, context.capabilities.revision),
                StudioList(limitations),
                native,
                glow,
                themeChange =
                    StudioThemeChange.FollowSystem.takeIf {
                        EffectOwner.SYSTEM in effects && StudioEffectPolicy.shouldSyncAppearance(latest, candidate)
                    },
                integrationSelection = StudioEffectPolicy.integrationSelection(latest, candidate, effects),
                workspaceFields = StudioEffectPolicy.workspaceFields(latest, candidate, effects),
            ),
        )
    }

    private fun resolveCatalog(
        request: AcceptanceRequest,
        latest: StudioDocument,
        review: MutableList<AcceptanceReview>,
    ): StudioDocument =
        when (val catalog = StudioCatalogPolicy.plan(latest, request.session, request.choices.catalog)) {
            is CatalogPlan.Ready -> catalog.document
            is CatalogPlan.Review -> {
                review.addAll(catalog.items.map(AcceptanceReview::Catalog))
                latest
            }
        }

    private fun nativeChoices(
        context: AcceptanceContext,
        effects: List<EffectOwner>,
        choices: AcceptanceChoices,
        review: MutableList<AcceptanceReview>,
    ): StudioList<NativeResolution> {
        val observed = context.native.filter { it.owner in effects }
        for (change in observed) {
            if (choices.native.none { it.change == change }) review.add(AcceptanceReview.Native(change))
        }
        return StudioList(choices.native.filter { it.change in observed })
    }

    fun hasPending(work: SessionWork): Boolean =
        work.catalog.isNotEmpty() ||
            work.shared.isNotEmpty() ||
            work.drafts.any {
                StudioDraftPolicy.isDirty(it) ||
                    (it.id == work.selected && it.tracking.intent == DraftIntent.FULL_PROFILE)
            }

    private fun blockers(
        request: AcceptanceRequest,
        latest: StudioDocument,
        context: AcceptanceContext,
    ): List<AcceptanceBlock> {
        when (context.authority) {
            AuthorityStatus.READ_ONLY -> return listOf(AcceptanceBlock.READ_ONLY)
            AuthorityStatus.RECOVERY -> return listOf(AcceptanceBlock.RECOVERY)
            AuthorityStatus.READY -> Unit
        }
        if (context.target.presence == TargetPresence.UNAVAILABLE ||
            StudioTargets.current(latest, request.session.target) == null
        ) {
            return listOf(AcceptanceBlock.TARGET_UNAVAILABLE)
        }
        if (context.target.target != request.session.target ||
            context.target.generation != request.targetGeneration
        ) {
            return listOf(AcceptanceBlock.TARGET_REPLACED)
        }
        if (context.target.presence == TargetPresence.INACTIVE &&
            (
                request.session.work.shared
                    .isNotEmpty() ||
                    request.initialization != null
            )
        ) {
            return listOf(AcceptanceBlock.ACTIVATE_TARGET)
        }
        if (!StudioIconPinPolicy.accepts(request, latest)) return listOf(AcceptanceBlock.INVALID_REQUEST)
        return try {
            StudioSessionValidation.validate(request.session)
            emptyList()
        } catch (_: IllegalArgumentException) {
            listOf(AcceptanceBlock.INVALID_REQUEST)
        }
    }

    private fun captured(
        draft: DraftRecord,
        intents: List<CatalogIntent>,
    ): Boolean =
        draft.tracking.reapply.isEmpty() &&
            intents.any { intent ->
                when (intent) {
                    is CatalogIntent.Create -> {
                        intent.draft == draft.id &&
                            intent.definition.appearance == draft.appearance
                    }

                    is CatalogIntent.Update -> {
                        intent.draft == draft.id && intent.after.appearance == draft.appearance
                    }

                    is CatalogIntent.Delete, is CatalogIntent.Rename -> {
                        false
                    }
                }
            }

    private fun fontRequirement(
        candidate: StudioDocument,
        request: AcceptanceRequest,
        effects: List<EffectOwner>,
        context: AcceptanceContext,
    ): AcceptanceBlock? {
        if (EffectOwner.FONT !in effects) return null
        val font = requireNotNull(StudioTargets.current(candidate, request.session.target)).appearance.font
        val enabled = font.records.single { it.key.name == "fontPresetEnabled" }.value == RawValue.BooleanValue(true)
        if (!enabled) return null
        if (StudioAcceptanceMerge.changed(context.font.requested, font)) return AcceptanceBlock.FONT_REFRESH
        return when (context.font.availability) {
            FontAvailability.AVAILABLE -> null
            FontAvailability.MISSING_CURATED -> AcceptanceBlock.FONT_INSTALL
            FontAvailability.MISSING_CUSTOM -> AcceptanceBlock.FONT_SELECT
            FontAvailability.CORRUPTED -> AcceptanceBlock.FONT_REINSTALL
            FontAvailability.RESTART_REQUIRED -> AcceptanceBlock.FONT_RESTART
            FontAvailability.UNCONFIRMED -> AcceptanceBlock.FONT_REFRESH
        }
    }

    private fun acceptWork(
        candidate: StudioDocument,
        request: AcceptanceRequest,
    ): StudioDocument {
        val current = requireNotNull(StudioTargets.current(candidate, request.session.target))
        val revision = candidate.header.revision + 1
        val sessions =
            candidate.sessions.map { record ->
                if (record.id != request.session.id) return@map record
                val drafts =
                    record.work.drafts.map { draft ->
                        val selected = draft.id == record.work.selected
                        val appearance = if (selected) current.appearance else draft.appearance
                        draft.copy(
                            base = current,
                            appearance = appearance,
                            tracking =
                                draft.tracking.copy(
                                    initial = appearance,
                                    touched = StudioList(emptyList()),
                                    reapply = StudioList(emptyList()),
                                    intent = if (selected) DraftIntent.EDITS else draft.tracking.intent,
                                    rotationOrigin = if (selected) null else draft.tracking.rotationOrigin,
                                ),
                        )
                    }
                record.copy(
                    generation = record.generation + 1,
                    baseDocumentRevision = revision,
                    work =
                        record.work.copy(
                            drafts = StudioList(drafts),
                            catalog = StudioList(emptyList()),
                            shared = StudioList(emptyList()),
                        ),
                )
            }
        return candidate.copy(
            header = candidate.header.copy(revision = revision, transactionId = request.transactionId),
            sessions = StudioList(sessions),
        )
    }
}
