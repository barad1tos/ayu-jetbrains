package dev.ayuislands.integration

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.font.FontCatalog
import dev.ayuislands.font.FontInstallConsent
import dev.ayuislands.font.FontInstaller
import dev.ayuislands.font.FontPreset
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.ApplyFailure
import dev.ayuislands.studio.model.CloseRequest
import dev.ayuislands.studio.model.FontAction
import dev.ayuislands.studio.model.FontJob
import dev.ayuislands.studio.model.FontJobStatus
import dev.ayuislands.studio.model.OperationStamp
import dev.ayuislands.studio.model.SessionEvent
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.SessionNotice
import dev.ayuislands.studio.model.SessionPhase
import dev.ayuislands.studio.model.StudioMetadataCommand
import dev.ayuislands.studio.session.StudioFontResources
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class StudioFontResourcesTest {
    @get:Rule val applicationRule = ApplicationRule()

    @get:Rule val temporary = TemporaryFolder()

    private val scope = appScope()
    private val entry = FontCatalog.requirePreset(FontPreset.AMBIENT)

    @Before
    fun setUp() {
        mockkObject(FontInstallConsent)
        every { FontInstallConsent.confirmInstall(entry, null, true) } returns mockk()
        mockkObject(FontInstaller)
    }

    @After
    fun tearDown() {
        scope.cancel()
        unmockkAll()
    }

    @Test
    fun `onboarding resource callback waits for durable metadata without creating a Settings binding`() =
        onHostEdt {
            StudioHostFixture(temporary.newFolder().toPath()).use { fixture ->
                val job = FontJob(OperationStamp("onboarding", 0, "install"), entry.familyName, FontAction.INSTALL)
                val nativeStarted = CompletableDeferred<(FontInstaller.InstallResult) -> Unit>()
                val attempted = CompletableDeferred<Unit>()
                val statuses = mutableListOf<FontJobStatus>()
                val commands = mutableListOf<StudioMetadataCommand>()
                val paths = listOf(temporary.root.resolve("onboarding.ttf").absolutePath)
                var nativeCalls = 0
                every { FontInstallConsent.confirmInstall(entry, null, false) } returns mockk()
                every { FontInstaller.installResource(entry, any(), null, any()) } answers {
                    nativeCalls++
                    nativeStarted.complete(lastArg())
                }
                val executor =
                    StudioFontResources(
                        scope,
                        document = { fixture.document },
                        submitMetadata = { command ->
                            commands.add(command)
                            if (commands.size == 1) {
                                attempted.complete(Unit)
                                AcceptanceResult.NotAccepted(ApplyFailure("metadata-write"))
                            } else {
                                AcceptanceResult.NoChanges
                            }
                        },
                        entitlement = { LicenseEntitlement.LICENSED },
                    )
                val before = fixture.binding.controller.state

                resourceModal {
                    withContext(Dispatchers.EDT) { executor.start(null, job, false, statuses::add) }
                    val complete = nativeStarted.await()
                    withContext(
                        Dispatchers.EDT,
                    ) { complete(FontInstaller.InstallResult.Success(entry.familyName, true, paths)) }
                    attempted.await()
                    withContext(Dispatchers.EDT) { assertTrue(statuses.isEmpty()) }
                    assertEquals(listOf(AcceptanceResult.NoChanges), executor.retryPending())
                }

                assertEquals(listOf(FontJobStatus.INSTALLED), statuses)
                assertEquals(1, nativeCalls)
                assertEquals(commands[0], commands[1])
                assertEquals(
                    paths,
                    AyuIslandsState.decodeFontPaths(
                        assertIs<StudioMetadataCommand.FontInstalled>(commands[0]).encodedPaths,
                    ),
                )
                assertEquals(before, fixture.binding.controller.state)
                assertTrue(fixture.apply.effects.all { it.writes == 0 })
            }
        }

    @Test
    fun `resource metadata retries keep the exact completion and never repeat native installation`() =
        onHostEdt {
            val failures =
                listOf(
                    AcceptanceResult.NotAccepted(ApplyFailure("write-failed")),
                    AcceptanceResult.RecoveryRequired("pending", ApplyFailure("outcome-unknown")),
                    AcceptanceResult.AcceptedCleanupPending(1, "pending", ApplyFailure("cleanup")),
                )
            for (failure in failures) {
                StudioHostFixture(temporary.newFolder().toPath()).use { fixture ->
                    hostModal { fixture.binding.load() }
                    val job = startJob(fixture)
                    val submitted = CompletableDeferred<Unit>()
                    val commands = mutableListOf<StudioMetadataCommand>()
                    val paths = listOf(temporary.root.resolve("actual.ttf").absolutePath)
                    var nativeCalls = 0
                    lateinit var expectedModality: ModalityState
                    every { FontInstaller.installResource(entry, any(), null, any()) } answers {
                        nativeCalls++
                        val actualModality = ModalityState.current()
                        assertSame(
                            expectedModality,
                            actualModality,
                            "Installation must retain the originating modal",
                        )
                        lastArg<(FontInstaller.InstallResult) -> Unit>()(
                            FontInstaller.InstallResult.Success("Maple Mono NF", false, paths),
                        )
                    }
                    val executor =
                        StudioFontResources(
                            scope,
                            document = { fixture.document },
                            submitMetadata = { command ->
                                val actualModality = ModalityState.current()
                                assertSame(
                                    expectedModality,
                                    actualModality,
                                    "Completion must retain the originating modal",
                                )
                                commands.add(command)
                                if (commands.size == 1) {
                                    submitted.complete(Unit)
                                    failure
                                } else {
                                    AcceptanceResult.NoChanges
                                }
                            },
                            entitlement = { LicenseEntitlement.LICENSED },
                        )

                    val retry =
                        resourceModal {
                            withContext(Dispatchers.EDT) {
                                expectedModality = ModalityState.current()
                                assertTrue(expectedModality != ModalityState.nonModal())
                                executor.start(null, fixture.binding, job)
                            }
                            submitted.await()
                            withContext(Dispatchers.EDT) {
                                assertEquals(listOf(job), fixture.binding.controller.state.async.jobs)
                            }
                            executor.retryPending()
                        }
                    assertEquals(listOf(AcceptanceResult.NoChanges), retry)

                    val expected =
                        StudioMetadataCommand.FontInstalled(
                            job,
                            "Maple Mono NF",
                            AyuIslandsState.encodeFontPaths(paths.map(::File)),
                        )
                    assertEquals(listOf<StudioMetadataCommand>(expected, expected), commands)
                    assertEquals(1, nativeCalls)
                    assertTrue(
                        fixture.binding.controller.state.async.jobs
                            .isEmpty(),
                    )
                    assertEquals(
                        SessionNotice.Resource(job.fontId, FontJobStatus.RESTART_REQUIRED),
                        fixture.binding.controller.state.notice,
                    )
                    assertEquals(fixture.apply.initial.currents, fixture.document.currents)
                    assertTrue(fixture.apply.effects.all { it.writes == 0 })
                    assertTrue(hostModal { executor.retryPending() }.isEmpty())
                }
            }
        }

    @Test
    fun `completed installation is recorded after originating Settings has cancelled without reviving its draft`() =
        onHostEdt {
            StudioHostFixture(temporary.newFolder().toPath()).use { fixture ->
                hostModal { fixture.binding.load() }
                val job = startJob(fixture)
                val started = CompletableDeferred<(FontInstaller.InstallResult) -> Unit>()
                val recorded = CompletableDeferred<StudioMetadataCommand>()
                every { FontInstaller.installResource(entry, any(), null, any()) } answers {
                    started.complete(lastArg())
                }
                val executor =
                    StudioFontResources(
                        scope,
                        document = { fixture.document },
                        submitMetadata = { command ->
                            recorded.complete(command)
                            AcceptanceResult.NoChanges
                        },
                        entitlement = { LicenseEntitlement.LICENSED },
                    )
                val retired =
                    resourceModal {
                        withContext(Dispatchers.EDT) { executor.start(null, fixture.binding, job) }
                        val complete = started.await()
                        val cancelled =
                            withContext(Dispatchers.EDT) {
                                fixture.binding.controller.dispatch(SessionEvent.HostClose(CloseRequest.CANCEL))
                                fixture.binding.controller.state.record
                            }
                        withContext(Dispatchers.EDT) {
                            complete(FontInstaller.InstallResult.Success("Maple Mono", true, emptyList()))
                        }
                        assertIs<StudioMetadataCommand.FontInstalled>(recorded.await())
                        cancelled
                    }

                assertEquals(SessionPhase.Retired, fixture.binding.controller.state.phase)
                assertEquals(retired, fixture.binding.controller.state.record)
                assertEquals(fixture.apply.initial.currents, fixture.document.currents)
                assertTrue(fixture.apply.effects.all { it.writes == 0 })
            }
        }

    @Test
    fun `declined native consent completes the job without installing or recording a resource`() =
        onHostEdt {
            StudioHostFixture(temporary.newFolder().toPath()).use { fixture ->
                hostModal { fixture.binding.load() }
                val job = startJob(fixture)
                val declined = CompletableDeferred<Unit>()
                every { FontInstallConsent.confirmInstall(entry, null, true) } answers {
                    declined.complete(Unit)
                    null
                }
                every { FontInstaller.installResource(any(), any(), any(), any()) } throws
                    AssertionError("Declined installation")
                val executor =
                    StudioFontResources(
                        scope,
                        document = { fixture.document },
                        submitMetadata = { error("Declined resource must not write metadata") },
                        entitlement = { LicenseEntitlement.LICENSED },
                    )

                resourceModal {
                    withContext(Dispatchers.EDT) { executor.start(null, fixture.binding, job) }
                    declined.await()
                }

                assertEquals(
                    SessionNotice.Resource(job.fontId, FontJobStatus.DECLINED),
                    fixture.binding.controller.state.notice,
                )
                assertTrue(
                    fixture.binding.controller.state.async.jobs
                        .isEmpty(),
                )
                assertTrue(hostModal { executor.retryPending() }.isEmpty())
            }
        }

    private fun <T> resourceModal(action: suspend () -> T): T =
        hostModal {
            withTimeout(10.seconds) { action() }
        }

    private fun startJob(fixture: StudioHostFixture): FontJob {
        val record = requireNotNull(fixture.binding.controller.state.record)
        val job =
            FontJob(OperationStamp(record.id, record.generation, "font-job"), entry.familyName, FontAction.INSTALL)
        fixture.binding.controller.dispatch(SessionEvent.Intent(SessionIntent.Resource(job)))
        return job
    }

    private fun appScope(context: CoroutineContext = Dispatchers.Default): CoroutineScope =
        CoroutineScope(SupervisorJob() + context)
}
