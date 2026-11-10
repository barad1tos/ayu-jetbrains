package dev.ayuislands.integration

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.AyuLaf
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ResolvedAccent
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.rotation.AccentRotationMode
import dev.ayuislands.rotation.ClockSample
import dev.ayuislands.rotation.RotationCandidate
import dev.ayuislands.rotation.RotationIntent
import dev.ayuislands.rotation.RotationOutput
import dev.ayuislands.rotation.RotationRuntimeCheckpoint
import dev.ayuislands.rotation.RotationRuntimeState
import dev.ayuislands.rotation.RotationSchedule
import dev.ayuislands.rotation.RotationSnapshot
import dev.ayuislands.rotation.RotationTicket
import dev.ayuislands.rotation.RuntimeAvailability
import dev.ayuislands.rotation.RuntimeInputs
import dev.ayuislands.rotation.RuntimeOwner
import dev.ayuislands.studio.StudioApplyFixture
import dev.ayuislands.studio.model.AcceptanceContext
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioAccentContinuation
import dev.ayuislands.studio.model.StudioApplyUpdate
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioEffectiveContinuation
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioSessionRecord
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.runtime.FocusSnapshot
import dev.ayuislands.studio.runtime.RuntimeDispatch
import dev.ayuislands.studio.runtime.StudioRuntimeCoordinator
import dev.ayuislands.studio.runtime.StudioRuntimeSource
import dev.ayuislands.studio.session.StudioApplyCoordinator
import dev.ayuislands.studio.session.StudioNativeEnvironment
import dev.ayuislands.studio.session.StudioNativeFocus
import dev.ayuislands.studio.session.StudioNativeHost
import dev.ayuislands.studio.session.StudioSessionIo
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StudioAccentRuntimeTest {
    @get:Rule val applicationRule = ApplicationRule()

    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `real Accent participant consumes frozen candidate and accepted generated output without raw edits`() =
        onEdt {
            AccentParticipantFixture().use { native ->
                val original = native.input
                val current = original.candidate.currents.global
                val owner = RuntimeOwner(StudioTarget.Global, current.revision)
                val candidate =
                    RotationCandidate(
                        UUID.fromString("00000000-0000-0000-0000-000000000076"),
                        owner,
                        RotationIntent(true, "RANDOM", 1),
                        AccentRotationMode.RANDOM,
                        AyuVariant.MIRAGE,
                        "#A1B2C3",
                        9,
                    )
                val input = original.copy(rotationStep = candidate)
                val operation = native.participant.prepare(input, native.runtime)
                try {
                    operation.apply()
                    assertGeneratedAccent(native)
                    assertEquals(original.candidate, input.candidate)
                } finally {
                    settleParticipant(operation)
                }
                native.assertOriginal()
                val checkpoint =
                    assertNotNull(original.candidate.runtime.rotation).copy(
                        owner = owner,
                        output = RotationOutput(owner, AyuVariant.MIRAGE, "#A1B2C3"),
                    )
                val accepted =
                    original.copy(
                        candidate =
                            original.candidate.copy(
                                runtime = original.candidate.runtime.copy(rotation = checkpoint),
                            ),
                    )
                val reapplied = native.participant.prepare(accepted, native.runtime)
                try {
                    reapplied.apply()
                    assertGeneratedAccent(native)
                    assertEquals(current.appearance, accepted.candidate.currents.global.appearance)
                } finally {
                    settleParticipant(reapplied)
                }
            }
        }

    @Test
    fun `real native runtime tick releases held accent only after native refresh and durable commit`() =
        onEdt {
            AccentRuntimeHarness(temporary.newFolder().toPath()).use { fixture ->
                fixture.native.beforeRefresh = {
                    assertGeneratedAccent(fixture.native)
                    assertEquals(
                        fixture.initial.runtime.effectiveContinuation,
                        fixture.store
                            .document()
                            .runtime.effectiveContinuation,
                    )
                    assertEquals(
                        7L,
                        fixture.store
                            .document()
                            .runtime.rotation
                            ?.successfulStepSerial,
                    )
                }
                val result = fixture.tick()
                assertIs<RotationRuntimeState.Waiting>(result.state)
                val accepted = fixture.store.document()
                assertNull(accepted.runtime.effectiveContinuation?.accent)
                assertEquals(
                    "#A1B2C3",
                    accepted.runtime.rotation
                        ?.output
                        ?.hex,
                )
                assertEquals(8L, accepted.runtime.rotation?.successfulStepSerial)
                assertEquals(1, fixture.draws)
                assertEquals(fixture.initial.currents, accepted.currents)
                assertEquals(fixture.initial.shared, accepted.shared)
                assertEquals(fixture.initial.catalog, accepted.catalog)
                assertGeneratedAccent(fixture.native)
            }
        }

    @Test
    fun `failed native runtime tick restores real surfaces and retains held accent and accepted history`() =
        onEdt {
            AccentRuntimeHarness(temporary.newFolder().toPath()).use { fixture ->
                fixture.native.refreshFailure = IllegalStateException("late native refresh")
                val before = ParticipantSnapshot(fixture.native.scheme)
                fixture.tick()
                assertEquals(
                    fixture.initial.runtime.effectiveContinuation,
                    fixture.store
                        .document()
                        .runtime.effectiveContinuation,
                )
                assertEquals(
                    fixture.initial.runtime.rotation
                        ?.output,
                    fixture.store
                        .document()
                        .runtime.rotation
                        ?.output,
                )
                assertEquals(
                    7L,
                    fixture.store
                        .document()
                        .runtime.rotation
                        ?.successfulStepSerial,
                )
                assertEquals(fixture.initial.currents, fixture.store.document().currents)
                assertEquals(1, fixture.draws)
                fixture.native.assertOriginal()
                before.assertValues()
            }
        }

    private fun onEdt(action: suspend () -> Unit) {
        SwingUtilities.invokeAndWait { runBlocking { action() } }
    }
}

/** Real participant and store joined through the native lease; only theme, clock, and random are inputs. */
private class AccentRuntimeHarness(
    directory: Path,
) : AutoCloseable {
    val native = AccentParticipantFixture()
    val store = StudioApplyFixture(directory)
    private val colors = EditorColorsManager.getInstance()
    private val originalScheme = colors.globalScheme
    private val focus = FocusSnapshot(StudioTarget.Global, 1)
    private var sequence = 1L
    var draws = 0
        private set
    val initial = seed()
    private val checkpoint = assertNotNull(initial.runtime.rotation)
    private val ticket = RotationTicket(checkpoint.owner, 5, 0, 0, 0)
    private val runtime =
        StudioRuntimeCoordinator(
            RotationRuntimeState.Waiting(
                RotationSnapshot(
                    checkpoint,
                    RuntimeInputs(
                        checkpoint.owner,
                        initial.currents.global.appearance,
                        RuntimeAvailability(
                            lastDefinitivePro = true,
                            effectiveFollowSystem = false,
                            variant = AyuVariant.MIRAGE,
                            targetValid = true,
                            recoveryPending = false,
                        ),
                    ),
                ),
                ticket,
            ),
            focus,
        )
    private val source =
        object : StudioRuntimeSource {
            override fun clock() = ClockSample(HOUR, TimeUnit.MILLISECONDS.toNanos(HOUR))

            override fun operationId() = UUID(0, sequence++)

            override fun transactionId() = "native-rotation-${sequence++}"

            override fun randomColor(variant: AyuVariant): String {
                draws++
                return "#A1B2C3"
            }
        }
    private val session =
        object : StudioSessionIo {
            override suspend fun publish(update: StudioApplyUpdate) = store.publish(update)

            override suspend fun flush(record: StudioSessionRecord): DraftWriteResult = store.flush(record)

            override suspend fun observe(
                request: AcceptanceRequest,
                document: StudioDocument,
            ): AcceptanceContext = store.observe(request, document)
        }
    private val host =
        StudioNativeHost(
            focus = { StudioNativeFocus(focus, null) },
            entitlement = { LicenseEntitlement.LICENSED },
            accent = { null },
            managers = { emptyList() },
            process = StudioProcess(76, Instant.EPOCH),
        )
    private val environment = StudioNativeEnvironment(runtime, source, session, host, {}, EmptyCoroutineContext)
    private val coordinator =
        StudioApplyCoordinator(store.store, environment, listOf(native.participant), store.mutex, EmptyCoroutineContext)

    init {
        mockkObject(AyuLaf)
        every { AyuLaf.currentThemeName(any()) } returns "Ayu Mirage"
        colors.setGlobalScheme(native.scheme)
    }

    suspend fun tick() = coordinator.executeRuntime(runtime, RuntimeDispatch.Due(ticket), source)

    private fun seed(): StudioDocument {
        val original = store.initial
        val requested = native.input.candidate.currents.global.appearance
        val values =
            mapOf(
                "accentRotationEnabled" to RawValue.BooleanValue(true),
                "accentRotationMode" to RawValue.Text("RANDOM"),
                "accentRotationIntervalHours" to RawValue.Integer(1),
            )
        val appearance =
            requested.copy(
                accent =
                    requested.accent.copy(
                        records =
                            StudioList(
                                requested.accent.records.map {
                                    values[it.key.name]?.let { value -> it.copy(value = value) } ?: it
                                },
                            ),
                    ),
            )
        val current = original.currents.global.copy(appearance = appearance, revision = 1)
        val held =
            StudioEffectiveContinuation(
                RuntimeOwner(StudioTarget.Project(StudioProjectKey("closed-project")), 17),
                appearance,
                StudioAccentContinuation(
                    AyuVariant.MIRAGE,
                    ResolvedAccent(AccentResolver.Source.PROJECT_OVERRIDE, "#FF0000"),
                    StudioList(emptyList()),
                ),
            )
        val candidate =
            original.copy(
                header = original.header.copy(revision = 1, transactionId = "seed-native-rotation"),
                currents = original.currents.copy(global = current),
                shared = native.input.candidate.shared,
                runtime =
                    native.input.candidate.runtime.copy(
                        rotation =
                            RotationRuntimeCheckpoint(
                                RuntimeOwner(StudioTarget.Global, 1),
                                3,
                                7,
                                schedule = RotationSchedule(0, HOUR, HOUR, 5),
                            ),
                        effectiveContinuation = held,
                    ),
            )
        val token =
            assertIs<PrepareResult.Prepared>(
                store.store.prepare(0, candidate, RecoveryPlan(StudioTarget.Global, StudioList(emptyList()))),
            ).token
        assertIs<CommitResult.Committed>(store.store.commit(token, candidate.runtime))
        assertEquals(JournalResult.Acknowledged, store.store.finishAccepted(token))
        return candidate
    }

    override fun close() {
        colors.setGlobalScheme(originalScheme)
        unmockkObject(AyuLaf)
        store.close()
        native.close()
    }

    companion object {
        private val HOUR = TimeUnit.HOURS.toMillis(1)
    }
}

private fun assertGeneratedAccent(native: AccentParticipantFixture) {
    val expectedColor = Color.decode("#A1B2C3")
    assertEquals(expectedColor, UIManager.getColor("Button.default.startBackground"))
    assertEquals(expectedColor, UIManager.getColor("ProgressBar.foreground"))
    assertEquals(expectedColor, native.scheme.getColor(ColorKey.find("BUTTON_BACKGROUND")))
    assertEquals(expectedColor, native.scheme.getColor(ColorKey.find("CARET_COLOR")))
    assertEquals("A1B2C3", native.codeGlance.viewportColor.uppercase())
}
