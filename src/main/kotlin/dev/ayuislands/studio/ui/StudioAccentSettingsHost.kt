package dev.ayuislands.studio.ui

import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.studio.model.AcceptanceBlock
import dev.ayuislands.studio.model.ApplyRequest
import dev.ayuislands.studio.model.AuthorityStatus
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.DraftRecord
import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.PreviewMode
import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.SessionWork
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProfileChoice
import dev.ayuislands.studio.model.StudioProfileKind
import dev.ayuislands.studio.model.StudioProfileLabel
import dev.ayuislands.studio.model.StudioProfileRow
import dev.ayuislands.studio.model.StudioProfileView
import dev.ayuislands.studio.model.StudioSettingsSection
import dev.ayuislands.studio.model.StudioSettingsView
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.session.ApplyExecution
import dev.ayuislands.studio.session.ApplySettlement
import dev.ayuislands.studio.session.StudioAccentEditing
import dev.ayuislands.studio.session.StudioApplyCandidate
import dev.ayuislands.studio.session.StudioApplyCoordinator
import dev.ayuislands.studio.session.StudioDraftPolicy
import dev.ayuislands.studio.session.StudioSessionRecords
import dev.ayuislands.studio.session.Submission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.swing.SwingUtilities

/** Binds one global Accent settings view to the supplied shared acceptance and draft writers. */
internal class StudioAccentSettingsHost(
    initial: StudioDocument,
    private val variant: AyuVariant,
    otherSections: List<StudioSectionContent>,
    private val coordinator: StudioApplyCoordinator,
    private val retireDraft: suspend (String) -> DraftWriteResult,
    createAccentSection: (editAccent: (DraftRecord) -> Unit) -> StudioAccentSection,
    version: String,
    openCommunityLink: (StudioCommunityLink) -> Unit,
) {
    private var document = initial
    private var session = newSession()
    private var currentDraft = session.work.selected
    private var preview = PreviewMode.CURRENT
    private var section = StudioSettingsSection.ACCENT
    private var authority = AuthorityStatus.READY
    private var pendingAcceptance: Submission.Retained? = null
    private var pendingRetirement: String? = null
    private var isBusy = false
    private val accent = createAccentSection(::editAccent)

    val panel =
        StudioSettingsPanel(
            listOf(accent.content) + otherSections,
            version = version,
            selectProfile = ::selectProfile,
            selectPreview = { mode ->
                preview = mode
                render()
            },
            selectSection = { section = it },
            openCommunityLink = openCommunityLink,
        ).also { render(it) }

    val isModified: Boolean
        get() = hasDraftChanges || isBusy || authority == AuthorityStatus.RECOVERY

    private val hasDraftChanges: Boolean
        get() = StudioApplyCandidate.hasPending(session.work)

    private val hasAcceptedCleanup: Boolean
        get() = pendingAcceptance?.execution?.settlement is ApplySettlement.AcceptedCleanupPending

    suspend fun apply(
        operation: UUID,
        targetGeneration: Long,
    ): Submission<ApplyExecution> =
        whileBusy {
            resumePending(operation)?.let { return@whileBusy it }
            pendingRetirement?.let { abandonedId ->
                val retirement = finishRetirement(abandonedId)
                if (retirement is DraftWriteResult.Failed) return@whileBusy Submission.FlushFailed(retirement.code)
            }
            reconcileCancellation(operation) {
                when (val result = coordinator.submit(ApplyRequest(session, targetGeneration), operation)) {
                    is Submission.Admitted -> result.also { settle(operation, it.result) }
                    is Submission.Retained -> result.also { settle(it.operation, it.execution) }
                    is Submission.StoreUnavailable -> result.also { authority = AuthorityStatus.READ_ONLY }
                    is Submission.Recovery -> result.also { authority = AuthorityStatus.RECOVERY }
                    else -> result
                }
            }
        }

    private suspend fun resumePending(operation: UUID): Submission<ApplyExecution>? {
        val retained = pendingAcceptance ?: return null
        val execution = requireNotNull(resumeApply(retained.operation))
        return when {
            execution.settlement !is ApplySettlement.Finished || !hasDraftChanges -> Submission.Admitted(execution)
            operation == retained.operation -> Submission.Blocked(AcceptanceBlock.INVALID_REQUEST)
            else -> null
        }
    }

    private suspend fun resumeApply(operation: UUID): ApplyExecution? =
        reconcileCancellation(operation) { coordinator.retry(operation)?.also { settle(operation, it) } }

    private suspend fun <T> reconcileCancellation(
        operation: UUID,
        action: suspend () -> T,
    ): T =
        try {
            action()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                coordinator.outcome(operation)?.let { settle(operation, it) }
            }
            throw cancelled
        }

    suspend fun cancel(): DraftWriteResult =
        whileBusy {
            currentCoroutineContext().ensureActive()
            val result =
                withContext(NonCancellable) {
                    val abandoned = pendingRetirement ?: session.id.takeIf { hasDraftChanges }
                    val retirement =
                        if (abandoned == null) {
                            DraftWriteResult.Saved(document.header.revision)
                        } else {
                            finishRetirement(abandoned)
                        }
                    // A failed acknowledgement still detaches this draft from the view.
                    session = newSession()
                    currentDraft = session.work.selected
                    preview = PreviewMode.CURRENT
                    retirement
                }
            currentCoroutineContext().ensureActive()
            result
        }

    private suspend fun finishRetirement(sessionId: String): DraftWriteResult =
        retireDraft(sessionId).also { result ->
            pendingRetirement = if (result is DraftWriteResult.Failed) sessionId else null
        }

    private suspend fun <T> whileBusy(action: suspend () -> T): T {
        check(SwingUtilities.isEventDispatchThread() && !isBusy) { "The Studio view is busy or not on EDT" }
        isBusy = true
        return try {
            render()
            action()
        } finally {
            isBusy = false
            render()
        }
    }

    private suspend fun settle(
        operation: UUID,
        execution: ApplyExecution,
    ) {
        val outcome = execution.settlement
        when (outcome) {
            is ApplySettlement.Accepted -> refreshAccepted(outcome.document)
            is ApplySettlement.AcceptedCleanupPending -> refreshAccepted(outcome.document)
            else -> Unit
        }
        val isFinished = outcome is ApplySettlement.Finished
        pendingAcceptance = if (isFinished) null else Submission.Retained(operation, execution)
        authority = if (isFinished) AuthorityStatus.READY else AuthorityStatus.RECOVERY
        if (isFinished) coordinator.acknowledge(operation)
    }

    private fun refreshAccepted(accepted: StudioDocument) {
        if (accepted.header == document.header) return
        document = accepted
        accepted.sessions
            .singleOrNull {
                it.id == session.id && it.generation > session.generation
            }?.let {
                session = it
                currentDraft = it.work.selected
            }
        preview = PreviewMode.CURRENT
    }

    private fun newSession() =
        requireNotNull(
            StudioSessionRecords.current(document, StudioTarget.Global, UUID.randomUUID().toString(), newDraftId()),
        )

    private fun editAccent(draft: DraftRecord) {
        val work = session.work
        val drafts = work.drafts.map { if (it.id == draft.id) draft else it }
        update(work.copy(drafts = StudioList(drafts)))
    }

    private fun selectProfile(choice: StudioProfileChoice) {
        val work = session.work
        val selected =
            when (choice) {
                is StudioProfileChoice.Draft -> work.copy(selected = choice.id)
                is StudioProfileChoice.Profile -> {
                    val definition = document.catalog.single { it.id == choice.id }
                    StudioDraftPolicy.select(work, definition, newDraftId(), document.currents.global)
                }
            }
        update(selected)
    }

    private fun update(work: SessionWork) {
        session = session.copy(generation = session.generation + 1, work = work)
        preview = PreviewMode.DRAFT
        render()
    }

    private fun render(target: StudioSettingsPanel = panel) {
        val work = session.work
        val selected = work.drafts.single { it.id == work.selected }
        val accepted = document.currents.global
        val isEditable = !isBusy && (authority == AuthorityStatus.READY || hasAcceptedCleanup)
        accent.render(
            StudioAccentView(variant, selected, accepted.appearance, document.shared.systemAccentPolicy, isEditable),
        )
        val profiles = profileView(selected, isEditable)
        target.render(
            StudioSettingsView(
                profiles,
                section,
                authority,
                StudioAccentEditing.sample(accepted.appearance, variant),
                StudioList(emptyList()),
                acceptedCleanupPending = hasAcceptedCleanup,
            ),
        )
    }

    private fun profileView(
        selected: DraftRecord,
        isEditable: Boolean,
    ): StudioProfileView {
        val current = document.currents.global
        val name = document.catalog.singleOrNull { it.id == current.source?.id }?.name ?: "Custom"
        val rows =
            listOf(
                StudioProfileRow(
                    StudioProfileChoice.Draft(currentDraft),
                    StudioProfileLabel(name),
                    StudioProfileKind.DETACHED,
                    StudioList(emptyList()),
                    isCurrent = true,
                    isEnabled = isEditable,
                ),
            ) +
                document.catalog.map { profile ->
                    StudioProfileRow(
                        StudioProfileChoice.Profile(profile.id),
                        StudioProfileLabel(profile.name),
                        if (profile is ProfileDefinition.Factory) StudioProfileKind.FACTORY else StudioProfileKind.USER,
                        StudioList(emptyList()),
                        isCurrent = profile.id == current.source?.id,
                        isEnabled = isEditable,
                    )
                }
        val choice =
            if (selected.id == currentDraft) {
                StudioProfileChoice.Draft(selected.id)
            } else {
                StudioProfileChoice.Profile(requireNotNull(selected.source).id)
            }
        return StudioProfileView(StudioList(rows), choice, name, preview, !isBusy, hasDraftChanges)
    }

    private fun newDraftId() = DraftId(UUID.randomUUID().toString())
}
