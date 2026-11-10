package dev.ayuislands.integration

import com.intellij.ide.ui.laf.UIThemeLookAndFeelInfo
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.project.Project
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.AyuLaf
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ResolvedAccent
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.rotation.ClockSample
import dev.ayuislands.rotation.RotationCommand
import dev.ayuislands.rotation.RotationRuntimeCheckpoint
import dev.ayuislands.rotation.RotationRuntimeState
import dev.ayuislands.rotation.RotationSchedule
import dev.ayuislands.rotation.RotationSnapshot
import dev.ayuislands.rotation.RotationTicket
import dev.ayuislands.rotation.RuntimeAvailability
import dev.ayuislands.rotation.RuntimeInputs
import dev.ayuislands.rotation.RuntimeOwner
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.studio.IoBoundary
import dev.ayuislands.studio.IoEvent
import dev.ayuislands.studio.IoOperation
import dev.ayuislands.studio.StudioApplyFixture
import dev.ayuislands.studio.fields.StudioAppearanceFields
import dev.ayuislands.studio.model.AcceptanceContext
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.AcceptancePlanResult
import dev.ayuislands.studio.model.AcceptanceRequest
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.DraftWriteResult
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionJournalEvent
import dev.ayuislands.studio.model.StudioApplyUpdate
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioGlowSelection
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
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
import dev.ayuislands.studio.session.StudioAccentResult
import dev.ayuislands.studio.session.StudioAcceptancePlanner
import dev.ayuislands.studio.session.StudioAcceptancePreparation
import dev.ayuislands.studio.session.StudioApplyCoordinator
import dev.ayuislands.studio.session.StudioEffectInput
import dev.ayuislands.studio.session.StudioEffectOperation
import dev.ayuislands.studio.session.StudioEffectParticipant
import dev.ayuislands.studio.session.StudioEffectRuntime
import dev.ayuislands.studio.session.StudioGlowParticipant
import dev.ayuislands.studio.session.StudioNativeEnvironment
import dev.ayuislands.studio.session.StudioNativeFocus
import dev.ayuislands.studio.session.StudioNativeHost
import dev.ayuislands.studio.session.StudioProjectAccent
import dev.ayuislands.studio.session.StudioRestoreContinuation
import dev.ayuislands.studio.session.StudioRuntimeLease
import dev.ayuislands.studio.session.StudioSessionIo
import dev.ayuislands.studio.session.StudioThemeSnapshot
import dev.ayuislands.studio.session.StudioThemeTransition
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.plaf.metal.MetalLookAndFeel
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Real native identity/admission tests; effect algorithms retain their own already qualified suites. */
class StudioNativeEnvironmentTest {
    @get:Rule val applicationRule = ApplicationRule()

    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `implicit icon pin loses admission when license changes after its plan was prepared`() =
        onEdt {
            NativeEnvironmentHarness(temporary.newFolder().toPath()).use { harness ->
                val request = harness.fixture.request("icon-license-race")
                val document = harness.fixture.document()
                val plan =
                    assertIs<AcceptancePlan.Commit>(
                        assertIs<AcceptancePlanResult.Ready>(
                            StudioAcceptancePlanner.plan(request, document, harness.fixture.observe(request, document)),
                        ).plan,
                    )
                harness.nativeEntitlement = LicenseEntitlement.UNLICENSED
                val result =
                    harness.environment.prepareAcceptance(
                        request.copy(
                            iconPin =
                                dev.ayuislands.studio.model
                                    .StudioProjectKey("/work/icon"),
                        ),
                        plan,
                    )
                assertIs<StudioAcceptancePreparation.Blocked>(result)
                assertTrue(harness.observed.isEmpty())
                assertEquals(document, harness.fixture.document())
            }
        }

    @Test
    fun `user acceptance retains native admission until owner cleanup and schedules after cleanup ACK`() =
        onEdt {
            NativeEnvironmentHarness(temporary.newFolder().toPath()).use { harness ->
                val effect = harness.fixture.effect(EffectOwner.GLOW)
                effect.afterAccept = { harness.assertHeld() }
                harness.fixture.beforePublish = { update ->
                    if (update is StudioApplyUpdate.Committed) {
                        harness.assertHeld()
                        assertTrue(harness.commands.isEmpty())
                        harness.fixture.files.arm(IoEvent(IoOperation.REMOVE, "pending.xml", IoBoundary.BEFORE))
                    }
                }
                val request = harness.fixture.request("native-user")

                val submitted = harness.apply.submit(request)
                assertIs<AcceptanceResult.AcceptedCleanupPending>(
                    submitted,
                    "Native acceptance result: $submitted; events: ${harness.fixture.events}",
                )

                harness.assertReleased()
                assertTrue(harness.commands.isEmpty())
                harness.fixture.beforePublish = null
                assertIs<AcceptanceResult.Accepted>(harness.apply.retryRecovery(request.transactionId))
                assertEquals(1, harness.commands.filterIsInstance<RotationCommand.ArmSchedule>().size)
                assertEquals(1, effect.writes)
                assertEquals(1, effect.accepts)
                assertEquals(1, harness.fixture.flushes)
                assertEquals(harness.baseline.shared, harness.fixture.document().shared)
            }
        }

    @Test
    fun `automatic acceptance shares native admission and exact focused result without flushing a user draft`() =
        onEdt {
            NativeEnvironmentHarness(temporary.newFolder().toPath()).use { harness ->
                val effect = harness.fixture.effect(EffectOwner.ACCENT)
                effect.afterApply = { harness.assertHeld() }
                effect.afterAccept = { harness.assertHeld() }

                harness.apply.executeRuntime(harness.rotation, RuntimeDispatch.Due(harness.ticket), harness.source)

                assertEquals(1, effect.writes)
                assertEquals(0, harness.fixture.flushes)
                val held = harness.observed.first()
                assertSame(harness.confirmedAccent, held.accent)
                assertSame(harness.secondProject, held.accent?.focusedProject)
                assertEquals("#FFA659", held.accent?.focused?.hex)
                assertEquals(
                    "#73D0FF",
                    held.accent
                        ?.projects
                        ?.last()
                        ?.accent
                        ?.hex,
                )
                assertEquals(harness.managers, held.glow?.entries?.map { it.manager })
                assertTrue(held.native.isLicensed)
                assertEquals(harness.process, held.native.currentProcess)
                harness.assertReleased()
                assertEquals(harness.baseline.shared, harness.fixture.document().shared)
                assertEquals(harness.baseline.sessions, harness.fixture.document().sessions)
            }
        }

    @Test
    fun `partial native admission releases captured managers and preserves a foreign existing hold`() =
        onEdt {
            NativeEnvironmentHarness(temporary.newFolder().toPath()).use { harness ->
                val foreign = harness.managers.last().holdUpdates()
                try {
                    val before = harness.fixture.document()

                    assertIs<AcceptanceResult.NotAccepted>(harness.apply.submit(harness.fixture.request("partial")))

                    assertTrue(harness.fixture.effects.all { it.writes == 0 })
                    assertTrue(harness.observed.isEmpty())
                    assertEquals(before.currents, harness.fixture.document().currents)
                    harness.managers
                        .first()
                        .holdUpdates()
                        .release()
                    assertTrue(foreign.isActive)
                } finally {
                    foreign.release()
                }
                harness.assertReleased()
            }
        }

    @Test
    fun `focus replacement after durable capture rejects the user lease before the first setter`() =
        onEdt {
            NativeEnvironmentHarness(temporary.newFolder().toPath()).use { harness ->
                harness.fixture.beforePublish = { update ->
                    if (update is StudioApplyUpdate.Journal && update.result == SessionJournalEvent.ReceiptSaved) {
                        harness.focus = harness.focus.copy(generation = harness.focus.generation + 1)
                        harness.focusedProject = harness.firstProject
                        harness.rotation.observeFocus(harness.focus)
                    }
                }
                val before = harness.fixture.document()

                assertIs<AcceptanceResult.NotAccepted>(harness.apply.submit(harness.fixture.request("focus-change")))

                assertTrue(harness.fixture.effects.all { it.writes == 0 })
                assertEquals(before.currents, harness.fixture.document().currents)
                assertEquals(before.runtime, harness.fixture.document().runtime)
                harness.assertReleased()
            }
        }

    @Test
    fun `actual editor scheme and LAF replacements invalidate capture without adopting foreign identities`() =
        onEdt {
            val colors = EditorColorsManager.getInstance()
            val originalScheme = colors.globalScheme
            val originalLaf = UIManager.getLookAndFeel()
            try {
                for (isLafReplacement in listOf(false, true)) {
                    NativeEnvironmentHarness(temporary.newFolder().toPath()).use { harness ->
                        val replacement = assertIs<EditorColorsScheme>(originalScheme.clone())
                        harness.fixture.beforePublish = { update ->
                            if (update is StudioApplyUpdate.Journal &&
                                update.result == SessionJournalEvent.ReceiptSaved
                            ) {
                                if (isLafReplacement) {
                                    UIManager.setLookAndFeel(MetalLookAndFeel())
                                } else {
                                    colors.setGlobalScheme(replacement)
                                }
                            }
                        }

                        assertIs<AcceptanceResult.NotAccepted>(
                            harness.apply.submit(harness.fixture.request("native-change")),
                        )

                        assertTrue(harness.fixture.effects.all { it.writes == 0 })
                        if (isLafReplacement) {
                            assertIs<MetalLookAndFeel>(UIManager.getLookAndFeel())
                        } else {
                            assertSame(replacement, colors.globalScheme)
                        }
                        harness.assertReleased()
                    }
                    colors.setGlobalScheme(originalScheme)
                    UIManager.setLookAndFeel(originalLaf)
                }
            } finally {
                colors.setGlobalScheme(originalScheme)
                UIManager.setLookAndFeel(originalLaf)
            }
        }

    @Test
    fun `held native lease exposes a new Accent result to Glow only after explicit confirmation`() =
        onEdt {
            NativeEnvironmentHarness(temporary.newFolder().toPath()).use { harness ->
                harness.nativeAccent = null
                val prepared = harness.prepareAdmission()
                val lease = prepared.lease
                try {
                    val observation = assertNotNull(lease.observation)
                    assertSame(harness.secondProject, observation.focus.project)
                    assertEquals(AyuVariant.MIRAGE, observation.variant)
                    assertNull(lease.accent)
                    val input =
                        StudioEffectInput(
                            prepared.plan.candidate,
                            StudioTarget.Global,
                            prepared.plan.nativeChoices,
                            StudioGlowSelection(),
                        )
                    val participant = StudioGlowParticipant(EmptyCoroutineContext)
                    assertFailsWith<IllegalStateException> { participant.prepare(input, lease) }

                    lease.confirmAccent(harness.confirmedAccent)
                    val operation = participant.prepare(input, lease)
                    operation.apply()

                    assertSame(harness.confirmedAccent, lease.accent)
                    assertNull(observation.accent)
                    assertEquals(
                        listOf("#FFCC66", "#73D0FF"),
                        assertNotNull(lease.glow).entries.map {
                            assertNotNull(it.hold.selected)
                                .accent
                                ?.value
                        },
                    )
                    assertEquals("#FFA659", lease.accent?.focused?.hex)
                    operation.prepareRestore(StudioList(emptyList()))
                    assertEquals(StudioRestoreContinuation.REQUIRED, operation.restore(StudioList(emptyList())))
                    operation.prepareRestore(StudioList(emptyList()))
                    assertEquals(StudioRestoreContinuation.NONE, operation.restore(StudioList(emptyList())))
                } finally {
                    lease.release(null)
                }
            }
        }

    @Test
    fun `stale and released native leases reject Accent confirmation without replacing the held result`() =
        onEdt {
            for (isReleased in listOf(false, true)) {
                NativeEnvironmentHarness(temporary.newFolder().toPath()).use { harness ->
                    val lease = harness.prepareAdmission().lease
                    val before = lease.accent
                    try {
                        if (isReleased) {
                            lease.release(null)
                        } else {
                            harness.focus = harness.focus.copy(generation = harness.focus.generation + 1)
                            harness.focusedProject = harness.firstProject
                            harness.rotation.observeFocus(harness.focus)
                        }
                        val next =
                            harness.confirmedAccent.copy(
                                focused = ResolvedAccent(AccentResolver.Source.GLOBAL, "#D5FF80"),
                            )

                        assertFailsWith<IllegalStateException> { lease.confirmAccent(next) }

                        assertSame(before, lease.accent)
                    } finally {
                        lease.release(null)
                    }
                }
            }
        }

    @Test
    fun `owned theme confirmation advances native appearance and clears the previous Accent`() =
        onEdt {
            NativeEnvironmentHarness(temporary.newFolder().toPath()).use { harness ->
                harness.withThemeTransition { lease, transition ->
                    val initial = assertNotNull(lease.observation)
                    val accepted = harness.fixture.document()
                    assertFalse(lease.isValid())
                    assertFailsWith<IllegalStateException> { lease.confirmAccent(harness.confirmedAccent) }

                    lease.confirmTheme(transition)

                    assertTrue(lease.isValid())
                    val confirmed = assertNotNull(lease.observation)
                    assertSame(transition.after.lookAndFeel, confirmed.lookAndFeel)
                    assertSame(transition.after.scheme, confirmed.scheme)
                    assertEquals(AyuVariant.DARK, confirmed.variant)
                    assertSame(initial.focus, confirmed.focus)
                    assertSame(initial.managers, confirmed.managers)
                    assertEquals(initial.entitlement, confirmed.entitlement)
                    assertEquals(initial.process, confirmed.process)
                    assertNull(lease.accent)
                    assertSame(expected = transition.after.lookAndFeel, actual = UIManager.getLookAndFeel())
                    assertSame(transition.after.scheme, EditorColorsManager.getInstance().globalScheme)
                    assertEquals(accepted, harness.fixture.document())
                    assertTrue(harness.fixture.effects.all { it.writes == 0 })
                }
            }
        }

    @Test
    fun `theme confirmation rejects unapplied or foreign native identities without adopting their claims`() =
        onEdt {
            for (drift in NativeThemeDrift.entries) {
                NativeEnvironmentHarness(temporary.newFolder().toPath()).use { harness ->
                    harness.withThemeTransition { lease, transition ->
                        val initial = lease.observation
                        val accent = lease.accent
                        val claimed =
                            when (drift) {
                                NativeThemeDrift.BEFORE_SCHEME -> {
                                    transition.copy(
                                        before = transition.before.copy(scheme = cloneScheme(transition.before.scheme)),
                                    )
                                }

                                NativeThemeDrift.AFTER_LAF -> {
                                    transition.copy(after = transition.after.copy(lookAndFeel = MetalLookAndFeel()))
                                }

                                NativeThemeDrift.AFTER_SCHEME -> {
                                    transition.copy(
                                        after = transition.after.copy(scheme = cloneScheme(transition.after.scheme)),
                                    )
                                }

                                NativeThemeDrift.VARIANT -> {
                                    harness.themeName = "Ayu Light"
                                    transition
                                }

                                NativeThemeDrift.UNAPPLIED -> {
                                    EditorColorsManager.getInstance().setGlobalScheme(transition.before.scheme)
                                    UIManager.setLookAndFeel(transition.before.lookAndFeel)
                                    harness.themeName = "Ayu Mirage"
                                    transition
                                }
                            }
                        val actualLaf = UIManager.getLookAndFeel()
                        val actualScheme = EditorColorsManager.getInstance().globalScheme

                        assertFailsWith<IllegalStateException>(drift.name) { lease.confirmTheme(claimed) }

                        assertSame(initial, lease.observation)
                        assertSame(accent, lease.accent)
                        assertSame(actualLaf, UIManager.getLookAndFeel())
                        assertSame(actualScheme, EditorColorsManager.getInstance().globalScheme)
                    }
                }
            }
        }

    @Test
    fun `theme confirmation cannot advance a released or changed native admission`() =
        onEdt {
            for (drift in NativeLeaseDrift.entries) {
                NativeEnvironmentHarness(temporary.newFolder().toPath()).use { harness ->
                    harness.withThemeTransition { lease, transition ->
                        val initial = lease.observation
                        val accent = lease.accent
                        when (drift) {
                            NativeLeaseDrift.RELEASED -> {
                                lease.release(null)
                            }

                            NativeLeaseDrift.FOCUS -> {
                                harness.focus = harness.focus.copy(generation = harness.focus.generation + 1)
                                harness.focusedProject = harness.firstProject
                                harness.rotation.observeFocus(harness.focus)
                            }

                            NativeLeaseDrift.ENTITLEMENT -> {
                                harness.nativeEntitlement = LicenseEntitlement.UNLICENSED
                            }

                            NativeLeaseDrift.MANAGERS -> {
                                harness.nativeManagers = harness.managers.reversed()
                            }
                        }

                        assertFailsWith<IllegalStateException>(drift.name) { lease.confirmTheme(transition) }

                        assertSame(initial, lease.observation)
                        assertSame(accent, lease.accent)
                        assertSame(expected = transition.after.lookAndFeel, actual = UIManager.getLookAndFeel())
                        assertSame(transition.after.scheme, EditorColorsManager.getInstance().globalScheme)
                    }
                }
            }
        }

    private fun cloneScheme(source: EditorColorsScheme): EditorColorsScheme =
        assertIs<EditorColorsScheme>(source.clone()).also { assertEquals(source.name, it.name) }

    private fun onEdt(action: suspend () -> Unit) {
        SwingUtilities.invokeAndWait { runBlocking { action() } }
    }
}

private enum class NativeThemeDrift { BEFORE_SCHEME, AFTER_LAF, AFTER_SCHEME, VARIANT, UNAPPLIED }

private enum class NativeLeaseDrift { RELEASED, FOCUS, ENTITLEMENT, MANAGERS }

private class NativeEnvironmentHarness(
    directory: Path,
) : AutoCloseable {
    val fixture = StudioApplyFixture(directory)
    val firstProject = project("first")
    val secondProject = project("focused-second")
    val managers = listOf(GlowOverlayManager(firstProject), GlowOverlayManager(secondProject))
    val process = StudioProcess(81, Instant.EPOCH)
    var themeName = "Ayu Mirage"
    var nativeEntitlement = LicenseEntitlement.LICENSED
    var nativeManagers = managers
    var focus = FocusSnapshot(StudioTarget.Global, 1)
    var focusedProject: Project? = secondProject
    val confirmedAccent =
        StudioAccentResult(
            AccentContext.Ayu(AyuVariant.MIRAGE),
            secondProject,
            ResolvedAccent(AccentResolver.Source.GLOBAL, "#FFA659"),
            StudioList(
                listOf(
                    StudioProjectAccent(firstProject, ResolvedAccent(AccentResolver.Source.GLOBAL, "#FFCC66")),
                    StudioProjectAccent(
                        secondProject,
                        ResolvedAccent(AccentResolver.Source.PROJECT_OVERRIDE, "#73D0FF"),
                    ),
                ),
            ),
        )
    var nativeAccent: StudioAccentResult? = confirmedAccent
    val baseline = seedRotation()
    private val checkpoint = assertNotNull(baseline.runtime.rotation)
    private val snapshot =
        RotationSnapshot(
            checkpoint,
            RuntimeInputs(
                checkpoint.owner,
                baseline.currents.global.appearance,
                RuntimeAvailability(
                    lastDefinitivePro = true,
                    effectiveFollowSystem = false,
                    variant = AyuVariant.MIRAGE,
                    targetValid = true,
                    recoveryPending = false,
                ),
            ),
        )
    val ticket = RotationTicket(checkpoint.owner, 1, 0, 0, 0)
    val rotation = StudioRuntimeCoordinator(RotationRuntimeState.Waiting(snapshot, ticket), focus)
    val commands = mutableListOf<RotationCommand>()
    val observed = mutableListOf<StudioEffectRuntime>()
    val source =
        object : StudioRuntimeSource {
            override fun clock() = ClockSample(TimeUnit.HOURS.toMillis(2), TimeUnit.HOURS.toNanos(2))

            override fun operationId(): UUID = UUID.fromString("00000000-0000-0000-0000-000000000081")

            override fun transactionId() = "native-automatic"

            override fun randomColor(variant: AyuVariant) = "#FFA659"
        }
    private val session =
        object : StudioSessionIo {
            override suspend fun publish(update: StudioApplyUpdate) = fixture.publish(update)

            override suspend fun flush(record: StudioSessionRecord): DraftWriteResult = fixture.flush(record)

            override suspend fun observe(
                request: AcceptanceRequest,
                document: StudioDocument,
            ): AcceptanceContext = fixture.observe(request, document)
        }
    private val host =
        StudioNativeHost(
            focus = { StudioNativeFocus(focus, focusedProject) },
            entitlement = { nativeEntitlement },
            accent = { nativeAccent },
            managers = { nativeManagers },
            process = process,
        )
    val environment =
        StudioNativeEnvironment(
            rotation,
            source,
            session,
            host,
            transitions = { commands.addAll(it.commands) },
            nativeContext = EmptyCoroutineContext,
        )
    private val participants =
        fixture.effects.map { effect ->
            object : StudioEffectParticipant by effect {
                override suspend fun prepare(
                    input: StudioEffectInput,
                    runtime: StudioEffectRuntime,
                ): StudioEffectOperation {
                    observed += runtime
                    assertHeld()
                    return effect.prepare(input, runtime)
                }
            }
        }
    val apply = StudioApplyCoordinator(fixture.store, environment, participants, fixture.mutex, EmptyCoroutineContext)

    init {
        // Theme selection is outside this packet; native LAF/scheme identities and manager holds stay real.
        mockkObject(AyuIslandsSettings.Companion, AyuLaf)
        val settings = mockk<AyuIslandsSettings>()
        every { settings.state } returns
            dev.ayuislands.studio.StudioFixtures
                .legacyView(AyuIslandsState())
                .settings
        every { AyuIslandsSettings.getInstance() } returns settings
        every { AyuLaf.currentThemeName(any()) } answers { themeName }
    }

    suspend fun prepareAdmission(): StudioAcceptancePreparation.Prepared {
        val request = fixture.request("native-handoff")
        val document = fixture.document()
        val context = fixture.observe(request, document)
        val planned = assertIs<AcceptancePlanResult.Ready>(StudioAcceptancePlanner.plan(request, document, context))
        return assertIs<StudioAcceptancePreparation.Prepared>(
            environment.prepareAcceptance(request, assertIs<AcceptancePlan.Commit>(planned.plan)),
        )
    }

    suspend fun withThemeTransition(action: suspend (StudioRuntimeLease, StudioThemeTransition) -> Unit) {
        val lease = prepareAdmission().lease
        val held = assertNotNull(lease.observation)
        val before = StudioThemeSnapshot(themeInfo("Ayu Mirage"), held.lookAndFeel, held.scheme)
        val after =
            StudioThemeSnapshot(
                themeInfo("Ayu Dark"),
                MetalLookAndFeel(),
                assertIs<EditorColorsScheme>(held.scheme.clone()),
            )
        val colors = EditorColorsManager.getInstance()
        try {
            UIManager.setLookAndFeel(after.lookAndFeel)
            colors.setGlobalScheme(after.scheme)
            themeName = "Ayu Dark"
            action(lease, StudioThemeTransition(before, after))
        } finally {
            lease.release(null)
            colors.setGlobalScheme(before.scheme)
            UIManager.setLookAndFeel(before.lookAndFeel)
            themeName = "Ayu Mirage"
        }
    }

    private fun themeInfo(displayName: String): UIThemeLookAndFeelInfo =
        mockk {
            every { name } returns displayName
        }

    fun assertHeld() {
        val held = assertNotNull(observed.last().glow)
        assertTrue(held.isActive)
        assertEquals(managers, held.entries.map { it.manager })
        assertTrue(held.entries.all { it.hold.isActive })
    }

    fun assertReleased() {
        observed.forEach { assertFalse(assertNotNull(it.glow).isActive) }
        managers.forEach { it.holdUpdates().release() }
    }

    private fun seedRotation(): StudioDocument {
        val before = fixture.initial
        val current = before.currents.global
        val enabled =
            StudioAppearanceFields.replace(
                current.appearance,
                current.appearance.accent.records
                    .single { it.key.name == "accentRotationEnabled" }
                    .copy(value = RawValue.BooleanValue(true)),
            )
        val owner = RuntimeOwner(StudioTarget.Global, current.revision)
        val seededCheckpoint =
            RotationRuntimeCheckpoint(owner, 0, 0, schedule = RotationSchedule(0, 1, 1, 1))
        val candidate =
            before.copy(
                header = before.header.copy(revision = 1, transactionId = "native-seed"),
                currents = before.currents.copy(global = current.copy(appearance = enabled)),
                runtime = before.runtime.copy(rotation = seededCheckpoint),
            )
        val token =
            assertIs<PrepareResult.Prepared>(
                fixture.store.prepare(0, candidate, RecoveryPlan(StudioTarget.Global, StudioList(emptyList()))),
            ).token
        assertIs<CommitResult.Committed>(fixture.store.commit(token, candidate.runtime))
        assertEquals(JournalResult.Acknowledged, fixture.store.finishAccepted(token))
        return candidate
    }

    override fun close() {
        managers.forEach { it.dispose() }
        unmockkObject(AyuIslandsSettings.Companion, AyuLaf)
        fixture.close()
    }

    private fun project(projectName: String): Project =
        mockk<Project>(relaxed = true) {
            every { isDisposed } returns false
            every { name } returns projectName
        }
}
