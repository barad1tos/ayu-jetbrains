package dev.ayuislands.integration

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.rotation.RotationSnapshot
import dev.ayuislands.studio.ApplyTestEffect
import dev.ayuislands.studio.EmptyEffectRuntime
import dev.ayuislands.studio.FaultingStudioFiles
import dev.ayuislands.studio.IoBoundary
import dev.ayuislands.studio.IoEvent
import dev.ayuislands.studio.IoOperation
import dev.ayuislands.studio.StudioBootstrap
import dev.ayuislands.studio.StudioBootstrapResult
import dev.ayuislands.studio.StudioFixtures
import dev.ayuislands.studio.model.AcceptanceContext
import dev.ayuislands.studio.model.AcceptanceOrigin
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.AcceptanceReview
import dev.ayuislands.studio.model.CatalogCommand
import dev.ayuislands.studio.model.CatalogIntent
import dev.ayuislands.studio.model.CloseRequest
import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionAvailability
import dev.ayuislands.studio.model.SessionEvent
import dev.ayuislands.studio.model.SessionIdentity
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SessionJournalStatus
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.StudioApplyUpdate
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.StudioSessionState
import dev.ayuislands.studio.model.ViewAttachment
import dev.ayuislands.studio.persistence.PendingJournal
import dev.ayuislands.studio.persistence.StagedFile
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioFiles
import dev.ayuislands.studio.persistence.openStudioFiles
import dev.ayuislands.studio.session.QuickCommandPreparation
import dev.ayuislands.studio.session.StudioAcceptancePreparation
import dev.ayuislands.studio.session.StudioApplyCoordinator
import dev.ayuislands.studio.session.StudioApplyEnvironment
import dev.ayuislands.studio.session.StudioEffectRuntime
import dev.ayuislands.studio.session.StudioQuickCommand
import dev.ayuislands.studio.session.StudioQuickCommands
import dev.ayuislands.studio.session.StudioRuntimeLease
import dev.ayuislands.studio.session.StudioSessionBinding
import dev.ayuislands.studio.session.StudioSessionDispatchers
import dev.ayuislands.studio.session.StudioSessionIo
import dev.ayuislands.studio.session.StudioSessions
import dev.ayuislands.studio.sessionInputs
import dev.ayuislands.studio.sessionRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class StudioSessionsTest {
    @get:Rule val applicationRule = ApplicationRule()

    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `two bindings share catalog acceptance and a detached peer retains its dirty work`() =
        withSessions {
            val first = bind("first")
            val second = bind("second")
            val dirty =
                withContext(Dispatchers.EDT) {
                    editGlow(second)
                    second.controller.detach()
                    requireNotNull(second.controller.state.record)
                }
            var acceptedCallbacks = 0
            withContext(Dispatchers.EDT) {
                first.controller.attach { state ->
                    if (state.record?.generation == 2L) {
                        assertEquals("Shared name", userProfile(sessions.document).name)
                        acceptedCallbacks++
                    }
                }
                rename(first, "Shared name")
                first.controller.dispatch(SessionEvent.AcceptRequested("rename"))
            }

            assertIs<AcceptanceResult.Accepted>(withTimeout(5.seconds) { first.execute() })

            withContext(Dispatchers.EDT) {
                assertSame(sessions.document, first.document)
                assertSame(sessions.document, second.document)
                assertEquals("Shared name", userProfile(second.document).name)
                assertEquals(ViewAttachment.DETACHED, second.controller.state.view.attachment)
                val retained = requireNotNull(second.controller.state.record)
                assertEquals(
                    dirty.work.drafts
                        .single()
                        .appearance,
                    retained.work.drafts
                        .single()
                        .appearance,
                )
                assertEquals(
                    dirty.work.drafts
                        .single()
                        .tracking,
                    retained.work.drafts
                        .single()
                        .tracking,
                )
            }
            assertTrue(acceptedCallbacks > 0)
            assertEquals(1, coordinatorCreations)
            assertTrue(effects.all { it.writes == 0 && it.inputs.isEmpty() })
            assertEquals(initial.currents, sessions.document.currents)
            assertEquals(initial.runtime, sessions.document.runtime)
            assertEquals(sessions.document, stored())
        }

    @Test
    fun `draft completion sees acknowledged aggregate on EDT after disk IO off EDT`() =
        withSessions {
            val binding = bind("settings")
            val completed = CompletableDeferred<DraftWriteResult>()
            val requested =
                withContext(Dispatchers.EDT) {
                    editGlow(binding)
                    val record = requireNotNull(binding.controller.state.record)
                    binding.controller.flush(record) { outcome ->
                        assertTrue(SwingUtilities.isEventDispatchThread())
                        assertEquals(record, sessions.document.sessions.single())
                        completed.complete(outcome)
                    }
                    record
                }

            assertIs<DraftWriteResult.Saved>(withTimeout(5.seconds) { completed.await() })

            assertEquals(requested, stored().sessions.single())
            assertEquals(initial.currents, sessions.document.currents)
            assertTrue(fileThreads.isNotEmpty())
            assertTrue(fileThreads.none { it })
        }

    @Test
    fun `explicit draft flush completes inside its originating modal dialog`() =
        withSessions {
            val binding = bind("modal-flush")
            val requested =
                withContext(Dispatchers.EDT) {
                    hostModal {
                        withTimeout(5.seconds) {
                            val completed = CompletableDeferred<Pair<DraftWriteResult, ModalityState>>()
                            lateinit var expectedModality: ModalityState
                            val record =
                                withContext(Dispatchers.EDT) {
                                    expectedModality = ModalityState.current()
                                    assertTrue(expectedModality !== ModalityState.nonModal())
                                    editGlow(binding)
                                    requireNotNull(binding.controller.state.record).also { changed ->
                                        binding.controller.flush(changed) { outcome ->
                                            completed.complete(outcome to ModalityState.current())
                                        }
                                    }
                                }
                            val (outcome, callbackModality) = completed.await()
                            assertIs<DraftWriteResult.Saved>(outcome)
                            assertSame(expectedModality, callbackModality)
                            assertEquals(record, sessions.document.sessions.single())
                            record
                        }
                    }
                }

            assertEquals(requested, stored().sessions.single())
            assertEquals(initial.currents, sessions.document.currents)
            assertTrue(fileThreads.none { it })
        }

    @Test
    fun `debounced drafts retain each bindings originating modal dialog`() =
        withSessions {
            val bindings = listOf(bind("first-modal"), bind("second-modal"))
            val modalities = mutableListOf<ModalityState>()
            val requested =
                bindings.map { binding ->
                    withContext(Dispatchers.EDT) {
                        hostModal {
                            withTimeout(5.seconds) {
                                val completed = CompletableDeferred<Pair<StudioSessionRecord, ModalityState>>()
                                val record =
                                    withContext(Dispatchers.EDT) {
                                        modalities.add(ModalityState.current())
                                        binding.controller.attach { observed ->
                                            if (observed.journal is SessionJournalStatus.Saved) {
                                                completed.complete(
                                                    requireNotNull(observed.record) to ModalityState.current(),
                                                )
                                            }
                                        }
                                        editGlow(binding)
                                        requireNotNull(binding.controller.state.record)
                                    }
                                val (saved, callbackModality) = completed.await()
                                assertEquals(record, saved)
                                assertSame(modalities.last(), callbackModality)
                                assertEquals(record, sessions.document.sessions.single { it.id == record.id })
                                record
                            }
                        }
                    }
                }

            assertTrue(modalities[0] !== modalities[1])
            assertEquals(requested, stored().sessions)
            assertEquals(initial.currents, sessions.document.currents)
            assertTrue(fileThreads.none { it })
        }

    @Test
    fun `unflushed detached binding is included in immediate Quick admission`() =
        withSessions {
            val binding = bind("settings")
            withContext(Dispatchers.EDT) {
                editGlow(binding)
                binding.controller.detach()
                val popup = state(record("popup"))
                val context = sessions.observe(request(requireNotNull(popup.record)), sessions.document)
                assertEquals(emptyList(), sessions.document.sessions)

                val result =
                    StudioQuickCommands.prepare(
                        StudioQuickCommand.GlowEnabled(true),
                        popup,
                        sessionInputs(sessions.document, popup).copy(context = context),
                        "quick",
                    )

                assertEquals(
                    listOf<AcceptanceReview>(AcceptanceReview.LiveSession("settings")),
                    assertIs<QuickCommandPreparation.ReviewRequired>(result).items,
                )
            }
        }

    @Test
    fun `binding registration follows reopen identity instead of retaining the retired record key`() =
        withSessions {
            val binding = bind("original")
            val retired =
                withContext(Dispatchers.EDT) {
                    editGlow(binding)
                    binding.controller.dispatch(SessionEvent.HostClose(CloseRequest.CANCEL))
                    requireNotNull(binding.controller.state.record)
                }
            assertIs<DraftWriteResult.Saved>(sessions.saveRecord(retired))
            binding.load()

            val reopened =
                withContext(Dispatchers.EDT) {
                    assertNotEquals("original", binding.controller.state.identity.id)
                    editGlow(binding)
                    val current = requireNotNull(binding.controller.state.record)
                    val context = sessions.observe(request(record("popup")), sessions.document)
                    assertEquals(listOf(current), context.liveSessions)
                    current
                }
            assertIs<DraftWriteResult.Saved>(sessions.flush(reopened))

            assertEquals(listOf(retired, reopened), stored().sessions)
            assertEquals(1, coordinatorCreations)
        }

    @Test
    fun `loading uses explicitly selected record instead of the first persisted session`() =
        withSessions {
            val first = record("first")
            val chosen = record("chosen")
            assertIs<DraftWriteResult.Saved>(sessions.saveRecord(first))
            assertIs<DraftWriteResult.Saved>(sessions.saveRecord(chosen))
            val loading = StudioSessionState(SessionIdentity(chosen.id, chosen.target, 1), null, SessionPhase.Loading)
            val binding =
                withContext(Dispatchers.EDT) {
                    sessions.bind(loading, loadRecord = { chosen }, resource = { error("No font action requested") })
                }

            binding.load()

            withContext(Dispatchers.EDT) {
                assertEquals(
                    chosen.id,
                    binding.controller.state.record
                        ?.id,
                )
                assertEquals(SessionPhase.Editing, binding.controller.state.phase)
            }
            assertEquals(listOf(first, chosen), stored().sessions)
        }

    @Test
    fun `failed draft save never publishes its unaccepted candidate`() =
        withSessions {
            files.arm(IoEvent(IoOperation.REPLACE, "state.xml", IoBoundary.BEFORE))

            assertIs<DraftWriteResult.Failed>(sessions.saveRecord(record("quick")))

            assertSame(initial, sessions.document)
            assertEquals(initial, stored())
        }

    @Test
    fun `unknown save retains one writer and publishes only after its own readback retry`() =
        withSessions {
            val record = record("quick")
            files.arm(
                IoEvent(IoOperation.REPLACE, "state.xml", IoBoundary.AFTER),
                IoEvent(IoOperation.READ, "state.xml", IoBoundary.BEFORE),
            )
            assertEquals(DraftWriteResult.Failed("save-unconfirmed"), sessions.saveRecord(record))
            assertSame(initial, sessions.document)

            assertIs<DraftWriteResult.Saved>(sessions.saveRecord(record))

            assertEquals(listOf(record), sessions.document.sessions)
            assertEquals(sessions.document, stored())
        }

    @Test
    fun `concurrent session saves preserve both records through the same transaction queue`() =
        withSessions {
            val records = listOf(record("first"), record("second"))
            val results = coroutineScope { records.map { async { sessions.saveRecord(it) } }.awaitAll() }

            assertTrue(results.all { it is DraftWriteResult.Saved })
            assertEquals(records.toSet(), stored().sessions.toSet())
            assertEquals(sessions.document, stored())
            assertEquals(initial.catalog, sessions.document.catalog)
            assertEquals(initial.currents, sessions.document.currents)
        }

    @Test
    fun `Quick disposition publishes the accepted record generation before returning`() =
        withSessions {
            val source = record("quick")
            val edit =
                source.copy(
                    generation = 1,
                    work =
                        source.work.copy(
                            catalog = StudioList(listOf(CatalogIntent.Rename(userProfile(initial), "Accepted name"))),
                        ),
                )
            assertIs<AcceptanceResult.Accepted>(sessions.coordinator.submit(request(edit)))
            val accepted = sessions.document.sessions.single()
            assertEquals(2L, accepted.generation)

            assertIs<DraftWriteResult.Saved>(sessions.finishRecord("quick", SessionAvailability.RETIRED))

            assertEquals(
                accepted.copy(generation = 3, availability = SessionAvailability.RETIRED),
                sessions.document.sessions.single(),
            )
            assertEquals("Accepted name", userProfile(sessions.document).name)
            assertEquals(sessions.document, stored())
            assertTrue(effects.all { it.writes == 0 })
        }

    private fun withSessions(action: suspend SessionsFixture.() -> Unit): Unit =
        runBlocking {
            val fixture = SessionsFixture.create(temporary.newFolder().toPath())
            try {
                withTimeout(15.seconds) { fixture.action() }
            } finally {
                fixture.close()
            }
        }
}

private class SessionsFixture(
    val store: StudioAppearanceStore,
    val files: FaultingStudioFiles,
    val initial: StudioDocument,
    private val mutex: Mutex,
    val fileThreads: CopyOnWriteArrayList<Boolean>,
    private val ioContext: CoroutineContext,
    backgroundContext: CoroutineContext = Dispatchers.Default,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + backgroundContext)
    private val serial = AtomicInteger()
    val effects =
        EffectOwner.entries
            .filterNot {
                it == EffectOwner.PLUGINS
            }.map { ApplyTestEffect(it, store, mutableListOf()) }
    var coordinatorCreations = 0
        private set
    val sessions =
        StudioSessions(
            initial = initial,
            store = store,
            scope = scope,
            transactionMutex = mutex,
            context = ::context,
            coordinator = ::coordinator,
            ids = { "session-write-${serial.incrementAndGet()}" },
            dispatchers = StudioSessionDispatchers(io = ioContext),
        )

    suspend fun bind(id: String): StudioSessionBinding =
        withContext(Dispatchers.EDT) {
            val record = record(id)
            sessions.bind(state(record), loadRecord = { record }, resource = { error("No font action requested") })
        }

    fun record(id: String): StudioSessionRecord = sessionRecord(sessions.document).copy(id = id)

    fun state(record: StudioSessionRecord): StudioSessionState =
        StudioSessionState(SessionIdentity(record.id, record.target, 1), record, SessionPhase.Editing)

    fun request(record: StudioSessionRecord): AcceptanceRequest =
        AcceptanceRequest(record, "quick-accept", 1, AcceptanceOrigin.QUICK_SWITCHER)

    fun userProfile(document: StudioDocument): ProfileDefinition.User =
        document.catalog.filterIsInstance<ProfileDefinition.User>().single()

    fun rename(
        binding: StudioSessionBinding,
        name: String,
    ) {
        binding.controller.dispatch(
            SessionEvent.Intent(SessionIntent.Catalog(CatalogCommand.Rename(userProfile(sessions.document).id, name))),
        )
    }

    fun editGlow(binding: StudioSessionBinding) {
        val draft = requireNotNull(binding.controller.state.record).work.drafts.single()
        val address = FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "glowEnabled"))
        val current =
            draft.appearance.glow.records
                .single { it.key == address.field }
                .value as RawValue.BooleanValue
        binding.controller.dispatch(
            SessionEvent.Intent(
                SessionIntent.EditField(draft.id, address, FieldValue.Present(RawValue.BooleanValue(!current.value))),
            ),
        )
    }

    suspend fun stored(): StudioDocument =
        mutex.withLock {
            withContext(ioContext) { assertIs<StoreLoad.Ready>(store.load()).document }
        }

    suspend fun close() {
        job.cancelAndJoin()
        withContext(ioContext) { store.close() }
    }

    private fun context(
        state: StudioSessionState,
        document: StudioDocument,
    ): AcceptanceContext {
        val observed =
            state.copy(
                record =
                    state.record ?: sessionRecord(
                        document,
                    ).copy(id = state.identity.id, target = state.identity.target),
            )
        return sessionInputs(document, observed).context
    }

    private fun coordinator(session: StudioSessionIo): StudioApplyCoordinator {
        coordinatorCreations++
        val environment =
            object : StudioApplyEnvironment {
                override suspend fun publish(update: StudioApplyUpdate) = session.publish(update)

                override suspend fun flush(record: StudioSessionRecord): DraftWriteResult = session.flush(record)

                override suspend fun observe(
                    request: AcceptanceRequest,
                    document: StudioDocument,
                ): AcceptanceContext = session.observe(request, document)

                override suspend fun prepareAcceptance(
                    request: AcceptanceRequest,
                    plan: AcceptancePlan.Commit,
                ): StudioAcceptancePreparation = StudioAcceptancePreparation.Prepared(lease(), plan)

                override suspend fun acquireRuntime(
                    operationId: UUID,
                    snapshot: RotationSnapshot,
                    plan: AcceptancePlan.Commit,
                ): StudioAcceptancePreparation = error("No runtime command requested")

                override suspend fun resume(journal: PendingJournal): StudioRuntimeLease =
                    error("No native recovery requested")
            }
        return StudioApplyCoordinator(store, environment, effects, mutex)
    }

    private fun lease(): StudioRuntimeLease =
        object : StudioRuntimeLease, StudioEffectRuntime by EmptyEffectRuntime {
            override suspend fun isValid(): Boolean = true

            override suspend fun settledRuntime(candidate: StudioDocument) = candidate.runtime

            override suspend fun release(accepted: StudioDocument?) = Unit
        }

    companion object {
        suspend fun create(
            directory: Path,
            ioContext: CoroutineContext = Dispatchers.IO,
        ): SessionsFixture {
            val threads = CopyOnWriteArrayList<Boolean>()
            val files =
                withContext(
                    ioContext,
                ) { FaultingStudioFiles(SessionThreadFiles(openStudioFiles(directory), threads)) }
            val store = StudioAppearanceStore(files)
            val mutex = Mutex()
            val initial =
                assertIs<StudioBootstrapResult.Ready>(
                    StudioBootstrap(store, {
                        StudioFixtures.capture()
                    }, mutex).load(),
                ).document
            return SessionsFixture(store, files, initial, mutex, threads, ioContext)
        }
    }
}

private class SessionThreadFiles(
    private val actual: StudioFiles,
    private val threads: MutableList<Boolean>,
) : StudioFiles by actual {
    override fun read(name: String): ByteArray? {
        threads.add(SwingUtilities.isEventDispatchThread())
        return actual.read(name)
    }

    override fun stage(name: String): StagedFile {
        threads.add(SwingUtilities.isEventDispatchThread())
        return actual.stage(name)
    }

    override fun replace(
        source: String,
        destination: String,
    ) {
        threads.add(SwingUtilities.isEventDispatchThread())
        actual.replace(source, destination)
    }
}
