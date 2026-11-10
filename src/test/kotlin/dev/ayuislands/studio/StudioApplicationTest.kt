package dev.ayuislands.studio

import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.StudioApplyUpdate
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioMetadataCommand
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.PendingJournal
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.RecoveryPurpose
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioMetadataStore
import dev.ayuislands.studio.session.StudioApplyCoordinator
import dev.ayuislands.studio.session.StudioApplyEnvironment
import dev.ayuislands.studio.session.StudioRuntimeLease
import dev.ayuislands.studio.session.StudioSessionDispatchers
import dev.ayuislands.studio.session.StudioSessions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioApplicationTest {
    private val dispatchers = StudioSessionDispatchers(native = EmptyCoroutineContext)

    @TempDir lateinit var directory: Path

    @Test
    fun `authority is published only after bootstrap and runtime startup and creates one session owner`() =
        runBlocking(dispatchers.io) {
            Fixture(directory, this).use { fixture ->
                val entered = CompletableDeferred<Unit>()
                val resume = CompletableDeferred<Unit>()
                fixture.runtime = {
                    assertNull(fixture.application.sessions)
                    entered.complete(Unit)
                    resume.await()
                    AcceptanceResult.NoChanges
                }
                val starting = async { fixture.application.start() }
                entered.await()
                assertIs<StudioApplicationState.Loading>(fixture.application.state)
                assertNull(fixture.application.sessions)
                resume.complete(Unit)
                val ready = assertIs<StudioApplicationState.Ready>(starting.await())

                assertSame(ready.sessions, fixture.application.sessions)
                assertEquals(fixture.native.initial, ready.sessions.document)
                assertSame(ready, fixture.application.start())
                assertEquals(1, fixture.creations)
                assertEquals(1, fixture.starts)
                assertTrue(fixture.native.events.isEmpty())
            }
        }

    @Test
    fun `unreadable authority creates no native or session owner and explicit retry reuses preserved state`() =
        runBlocking(dispatchers.io) {
            Fixture(directory, this).use { fixture ->
                val original = Files.readAllBytes(directory.resolve("state.xml"))
                Files.writeString(directory.resolve("state.xml"), "damaged")
                val failed = assertIs<StudioApplicationState.Unavailable>(fixture.application.start())
                assertIs<StudioBootstrapResult.ReadOnly>(failed.reason)
                assertNull(fixture.application.sessions)
                assertEquals(0, fixture.creations)
                assertEquals(0, fixture.starts)
                assertSame(failed, fixture.application.start())

                Files.write(directory.resolve("state.xml"), original)
                val ready = assertIs<StudioApplicationState.Ready>(fixture.application.retry())
                assertEquals(fixture.native.initial, ready.sessions.document)
                assertEquals(1, fixture.creations)
                assertEquals(1, fixture.starts)
            }
        }

    @Test
    fun `rejected project initialization keeps healthy authority unavailable until explicit retry`() =
        runBlocking(dispatchers.io) {
            Fixture(directory, this).use { fixture ->
                val original = Files.readAllBytes(directory.resolve("state.xml"))
                val project = StudioProjectKey("/work/startup")
                fixture.runtime = {
                    StudioMetadataStore(
                        fixture.native.store,
                        requireNotNull(fixture.created),
                        fixture.native.mutex,
                        EmptyCoroutineContext,
                    ).submit(
                        StudioMetadataCommand.InitializeProject(project, "Startup", StudioTarget.Global),
                        "startup-project",
                    )
                }
                fixture.native.files.arm(
                    IoEvent(IoOperation.STAGE, "pending.xml.tmp.startup-project", IoBoundary.BEFORE),
                )

                val blocked = fixture.application.start()

                assertFalse(blocked is StudioApplicationState.Ready)
                assertNull(fixture.application.sessions)
                assertIs<StoreLoad.Ready>(fixture.native.store.load())
                assertContentEquals(original, Files.readAllBytes(directory.resolve("state.xml")))
                assertFalse(Files.exists(directory.resolve("pending.xml")))
                assertEquals(0, fixture.native.files.pendingFailures)
                assertSame(blocked, fixture.application.start())
                assertEquals(1, fixture.starts)
                val retained = fixture.created

                val ready = assertIs<StudioApplicationState.Ready>(fixture.application.retry())

                assertSame(retained, ready.sessions)
                assertEquals(1, fixture.creations)
                assertEquals(2, fixture.starts)
                assertEquals(
                    project,
                    ready.sessions.document.currents.projects
                        .single()
                        .key,
                )
                assertEquals(fixture.native.initial.currents.global, ready.sessions.document.currents.global)
                assertEquals(fixture.native.initial.shared, ready.sessions.document.shared)
            }
        }

    @Test
    fun `pending user journal settles through the same coordinator before normal producers start`() =
        runBlocking(dispatchers.io) {
            Fixture(directory, this).use { fixture ->
                val candidate =
                    fixture.native.initial.copy(
                        header =
                            fixture.native.initial.header
                                .copy(revision = 1, transactionId = "interrupted"),
                    )
                assertIs<PrepareResult.Prepared>(
                    fixture.native.store.prepare(
                        0,
                        candidate,
                        RecoveryPlan(StudioTarget.Global, StudioList(emptyList())),
                    ),
                )
                fixture.runtime = {
                    assertIs<StoreLoad.Ready>(fixture.native.store.load())
                    AcceptanceResult.NoChanges
                }

                val ready = assertIs<StudioApplicationState.Ready>(fixture.application.start())

                assertEquals(fixture.native.initial, ready.sessions.document)
                assertEquals(1, fixture.creations)
                assertEquals(1, fixture.starts)
                assertFalse(Files.exists(directory.resolve("pending.xml")))
                assertTrue(fixture.native.effects.all { it.writes == 0 })
            }
        }

    @Test
    fun `cleanup retry keeps one retained coordinator and never exposes uncertain authority`() =
        runBlocking(dispatchers.io) {
            Fixture(directory, this).use { fixture ->
                val candidate =
                    fixture.native.initial.copy(
                        header =
                            fixture.native.initial.header
                                .copy(revision = 1, transactionId = "interrupted"),
                    )
                assertIs<PrepareResult.Prepared>(
                    fixture.native.store.prepare(
                        0,
                        candidate,
                        RecoveryPlan(StudioTarget.Global, StudioList(emptyList())),
                    ),
                )
                fixture.native.files.arm(IoEvent(IoOperation.REMOVE, "pending.xml", IoBoundary.BEFORE))

                assertIs<StudioApplicationState.Unavailable>(fixture.application.start())
                assertNull(fixture.application.sessions)
                assertEquals(1, fixture.creations)
                assertEquals(0, fixture.starts)
                val retained = fixture.created

                val ready = assertIs<StudioApplicationState.Ready>(fixture.application.retry())

                assertSame(retained, ready.sessions)
                assertEquals(1, fixture.creations)
                assertEquals(1, fixture.starts)
                assertEquals(fixture.native.initial, ready.sessions.document)
            }
        }

    @Test
    fun `a committed journal publishes its accepted generation before runtime startup`() =
        runBlocking(dispatchers.io) {
            Fixture(directory, this).use { fixture ->
                val candidate =
                    fixture.native.initial.copy(
                        header =
                            fixture.native.initial.header
                                .copy(revision = 1, transactionId = "accepted-before-crash"),
                    )
                val token =
                    assertIs<PrepareResult.Prepared>(
                        fixture.native.store.prepare(
                            0,
                            candidate,
                            RecoveryPlan(StudioTarget.Global, StudioList(emptyList())),
                        ),
                    ).token
                assertIs<CommitResult.Committed>(fixture.native.store.commit(token, candidate.runtime))
                fixture.runtime = {
                    assertEquals(candidate, requireNotNull(fixture.created).document)
                    assertEquals(StoreLoad.Ready(candidate), fixture.native.store.load())
                    AcceptanceResult.NoChanges
                }

                val ready = assertIs<StudioApplicationState.Ready>(fixture.application.start())

                assertEquals(candidate, ready.sessions.document)
                assertEquals(1, fixture.creations)
                assertEquals(1, fixture.starts)
                assertTrue(fixture.native.effects.all { it.writes == 0 })
            }
        }

    @Test
    fun `automatic startup recovery belongs to the runtime and is not started twice`() =
        runBlocking(dispatchers.io) {
            Fixture(directory, this).use { fixture ->
                val candidate =
                    fixture.native.initial.copy(
                        header =
                            fixture.native.initial.header
                                .copy(revision = 1, transactionId = "automatic"),
                    )
                assertIs<PrepareResult.Prepared>(
                    fixture.native.store.prepare(
                        0,
                        candidate,
                        RecoveryPlan(
                            StudioTarget.Global,
                            StudioList(emptyList()),
                            purpose = RecoveryPurpose.RotationSchedule(UUID.randomUUID()),
                        ),
                    ),
                )
                fixture.runtime = {
                    assertIs<StoreLoad.Recovering>(fixture.native.store.load())
                    requireNotNull(fixture.created).coordinator.retryRecovery("automatic")
                    AcceptanceResult.NoChanges
                }

                assertIs<StudioApplicationState.Ready>(fixture.application.start())

                assertEquals(1, fixture.starts)
                assertEquals(1, fixture.creations)
            }
        }

    @Test
    fun `metadata restart settles its durable decision without native recovery authority`() =
        runBlocking(dispatchers.io) {
            Fixture(directory, this).use { fixture ->
                val candidate =
                    fixture.native.initial.copy(
                        header =
                            fixture.native.initial.header
                                .copy(revision = 1, transactionId = "metadata-restart"),
                    )
                val prepared =
                    assertIs<PrepareResult.Prepared>(
                        fixture.native.store.prepare(
                            0,
                            candidate,
                            RecoveryPlan(
                                StudioTarget.Global,
                                StudioList(emptyList()),
                                purpose = RecoveryPurpose.Metadata,
                            ),
                        ),
                    )
                assertIs<CommitResult.Committed>(fixture.native.store.commit(prepared.token, candidate.runtime))
                fixture.rejectNativeRecovery = true
                fixture.runtime = {
                    assertIs<StoreLoad.Ready>(fixture.native.store.load())
                    AcceptanceResult.NoChanges
                }

                val ready = assertIs<StudioApplicationState.Ready>(fixture.application.start())

                assertEquals(candidate, ready.sessions.document)
                assertEquals(0, fixture.nativeRecoveries)
                assertTrue(fixture.native.events.none { it.startsWith("restore-") })
                assertEquals(1, fixture.starts)
                assertFalse(Files.exists(directory.resolve("pending.xml")))
                assertTrue(fixture.native.effects.all { it.writes == 0 && it.accepts == 0 })
            }
        }

    @Test
    fun `close gates callers immediately and releases the store only after the current writer finishes`() =
        runBlocking(dispatchers.io) {
            Fixture(directory, this).use { fixture ->
                assertIs<StudioApplicationState.Ready>(fixture.application.start())
                fixture.native.mutex.lock()
                val closing = async { fixture.application.close() }
                while (fixture.application.state != StudioApplicationState.Closed) kotlinx.coroutines.yield()
                assertNull(fixture.application.sessions)
                assertFalse(closing.isCompleted)
                assertIs<StoreLoad.Ready>(fixture.native.store.load())
                fixture.native.mutex.unlock()
                closing.await()

                assertIs<StoreLoad.ReadOnly>(fixture.native.store.load())
                assertEquals(StudioApplicationState.Closed, fixture.application.retry())
                assertEquals(StudioApplicationState.Closed, fixture.application.start())
                assertEquals(1, fixture.creations)
            }
        }

    private class Fixture(
        directory: Path,
        scope: CoroutineScope,
    ) : AutoCloseable {
        val native = StudioApplyFixture(directory)
        var creations = 0
        var starts = 0
        var nativeRecoveries = 0
        var rejectNativeRecovery = false
        var created: StudioSessions? = null
        var runtime: suspend () -> AcceptanceResult = { AcceptanceResult.NoChanges }
        private val bootstrap =
            StudioBootstrap(
                native.store,
                { error("Existing authority must not recapture legacy state") },
                native.mutex,
                EmptyCoroutineContext,
            )
        val application =
            StudioApplication(
                native.store,
                bootstrap,
                native.mutex,
                createSessions = { initial ->
                    creations++
                    StudioSessions(
                        initial,
                        native.store,
                        scope,
                        native.mutex,
                        { state, document -> sessionInputs(document, state).context },
                        coordinator = { sessions ->
                            val environment =
                                object : StudioApplyEnvironment by native {
                                    override suspend fun publish(update: StudioApplyUpdate) = sessions.publish(update)

                                    override suspend fun resume(journal: PendingJournal): StudioRuntimeLease {
                                        nativeRecoveries++
                                        check(!rejectNativeRecovery) { "Native entitlement is unconfirmed" }
                                        return native.resume(journal)
                                    }
                                }
                            StudioApplyCoordinator(
                                native.store,
                                environment,
                                native.effects,
                                native.mutex,
                                EmptyCoroutineContext,
                            )
                        },
                        ids = { "record" },
                        dispatchers = StudioSessionDispatchers(EmptyCoroutineContext, EmptyCoroutineContext),
                    ).also { created = it }
                },
                startRuntime = {
                    starts++
                    runtime()
                },
                recoverMetadata = { owner ->
                    StudioMetadataStore(native.store, owner, native.mutex, EmptyCoroutineContext).recoverPending()
                },
                dispatchers = StudioSessionDispatchers(EmptyCoroutineContext, EmptyCoroutineContext),
            )

        override fun close() = native.close()
    }
}
