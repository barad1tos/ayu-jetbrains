package dev.ayuislands.studio.session

import com.intellij.openapi.application.EDT
import dev.ayuislands.studio.captureStudioModality
import dev.ayuislands.studio.model.AcceptanceContext
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.DraftRestore
import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.FontJob
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.SessionEvent
import dev.ayuislands.studio.model.SessionIdentity
import dev.ayuislands.studio.model.SessionInputs
import dev.ayuislands.studio.model.SessionObservation
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.StudioApplyUpdate
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.StudioSessionState
import dev.ayuislands.studio.persistence.CommitResolution
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioDraftStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

internal data class StudioSessionDispatchers(
    val native: CoroutineContext = Dispatchers.EDT,
    val io: CoroutineContext = Dispatchers.IO,
)

/** Owns retained bindings, their shared writer and the accepted aggregate after qualified bootstrap. */
internal class StudioSessions(
    initial: StudioDocument,
    private val store: StudioAppearanceStore,
    private val scope: CoroutineScope,
    private val transactionMutex: Mutex,
    private val context: (StudioSessionState, StudioDocument) -> AcceptanceContext,
    coordinator: (StudioSessionIo) -> StudioApplyCoordinator,
    private val ids: () -> String,
    private val dispatchers: StudioSessionDispatchers = StudioSessionDispatchers(),
) : StudioSessionIo {
    private val accepted = AtomicReference(initial)
    private val drafts = StudioDraftStore(store)
    private val receivers = linkedMapOf<StudioSessionBinding, suspend (StudioApplyUpdate) -> Unit>()
    val document: StudioDocument get() = accepted.get()
    val liveSessions: StudioList<StudioSessionRecord>
        get() = StudioList(receivers.keys.mapNotNull { it.controller.state.record })
    val coordinator: StudioApplyCoordinator = coordinator(this)
    private val scheduler =
        SessionScheduler { delayMillis, action ->
            val scheduled =
                scope.launch(dispatchers.native + captureStudioModality()) {
                    delay(delayMillis.milliseconds)
                    action()
                }
            ScheduledCall { scheduled.cancel() }
        }
    private val writer =
        SessionWriter { record, completion ->
            scope.launch(dispatchers.native + captureStudioModality()) {
                completion(saveRecord(record))
            }
        }

    /** Called on the controller lane; detaching a view leaves this binding and its operation registered. */
    fun bind(
        initial: StudioSessionState,
        loadRecord: suspend () -> StudioSessionRecord,
        resource: (FontJob) -> Unit,
    ): StudioSessionBinding {
        lateinit var receive: suspend (StudioApplyUpdate) -> Unit
        val binding =
            StudioSessionBinding(
                initial,
                ::inputs,
                scheduler,
                writer,
                coordinator = { callback ->
                    receive = callback
                    coordinator
                },
                loadRecord,
                resource,
                ids,
            )
        receivers[binding] = receive
        return binding
    }

    suspend fun saveRecord(record: StudioSessionRecord): DraftWriteResult = write { drafts.save(record, ids()) }

    /** Uses the latest persisted generation; unresolved journals remain with their existing owner. */
    suspend fun finishRecord(
        sessionId: String,
        availability: SessionAvailability,
    ): DraftWriteResult {
        require(availability != SessionAvailability.LIVE)
        return write { drafts.finish(sessionId, availability, ids()) }
    }

    /** Called on the controller lane; view detachment does not release a registered session identity. */
    fun recoveryOffers(): List<StudioSessionRecord> = StudioDraftRecovery.offers(document, registeredIds())

    suspend fun restoreRecord(request: DraftRestore): DraftWriteResult =
        write {
            val registered = withContext(dispatchers.native) { registeredIds() }
            drafts.restore(request, registered)
        }

    suspend fun discardRecord(sessionId: String): DraftWriteResult =
        write {
            val registered = withContext(dispatchers.native) { registeredIds() }
            drafts.discard(sessionId, ids(), registered)
        }

    private fun registeredIds(): Set<String> = receivers.keys.map { it.controller.state.identity.id }.toSet()

    override suspend fun publish(update: StudioApplyUpdate) {
        withContext(dispatchers.native) {
            if (update is StudioApplyUpdate.Committed) accepted.set(update.document)
            receivers.values.toList().forEach { receive -> receive(update) }
            if (update is StudioApplyUpdate.Committed) {
                val event = SessionEvent.Observation(SessionObservation.IntentRevision(update.document.header.revision))
                receivers.keys.toList().forEach { binding -> binding.controller.dispatch(event) }
            }
        }
    }

    override suspend fun flush(record: StudioSessionRecord): DraftWriteResult =
        withContext(dispatchers.native) {
            val binding = receivers.keys.singleOrNull { it.controller.state.identity.id == record.id }
            if (binding == null) {
                saveRecord(record)
            } else {
                suspendCancellableCoroutine { continuation ->
                    binding.controller.flush(record) { result ->
                        if (continuation.isActive) continuation.resume(result)
                    }
                }
            }
        }

    override suspend fun observe(
        request: AcceptanceRequest,
        document: StudioDocument,
    ): AcceptanceContext =
        withContext(dispatchers.native) {
            val identity = SessionIdentity(request.session.id, request.session.target, request.targetGeneration)
            observation(StudioSessionState(identity, request.session, SessionPhase.Editing), document)
        }

    private fun inputs(state: StudioSessionState): SessionInputs {
        val latest = document
        return SessionInputs(latest, observation(state, latest))
    }

    private fun observation(
        state: StudioSessionState,
        latest: StudioDocument,
    ): AcceptanceContext =
        context(state, latest).copy(
            liveSessions = liveSessions,
        )

    private suspend fun write(action: suspend () -> DraftWriteResult): DraftWriteResult =
        transactionMutex.withLock {
            val result = withContext(dispatchers.io) { writeResult(action) }
            if (result is DraftWriteResult.Saved) {
                val latest =
                    withContext(dispatchers.io) { acceptedDocument(result.revision) }
                        ?: return@withLock DraftWriteResult.Failed("save-unconfirmed")
                withContext(dispatchers.native) { accepted.set(latest) }
            }
            result
        }

    private suspend fun writeResult(action: suspend () -> DraftWriteResult): DraftWriteResult =
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            DraftWriteResult.Failed("draft-write-io")
        } catch (_: RuntimeException) {
            DraftWriteResult.Failed("draft-write-failed")
        }

    private fun acceptedDocument(revision: Long): StudioDocument? {
        val latest =
            when (val loaded = store.load()) {
                is StoreLoad.Ready -> loaded.document
                is StoreLoad.Recovering -> {
                    if (store.resolveOutcome(loaded.journal.token) != CommitResolution.Committed) return null
                    loaded.document
                }
                is StoreLoad.ReadOnly, StoreLoad.Empty -> null
            }
        return latest?.takeIf { it.header.revision == revision }
    }
}
