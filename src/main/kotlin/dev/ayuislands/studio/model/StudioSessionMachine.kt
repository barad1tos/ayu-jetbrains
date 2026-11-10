package dev.ayuislands.studio.model

import dev.ayuislands.rotation.RotationAcceptanceOrigin

internal data class SessionIdentity(
    val id: String,
    val target: StudioTarget,
    val targetGeneration: Long,
)

internal data class OperationStamp(
    val session: String,
    val generation: Long,
    val id: String,
)

internal data class CallbackStamp(
    val operation: OperationStamp,
    val step: String,
)

internal enum class OperationStage {
    PREPARING,
    APPLYING,
    RESOLVING_COMMIT,
    ROLLING_BACK,
    ACCEPTED_CLEANUP,
    RECOVERY_REQUIRED,
}

internal enum class CloseRequest { NONE, CANCEL, AMBIGUOUS, SHUTDOWN }

internal sealed interface SessionPhase {
    data object Loading : SessionPhase

    data object Editing : SessionPhase

    data class Reviewing(
        val requestId: String,
        val items: StudioList<AcceptanceReview>,
    ) : SessionPhase

    data class Processing(
        val stage: OperationStage,
        val operation: OperationStamp,
        val close: CloseRequest = CloseRequest.NONE,
    ) : SessionPhase

    data object Retired : SessionPhase
}

internal enum class PreviewMode { CURRENT, DRAFT }

internal enum class ViewAttachment { ATTACHED, DETACHED }

internal data class SessionView(
    val preview: PreviewMode = PreviewMode.CURRENT,
    val attachment: ViewAttachment = ViewAttachment.ATTACHED,
    val native: StudioList<NativeSurfaceChange> = StudioList(emptyList()),
)

internal sealed interface SessionJournalStatus {
    data object Idle : SessionJournalStatus

    data class Pending(
        val generation: Long,
    ) : SessionJournalStatus

    data class Saved(
        val generation: Long,
        val revision: Long,
    ) : SessionJournalStatus

    data class Failed(
        val generation: Long,
        val code: String,
    ) : SessionJournalStatus
}

internal enum class FontAction { INSTALL, REINSTALL, UNINSTALL }

internal enum class FontJobStatus { INSTALLED, DECLINED, FAILED, CANCELLED, REMOVED, PARTIAL, RESTART_REQUIRED }

internal data class FontJob(
    val operation: OperationStamp,
    val fontId: String,
    val action: FontAction,
)

internal sealed interface SessionNotice {
    data class Rejected(
        val reason: String,
    ) : SessionNotice

    data class Invariant(
        val reason: String,
    ) : SessionNotice

    data class Blocked(
        val reasons: StudioList<AcceptanceBlock>,
    ) : SessionNotice

    data class Failure(
        val code: String,
    ) : SessionNotice

    data class Resource(
        val fontId: String,
        val status: FontJobStatus,
    ) : SessionNotice

    data object NoChanges : SessionNotice
}

internal data class SessionAsync(
    val completed: StudioList<CallbackStamp> = StudioList(emptyList()),
    val jobs: StudioList<FontJob> = StudioList(emptyList()),
    val journals: StudioList<OperationStamp> = StudioList(emptyList()),
)

internal data class StudioSessionState(
    val identity: SessionIdentity,
    val record: StudioSessionRecord?,
    val phase: SessionPhase,
    val view: SessionView = SessionView(),
    val journal: SessionJournalStatus = SessionJournalStatus.Idle,
    val async: SessionAsync = SessionAsync(),
    val notice: SessionNotice? = null,
)

internal sealed interface SessionIntent {
    data class EditField(
        val draft: DraftId,
        val address: FieldAddress,
        val value: FieldValue,
        val rotationOrigin: RotationAcceptanceOrigin? = null,
    ) : SessionIntent

    data class Browse(
        val profile: ProfileId,
        val draft: DraftId,
    ) : SessionIntent

    data class EditDraft(
        val draft: DraftId,
    ) : SessionIntent

    data class Catalog(
        val command: CatalogCommand,
    ) : SessionIntent

    data class Shared(
        val intent: SharedIntent,
    ) : SessionIntent

    data class Discard(
        val draft: DraftId,
    ) : SessionIntent

    data class Reapply(
        val draft: DraftId,
        val addresses: StudioList<FieldAddress>,
    ) : SessionIntent

    data class Preview(
        val mode: PreviewMode,
    ) : SessionIntent

    data class Resource(
        val job: FontJob,
    ) : SessionIntent

    data object Reset : SessionIntent

    data object CancelReview : SessionIntent
}

internal sealed interface SessionObservation {
    data class IntentRevision(
        val revision: Long,
    ) : SessionObservation

    data class NativeSurface(
        val change: NativeSurfaceChange,
    ) : SessionObservation

    data class ResourceInventory(
        val fontId: String,
        val availability: FontAvailability,
    ) : SessionObservation
}

internal sealed interface SessionEffect {
    data class PreflightCompleted(
        val result: AcceptancePlanResult,
    ) : SessionEffect

    data object Prepared : SessionEffect

    data object SurfacesSettled : SessionEffect

    data object RollbackSettled : SessionEffect

    data object CleanupSettled : SessionEffect

    data class Failed(
        val code: String,
    ) : SessionEffect

    data class ResourceFinished(
        val status: FontJobStatus,
    ) : SessionEffect
}

internal sealed interface SessionJournalEvent {
    data object LoadProgress : SessionJournalEvent

    data class Saved(
        val revision: Long,
    ) : SessionJournalEvent

    data class Failed(
        val code: String,
    ) : SessionJournalEvent

    data object Prepared : SessionJournalEvent

    data object ReceiptSaved : SessionJournalEvent

    data class Committed(
        val record: StudioSessionRecord,
    ) : SessionJournalEvent

    data class NotCommitted(
        val code: String,
    ) : SessionJournalEvent

    data object OutcomeUnknown : SessionJournalEvent

    data object RecoverySaved : SessionJournalEvent

    data object CleanupSaved : SessionJournalEvent
}

internal sealed interface SessionEvent {
    data class Loaded(
        val record: StudioSessionRecord,
    ) : SessionEvent

    data class Intent(
        val intent: SessionIntent,
    ) : SessionEvent

    data class AcceptRequested(
        val operationId: String,
    ) : SessionEvent

    data class ReviewResolved(
        val operationId: String,
        val choices: AcceptanceChoices,
    ) : SessionEvent

    data class HostClose(
        val request: CloseRequest,
    ) : SessionEvent

    data class Observation(
        val observation: SessionObservation,
    ) : SessionEvent

    data class EffectCompleted(
        val stamp: CallbackStamp,
        val result: SessionEffect,
    ) : SessionEvent

    data class JournalCompleted(
        val stamp: CallbackStamp,
        val result: SessionJournalEvent,
    ) : SessionEvent

    data class RetryRecovery(
        val operation: OperationStamp,
    ) : SessionEvent

    data object AttachUi : SessionEvent
}

internal sealed interface SessionCommand {
    data class DraftWriteFailed(
        val record: StudioSessionRecord,
        val code: String,
    ) : SessionCommand

    data class SaveDraft(
        val record: StudioSessionRecord,
    ) : SessionCommand

    data class Submit(
        val request: AcceptanceRequest,
    ) : SessionCommand

    data class Retire(
        val record: StudioSessionRecord,
    ) : SessionCommand

    data class Quarantine(
        val record: StudioSessionRecord,
    ) : SessionCommand

    data class Abort(
        val operation: OperationStamp,
    ) : SessionCommand

    data class Retry(
        val operation: OperationStamp,
    ) : SessionCommand

    data class SettleThenAccept(
        val operation: OperationStamp,
        val request: AcceptanceRequest,
    ) : SessionCommand

    data class QueueObservation(
        val observation: SessionObservation,
    ) : SessionCommand

    data class StartFontJob(
        val job: FontJob,
    ) : SessionCommand

    data class NewSession(
        val target: StudioTarget,
    ) : SessionCommand
}

internal enum class SessionHandling { HANDLED, REJECTED, INVARIANT, IGNORED }

internal data class SessionInputs(
    val latest: StudioDocument,
    val context: AcceptanceContext,
)

internal data class SessionTransition(
    val state: StudioSessionState,
    val commands: StudioList<SessionCommand>,
    val handling: SessionHandling,
)
