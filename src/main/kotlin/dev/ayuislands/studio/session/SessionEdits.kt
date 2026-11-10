package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.CatalogIntent
import dev.ayuislands.studio.model.CatalogStage
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.FontJob
import dev.ayuislands.studio.model.OperationStage
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.SessionCommand
import dev.ayuislands.studio.model.SessionInputs
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SessionJournalStatus
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.SessionTransition
import dev.ayuislands.studio.model.SessionWork
import dev.ayuislands.studio.model.SharedIntent
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSessionState
import dev.ayuislands.studio.model.StudioTarget

internal object SessionEdits {
    fun apply(
        state: StudioSessionState,
        intent: SessionIntent,
        inputs: SessionInputs,
    ): SessionTransition {
        if (!permits(
                state.phase,
                intent,
            )
        ) {
            return SessionTransitions.rejected(state, "The current operation must settle first")
        }
        if (intent is SessionIntent.Preview) {
            return SessionTransitions.handled(
                state.copy(view = state.view.copy(preview = intent.mode)),
            )
        }
        if (intent ==
            SessionIntent.CancelReview
        ) {
            return SessionTransitions.handled(state.copy(phase = SessionPhase.Editing, notice = null))
        }
        if (intent is SessionIntent.Resource) return resource(state, intent.job)
        val record = requireNotNull(state.record)
        if (intent == SessionIntent.Reset) {
            val clean = SessionTransitions.cleanRecord(state, inputs.latest)
            val next =
                state.copy(
                    record = clean,
                    phase = SessionPhase.Editing,
                    view = state.view.copy(preview = PreviewMode.CURRENT),
                    journal = SessionJournalStatus.Pending(clean.generation),
                )
            return SessionTransitions.handled(
                SessionTransitions.trackJournal(next),
                SessionCommand.SaveDraft(clean),
            )
        }
        return try {
            val work = editWork(record.work, intent, inputs, record.target)
            val next = if (intent is SessionIntent.EditDraft) state.copy(phase = SessionPhase.Editing) else state
            SessionTransitions.edited(next, work)
        } catch (failure: IllegalArgumentException) {
            SessionTransitions.rejected(state, failure.message ?: "Invalid setting change")
        }
    }

    /** Checks phase permission only; it neither validates the intent payload nor executes the event. */
    fun permits(
        phase: SessionPhase,
        intent: SessionIntent,
    ): Boolean =
        when (phase) {
            SessionPhase.Loading, SessionPhase.Retired -> {
                false
            }

            SessionPhase.Editing -> {
                true
            }

            is SessionPhase.Reviewing -> {
                when (intent) {
                    is SessionIntent.EditDraft, is SessionIntent.Catalog, is SessionIntent.Discard,
                    is SessionIntent.Preview, SessionIntent.CancelReview,
                    -> true

                    else -> false
                }
            }

            is SessionPhase.Processing -> {
                when (phase.stage) {
                    OperationStage.ACCEPTED_CLEANUP -> {
                        intent !is SessionIntent.Resource &&
                            intent != SessionIntent.Reset &&
                            intent != SessionIntent.CancelReview
                    }

                    OperationStage.RECOVERY_REQUIRED -> {
                        intent is SessionIntent.Preview
                    }

                    OperationStage.PREPARING, OperationStage.APPLYING,
                    OperationStage.RESOLVING_COMMIT, OperationStage.ROLLING_BACK,
                    -> {
                        false
                    }
                }
            }
        }

    private fun editWork(
        work: SessionWork,
        intent: SessionIntent,
        inputs: SessionInputs,
        target: StudioTarget,
    ): SessionWork =
        when (intent) {
            is SessionIntent.EditField -> {
                replaceDraft(work, intent.draft) {
                    StudioDraftPolicy.edit(it, intent.address, intent.value, intent.rotationOrigin)
                }
            }

            is SessionIntent.Browse -> {
                val profiles =
                    inputs.latest.catalog + work.catalog.filterIsInstance<CatalogIntent.Create>().map { it.definition }
                val profile =
                    requireNotNull(
                        profiles.singleOrNull { it.id == intent.profile },
                    ) { "Profile is no longer available" }
                StudioDraftPolicy.select(
                    work,
                    profile,
                    intent.draft,
                    requireNotNull(StudioTargets.current(inputs.latest, target)),
                )
            }

            is SessionIntent.EditDraft -> {
                require(work.drafts.any { it.id == intent.draft }) { "Draft is no longer available" }
                work.copy(selected = intent.draft)
            }

            is SessionIntent.Catalog -> {
                when (val staged = StudioCatalogPolicy.stage(inputs.latest, work, intent.command)) {
                    is CatalogStage.Staged -> staged.work
                    is CatalogStage.Rejected -> throw IllegalArgumentException(staged.error.name)
                }
            }

            is SessionIntent.Shared -> {
                editShared(work, intent.intent)
            }

            is SessionIntent.Discard -> {
                replaceDraft(work, intent.draft, StudioDraftPolicy::discard)
            }

            is SessionIntent.Reapply -> {
                replaceDraft(work, intent.draft) { draft ->
                    StudioSessionValidation.validateAddresses(draft.appearance, intent.addresses)
                    draft.copy(
                        tracking =
                            draft.tracking.copy(
                                reapply = StudioList((draft.tracking.reapply + intent.addresses).distinct()),
                            ),
                    )
                }
            }

            is SessionIntent.Preview, is SessionIntent.Resource, SessionIntent.Reset, SessionIntent.CancelReview -> {
                error(
                    "Presentation intent reached field reduction",
                )
            }
        }

    private fun replaceDraft(
        work: SessionWork,
        id: DraftId,
        transform: (DraftRecord) -> DraftRecord,
    ): SessionWork {
        require(work.drafts.any { it.id == id }) { "Draft is no longer available" }
        return work.copy(drafts = StudioList(work.drafts.map { if (it.id == id) transform(it) else it }))
    }

    private fun editShared(
        work: SessionWork,
        intent: SharedIntent,
    ): SessionWork {
        StudioSessionValidation.validateShared(listOf(intent))
        val previous = work.shared.singleOrNull { sameSetting(it, intent) }
        val updated =
            when (previous) {
                is SharedIntent.Field -> previous.copy(after = (intent as SharedIntent.Field).after)
                is SharedIntent.SystemPolicy -> previous.copy(after = (intent as SharedIntent.SystemPolicy).after)
                null -> intent
            }
        val redundant =
            when (updated) {
                is SharedIntent.Field -> updated.before.value == updated.after.value
                is SharedIntent.SystemPolicy -> updated.before == updated.after
            }
        val intents =
            if (previous ==
                null
            ) {
                work.shared + updated
            } else {
                work.shared.map { if (it == previous) updated else it }
            }
        return work.copy(shared = StudioList(if (redundant) intents.filterNot { it == updated } else intents))
    }

    private fun sameSetting(
        previous: SharedIntent,
        intent: SharedIntent,
    ): Boolean =
        when (previous) {
            is SharedIntent.Field -> intent is SharedIntent.Field && previous.before.key == intent.before.key
            is SharedIntent.SystemPolicy -> intent is SharedIntent.SystemPolicy
        }

    private fun resource(
        state: StudioSessionState,
        job: FontJob,
    ): SessionTransition {
        val record = requireNotNull(state.record)
        if (job.operation.session != record.id ||
            job.operation.generation != record.generation
        ) {
            return SessionTransitions.rejected(state, "The font request is stale")
        }
        if (state.async.jobs.any { it.operation == job.operation }) return SessionTransitions.ignored(state)
        return SessionTransitions.handled(
            state.copy(async = state.async.copy(jobs = StudioList(state.async.jobs + job))),
            SessionCommand.StartFontJob(job),
        )
    }
}
