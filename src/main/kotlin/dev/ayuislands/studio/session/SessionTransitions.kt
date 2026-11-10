package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.AcceptanceChoices
import dev.ayuislands.studio.model.AcceptanceOrigin
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.CallbackStamp
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.OperationStage
import dev.ayuislands.studio.model.OperationStamp
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.SessionCommand
import dev.ayuislands.studio.model.SessionHandling
import dev.ayuislands.studio.model.SessionJournalStatus
import dev.ayuislands.studio.model.SessionNotice
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.SessionTransition
import dev.ayuislands.studio.model.SessionWork
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.StudioSessionState

internal object SessionTransitions {
    fun handled(
        state: StudioSessionState,
        vararg commands: SessionCommand,
    ): SessionTransition = SessionTransition(state, StudioList(commands.toList()), SessionHandling.HANDLED)

    fun ignored(state: StudioSessionState): SessionTransition =
        SessionTransition(state, StudioList(emptyList()), SessionHandling.IGNORED)

    fun rejected(
        state: StudioSessionState,
        reason: String,
    ): SessionTransition =
        SessionTransition(
            state.copy(notice = SessionNotice.Rejected(reason)),
            StudioList(emptyList()),
            SessionHandling.REJECTED,
        )

    fun invariant(
        state: StudioSessionState,
        reason: String,
    ): SessionTransition =
        SessionTransition(
            state.copy(notice = SessionNotice.Invariant(reason)),
            StudioList(emptyList()),
            SessionHandling.INVARIANT,
        )

    fun journalStamp(record: StudioSessionRecord): OperationStamp =
        OperationStamp(record.id, record.generation, "journal:${record.generation}")

    fun edited(
        state: StudioSessionState,
        work: SessionWork,
    ): SessionTransition {
        val current = requireNotNull(state.record)
        if (current.work == work) return handled(state)
        val record = current.copy(generation = current.generation + 1, work = work)
        val next =
            pruneCallbacks(
                state.copy(
                    record = record,
                    view = state.view.copy(preview = PreviewMode.DRAFT),
                    journal = SessionJournalStatus.Pending(record.generation),
                    notice = null,
                ),
            )
        if ((state.phase as? SessionPhase.Processing)?.stage == OperationStage.ACCEPTED_CLEANUP) return handled(next)
        return handled(
            trackJournal(next),
            SessionCommand.SaveDraft(record),
        )
    }

    fun trackJournal(state: StudioSessionState): StudioSessionState {
        val pruned = pruneCallbacks(state)
        return pruned.copy(
            async =
                pruned.async.copy(
                    journals = StudioList(listOf(journalStamp(requireNotNull(state.record)))),
                ),
        )
    }

    private fun pruneCallbacks(state: StudioSessionState): StudioSessionState {
        val generation = requireNotNull(state.record).generation
        val active = (state.phase as? SessionPhase.Processing)?.operation
        return state.copy(
            async =
                state.async.copy(
                    journals = StudioList(state.async.journals.filter { it.generation >= generation }),
                    completed =
                        StudioList(
                            state.async.completed.filter { stamp ->
                                stamp.operation.generation >= generation ||
                                    stamp.operation == active ||
                                    state.async.jobs.any { it.operation == stamp.operation }
                            },
                        ),
                ),
        )
    }

    fun cleanRecord(
        state: StudioSessionState,
        latest: StudioDocument,
    ): StudioSessionRecord {
        val record = requireNotNull(state.record)
        val retained = record.work.drafts.single { it.id == record.work.selected }
        val current =
            StudioTargets
                .current(latest, record.target)
                ?.takeIf { latest.header.revision >= record.baseDocumentRevision } ?: retained.base
        val name = latest.catalog.singleOrNull { it.id == current.source?.id }?.name
        val draft = DraftRecord(record.work.selected, current.source, current, current.appearance, sourceName = name)
        return record.copy(
            generation = record.generation + 1,
            baseDocumentRevision = maxOf(latest.header.revision, record.baseDocumentRevision),
            work = SessionWork(StudioList(listOf(draft)), draft.id, StudioList(emptyList()), StudioList(emptyList())),
        )
    }

    fun request(
        state: StudioSessionState,
        operationId: String,
        choices: AcceptanceChoices = AcceptanceChoices(),
    ): AcceptanceRequest =
        AcceptanceRequest(
            requireNotNull(state.record),
            operationId,
            state.identity.targetGeneration,
            AcceptanceOrigin.SETTINGS,
            choices,
        )

    fun completed(
        transition: SessionTransition,
        stamp: CallbackStamp,
    ): SessionTransition {
        if (transition.handling != SessionHandling.HANDLED) return transition
        val async = transition.state.async
        return transition.copy(
            state =
                transition.state.copy(
                    async =
                        async.copy(
                            completed =
                                StudioList(
                                    async.completed + stamp,
                                ),
                        ),
                ),
        )
    }
}
