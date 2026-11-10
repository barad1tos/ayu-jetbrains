package dev.ayuislands.studio.session

import dev.ayuislands.font.FontApplyOrigin
import dev.ayuislands.rotation.RotationCandidate
import dev.ayuislands.rotation.RotationSnapshot
import dev.ayuislands.studio.model.AcceptanceContext
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.ApplyFailure
import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.NativeResolution
import dev.ayuislands.studio.model.RuntimeFieldChange
import dev.ayuislands.studio.model.StudioApplyUpdate
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioGlowSelection
import dev.ayuislands.studio.model.StudioIntegrationSelection
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.model.StudioRuntimeState
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.StudioThemeChange
import dev.ayuislands.studio.persistence.PendingJournal
import dev.ayuislands.studio.persistence.PortableReceipt
import java.util.UUID

/** Native calls are awaited on the host's modal EDT context; flush may enter the shared store mutex. */
internal interface StudioApplyEnvironment {
    /** Deliver on the session dispatcher and await it before the next dependent effect. */
    suspend fun publish(update: StudioApplyUpdate)

    suspend fun flush(record: StudioSessionRecord): DraftWriteResult

    suspend fun observe(
        request: AcceptanceRequest,
        document: StudioDocument,
    ): AcceptanceContext

    /** Prepare the validated candidate before journaling, without setters; retain partially acquired resources. */
    suspend fun prepareAcceptance(
        request: AcceptanceRequest,
        plan: AcceptancePlan.Commit,
    ): StudioAcceptancePreparation

    /** Acquire automatic work without flushing drafts or manufacturing a user acceptance request. */
    suspend fun acquireRuntime(
        operationId: UUID,
        snapshot: RotationSnapshot,
        plan: AcceptancePlan.Commit,
    ): StudioAcceptancePreparation

    suspend fun resume(journal: PendingJournal): StudioRuntimeLease
}

/** Blocked preparation owns no resources; even an invalid partial lease must be returned for cleanup. */
internal sealed interface StudioAcceptancePreparation {
    data class Prepared(
        val lease: StudioRuntimeLease,
        val plan: AcceptancePlan.Commit,
    ) : StudioAcceptancePreparation

    data class Blocked(
        val failure: ApplyFailure,
    ) : StudioAcceptancePreparation
}

/** Native resources owned by the outer operation, separate from declarative candidate inputs. */
internal interface StudioEffectRuntime {
    val glow: StudioGlowHolds?
    val native: StudioNativeFacts
    val accent: StudioAccentResult?

    /** Actual native identities captured by this lease; null when those handles are unavailable. */
    val observation: StudioNativeObservation?

    /** Publish Accent only after its native writes and refresh have succeeded under this same lease. */
    suspend fun confirmAccent(result: StudioAccentResult)

    /** Advance held appearance only after this operation's verified forward SYSTEM switch and refresh. */
    suspend fun confirmTheme(transition: StudioThemeTransition)
}

/** Confirmed entitlement and process observations supplied by the held outer runtime lease. */
internal data class StudioNativeFacts(
    val isLicensed: Boolean,
    val currentProcess: StudioProcess?,
)

/** Suppresses competing runtime producers without changing their stored preferences. */
internal interface StudioRuntimeLease : StudioEffectRuntime {
    suspend fun isValid(): Boolean

    suspend fun settledRuntime(candidate: StudioDocument): StudioRuntimeState

    suspend fun release(accepted: StudioDocument?)
}

internal data class StudioEffectInput(
    val candidate: StudioDocument,
    val target: StudioTarget,
    val choices: StudioList<NativeResolution>,
    val glowSelection: StudioGlowSelection = StudioGlowSelection(),
    val themeChange: StudioThemeChange? = null,
    val integrationSelection: StudioIntegrationSelection = StudioIntegrationSelection(),
    val workspaceFields: StudioList<FieldAddress> = StudioList(emptyList()),
    val rotationStep: RotationCandidate? = null,
    val fontOrigin: FontApplyOrigin = FontApplyOrigin.EXPLICIT,
)

internal interface StudioEffectParticipant {
    val owner: EffectOwner

    /** Capture only. All returned receipts must become durable before apply is called. */
    suspend fun prepare(
        input: StudioEffectInput,
        runtime: StudioEffectRuntime,
    ): StudioEffectOperation

    /** Reacquire owner-verified identities without replaying any setter. */
    suspend fun recover(
        input: StudioEffectInput,
        receipts: StudioList<PortableReceipt>,
        runtime: StudioEffectRuntime,
    ): StudioEffectOperation
}

/** Owner-specific recovery retains partial progress and respects foreign writes on every retry. */
internal interface StudioEffectOperation {
    val receipts: StudioList<PortableReceipt>
    val runtimeChanges: StudioList<RuntimeFieldChange>

    suspend fun apply()

    /** Capture new rollback evidence only; the coordinator must acknowledge it before restore. */
    suspend fun prepareRestore(blockedBy: StudioList<PortableReceipt>)

    /** Newer unresolved writes on the same field take precedence, even when values happen to be equal. */
    suspend fun restore(blockedBy: StudioList<PortableReceipt>): StudioRestoreContinuation

    suspend fun accept()
}

/** A successful restore may require one fresh capture/acknowledgement step; failures never request retries. */
internal enum class StudioRestoreContinuation { NONE, REQUIRED }

/** Qualified transaction phase callbacks; they cannot replace journal or native settlement. */
internal interface StudioApplyProgress {
    fun prepared()

    fun settled(runtime: StudioRuntimeState): StudioRuntimeState
}
