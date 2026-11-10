package dev.ayuislands.rotation

import com.intellij.openapi.project.Project
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.ResolvedAccent
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.studio.IoBoundary
import dev.ayuislands.studio.IoEvent
import dev.ayuislands.studio.IoOperation
import dev.ayuislands.studio.ManualSessionScheduler
import dev.ayuislands.studio.StudioApplyFixture
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionEffect
import dev.ayuislands.studio.model.SessionJournalEvent
import dev.ayuislands.studio.model.StudioApplyUpdate
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioNativeChange
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioProjectRecord
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.StudioThemeChange
import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.JournalDecodeResult
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.PendingJournal
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.RecoveryPurpose
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioJournalCodec
import dev.ayuislands.studio.persistence.StudioXml
import dev.ayuislands.studio.runtime.FocusSnapshot
import dev.ayuislands.studio.runtime.RuntimeDispatch
import dev.ayuislands.studio.runtime.StudioRuntimeBinding
import dev.ayuislands.studio.runtime.StudioRuntimeCoordinator
import dev.ayuislands.studio.runtime.StudioRuntimeObservation
import dev.ayuislands.studio.runtime.StudioRuntimeSource
import dev.ayuislands.studio.session.ScheduledCall
import dev.ayuislands.studio.session.SessionScheduler
import dev.ayuislands.studio.session.StudioAccentResult
import dev.ayuislands.studio.session.StudioAcceptancePreparation
import dev.ayuislands.studio.session.StudioApplyCoordinator
import dev.ayuislands.studio.session.StudioApplyEnvironment
import dev.ayuislands.studio.session.StudioDraftPolicy
import dev.ayuislands.studio.session.StudioNativeFocus
import dev.ayuislands.studio.session.StudioNativeHost
import dev.ayuislands.studio.session.StudioNativeObservation
import dev.ayuislands.studio.session.StudioRuntimeLease
import dev.ayuislands.studio.sessionRecord
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RotationNativeAdmissionTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `manual theme choice updates only its remembered Follow appearance slot in the same transaction`() =
        runBlocking {
            AutomaticHarness(directory, initialTime = SIX_HOURS / 2).use { harness ->
                harness.replaceAccepted { document ->
                    document.copy(
                        shared =
                            document.shared.copy(
                                system =
                                    document.shared.system.copy(
                                        records =
                                            StudioList(
                                                document.shared.system.records.map { field ->
                                                    if (field.key.name == "followSystemAppearance") {
                                                        field.copy(value = RawValue.BooleanValue(true))
                                                    } else {
                                                        field
                                                    }
                                                },
                                            ),
                                    ),
                            ),
                    )
                }
                val before = harness.native.document()
                val selected = "Ayu Light"
                harness.native.effect(EffectOwner.SYSTEM).beforePrepare = {
                    val journal = assertIs<StoreLoad.Recovering>(harness.native.store.load()).journal
                    assertEquals(
                        RawValue.Text(selected),
                        journal.candidate.shared.system.records
                            .single { it.key.name == "lastLightAppearanceTheme" }
                            .value,
                    )
                    assertEquals(before.shared, harness.native.document().shared)
                }
                harness.nativeChange(StudioNativeChange.Theme(StudioThemeChange.Explicit(selected)))
                val after = harness.native.document()
                assertEquals(
                    RawValue.Text(selected),
                    after.shared.system.records
                        .single { it.key.name == "lastLightAppearanceTheme" }
                        .value,
                )
                assertEquals(
                    before.shared.system.records
                        .filterNot { it.key.name == "lastLightAppearanceTheme" },
                    after.shared.system.records
                        .filterNot { it.key.name == "lastLightAppearanceTheme" },
                )
                assertEquals(before.currents, after.currents)
                assertEquals(before.runtime.rotation, after.runtime.rotation)
                assertEquals(before.shared.workspace, after.shared.workspace)
                assertEquals(before.shared.mappings, after.shared.mappings)
                assertEquals(0, harness.source.draws)
            }
        }

    @Test
    fun `appearance refresh keeps accepted choices and rotation history while using the existing owners`() =
        runBlocking {
            AutomaticHarness(directory, initialTime = SIX_HOURS / 2).use { harness ->
                val before = harness.native.document()
                val ticket = harness.ticket()
                harness.nativeChange(StudioNativeChange.Appearance)
                val accepted = harness.native.document()
                assertEquals(before.currents, accepted.currents)
                assertEquals(before.shared, accepted.shared)
                assertEquals(before.catalog, accepted.catalog)
                assertEquals(before.runtime.rotation, accepted.runtime.rotation)
                assertEquals(ticket.remainingMillis, harness.ticket().remainingMillis)
                assertEquals(0, harness.source.draws)
                assertEquals(
                    listOf(
                        EffectOwner.ACCENT,
                        EffectOwner.FONT,
                        EffectOwner.SYNTAX,
                        EffectOwner.VCS,
                        EffectOwner.GLOW,
                        EffectOwner.WORKSPACE,
                    ),
                    harness.native.acquiredEffects
                        .single()
                        .toList(),
                )
                assertEquals(1, harness.native.effect(EffectOwner.WORKSPACE).writes)
                assertEquals(0, harness.native.effect(EffectOwner.SYSTEM).writes)
            }
        }

    @Test
    fun `explicit theme admission journals native purpose and orders SYSTEM before dependent appearance owners`() =
        runBlocking {
            AutomaticHarness(directory, initialTime = SIX_HOURS / 2).use { harness ->
                val change = StudioNativeChange.Theme(StudioThemeChange.Explicit("Ayu Dark (Islands UI)"))
                harness.native.effect(EffectOwner.SYSTEM).beforePrepare = {
                    val journal = assertIs<StoreLoad.Recovering>(harness.native.store.load()).journal
                    val decoded =
                        assertIs<JournalDecodeResult.Ready>(
                            StudioJournalCodec.decode(StudioJournalCodec.encode(journal)),
                        ).journal
                    assertEquals(change, assertIs<RecoveryPurpose.NativeChange>(decoded.recovery.purpose).change)
                    assertEquals(0, harness.accent.writes)
                }
                harness.nativeChange(change)
                assertEquals(
                    EffectOwner.SYSTEM,
                    harness.native.acquiredEffects
                        .single()
                        .first(),
                )
                val themeWrite = harness.native.events.indexOf("apply-SYSTEM")
                val accentWrite = harness.native.events.indexOf("apply-ACCENT")
                assertTrue(themeWrite in 0 until accentWrite)
                assertEquals(1, harness.native.effect(EffectOwner.SYSTEM).writes)
                assertEquals(0, harness.source.draws)
                harness.assertPreferencesPreserved()
            }
        }

    @Test
    fun `scan context refresh admits only Accent and Glow without turning it into a rotation tick`() =
        runBlocking {
            AutomaticHarness(directory, initialTime = SIX_HOURS / 2).use { harness ->
                val checkpoint =
                    harness.native
                        .document()
                        .runtime.rotation
                harness.nativeChange(StudioNativeChange.AccentContext)
                assertEquals(
                    listOf(EffectOwner.ACCENT, EffectOwner.GLOW),
                    harness.native.acquiredEffects
                        .single()
                        .toList(),
                )
                assertEquals(1, harness.accent.writes)
                assertEquals(1, harness.native.effect(EffectOwner.GLOW).writes)
                assertEquals(0, harness.native.effect(EffectOwner.FONT).writes)
                assertEquals(0, harness.source.draws)
                assertEquals(
                    checkpoint,
                    harness.native
                        .document()
                        .runtime.rotation,
                )
                harness.assertPreferencesPreserved()
            }
        }

    @Test
    fun `late native refresh failure restores earlier owners and retries the same intent without drawing a color`() =
        runBlocking {
            AutomaticHarness(directory, initialTime = SIX_HOURS / 2).use { harness ->
                val checkpoint =
                    harness.native
                        .document()
                        .runtime.rotation
                val workspace = harness.native.effect(EffectOwner.WORKSPACE)
                workspace.failAfter = true
                harness.nativeChange(StudioNativeChange.Appearance)
                assertIs<RotationRuntimeState.Faulted.Loaded>(harness.runtime.state)
                assertEquals(
                    checkpoint,
                    harness.native
                        .document()
                        .runtime.rotation,
                )
                assertEquals(0, harness.accent.value)
                assertEquals(0, workspace.value)
                assertEquals(0, harness.source.draws)
                workspace.failAfter = false
                harness.recover()
                assertIs<RotationRuntimeState.Waiting>(harness.runtime.state)
                assertEquals(2, harness.accent.writes)
                assertEquals(1, harness.accent.accepts)
                assertEquals(0, harness.source.draws)
                assertEquals(
                    checkpoint,
                    harness.native
                        .document()
                        .runtime.rotation,
                )
                harness.assertPreferencesPreserved()
            }
        }
}

class RotationNativeBindingTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `reactivating the accepted window refreshes Accent without resetting its rotation appointment`(): Unit =
        runBlocking {
            AutomaticHarness(directory, coldTarget = StudioTarget.Global, initialTime = SIX_HOURS / 2).use { harness ->
                val target = StudioTarget.Project(StudioProjectKey("reactivated-window"))
                harness.addProject(target)
                val native = BindingObservation()
                val scheduler = BindingScheduler()
                val binding = harness.binding(this, native.host, scheduler)
                binding.use {
                    binding.start().join()
                    native.focus(target, 2)
                    binding.focusChanged().join()
                    val before = harness.native.document()
                    val writes = harness.accent.writes
                    val generation = harness.snapshot().focusGeneration

                    binding.windowActivated().join()

                    val accepted = harness.native.document()
                    assertEquals(writes + 1, harness.accent.writes)
                    assertEquals(0, harness.source.draws)
                    assertEquals(before.currents, accepted.currents)
                    assertEquals(before.runtime.rotation, accepted.runtime.rotation)
                    assertEquals(generation, harness.snapshot().focusGeneration)
                    assertEquals(1, scheduler.active)
                }
            }
        }

    @Test
    fun `native theme queue survives another owner journal and resumes in explicit order after settlement`(): Unit =
        runBlocking {
            AutomaticHarness(directory, coldTarget = StudioTarget.Global, initialTime = SIX_HOURS / 2).use { harness ->
                val native = BindingObservation()
                val binding = harness.binding(this, native.host, ManualSessionScheduler())
                binding.start().join()
                val reads = harness.source.clockReads
                val before = harness.native.document()
                val candidate =
                    before.copy(
                        header =
                            before.header.copy(
                                revision = before.header.revision + 1,
                                transactionId = "unrelated-accepted-metadata",
                            ),
                    )
                val token =
                    assertIs<PrepareResult.Prepared>(
                        harness.native.store.prepare(
                            before.header.revision,
                            candidate,
                            RecoveryPlan(StudioTarget.Global, StudioList(emptyList())),
                        ),
                    ).token
                val choices = mutableListOf<String>()
                harness.native.effect(EffectOwner.SYSTEM).beforePrepare = {
                    val journal = assertIs<StoreLoad.Recovering>(harness.native.store.load()).journal
                    val purpose = assertIs<RecoveryPurpose.NativeChange>(journal.recovery.purpose)
                    val theme = assertIs<StudioNativeChange.Theme>(purpose.change)
                    choices.add(assertIs<StudioThemeChange.Explicit>(theme.selection).name)
                }
                binding.use {
                    binding.selectTheme("Ayu Dark").join()
                    binding.selectTheme("Ayu Mirage").join()
                    assertEquals(reads, harness.source.clockReads)
                    assertEquals(0, harness.accent.writes)
                    assertEquals(token, assertIs<StoreLoad.Recovering>(harness.native.store.load()).journal.token)
                    assertIs<CommitResult.Committed>(harness.native.store.commit(token, candidate.runtime))
                    assertEquals(JournalResult.Acknowledged, harness.native.store.finishAccepted(token))
                    binding.resumePending().join()
                    assertEquals(listOf("Ayu Dark", "Ayu Mirage"), choices)
                    assertEquals(2, harness.accent.writes)
                    assertEquals(0, harness.source.draws)
                    assertEquals(before.currents, harness.native.document().currents)
                }
            }
        }

    @Test
    fun `binding starts once and owns one timer without drawing before its accepted deadline`(): Unit =
        runBlocking {
            AutomaticHarness(directory, coldTarget = StudioTarget.Global, initialTime = SIX_HOURS / 2).use { harness ->
                val native = BindingObservation()
                val scheduler = BindingScheduler()
                val binding = harness.binding(this, native.host, scheduler)
                binding.use {
                    binding.start().join()
                    assertEquals(1L, harness.snapshot().focusGeneration)
                    val reads = harness.source.clockReads
                    binding.start().join()
                    binding.focusChanged().join()
                    assertEquals(reads, harness.source.clockReads)
                    assertEquals(0, harness.source.draws)
                    assertEquals(0, harness.accent.writes)
                    assertEquals(1, scheduler.scheduled)
                    assertEquals(1, scheduler.active)
                }
            }
        }

    @Test
    fun `latest focus supersedes queued native context before clock random or effect capture`(): Unit =
        runBlocking {
            AutomaticHarness(directory, initialTime = SIX_HOURS / 2).use { harness ->
                val first = StudioTarget.Project(StudioProjectKey("focus-one"))
                val latest = StudioTarget.Project(StudioProjectKey("focus-two"))
                harness.addProject(first)
                harness.addProject(latest)
                val native = BindingObservation()
                val binding = harness.binding(this, native.host, ManualSessionScheduler())
                binding.use {
                    harness.native.mutex.lock()
                    val queued =
                        try {
                            native.focus(first, 2)
                            val firstJob = binding.focusChanged()
                            yield()
                            assertTrue(firstJob.isActive)
                            native.focus(latest, 3)
                            val latestJob = binding.focusChanged()
                            yield()
                            assertEquals(0, harness.source.clockReads)
                            assertEquals(0, harness.source.draws)
                            listOf(firstJob, latestJob)
                        } finally {
                            harness.native.mutex.unlock()
                        }
                    queued.joinAll()
                    assertEquals(
                        latest,
                        harness
                            .snapshot()
                            .inputs.owner.target,
                    )
                    assertEquals(3L, harness.snapshot().focusGeneration)
                    assertEquals(1, harness.accent.writes)
                    assertEquals(0, harness.source.draws)
                }
            }
        }

    @Test
    fun `pending journal keeps only latest observed focus and drains it after shared recovery`(): Unit =
        runBlocking {
            AutomaticHarness(directory).use { harness ->
                val first = StudioTarget.Project(StudioProjectKey("pending-one"))
                val latest = StudioTarget.Project(StudioProjectKey("pending-two"))
                harness.addProject(first)
                harness.addProject(latest)
                harness.accent.failAfter = true
                harness.accent.failRestore = true
                harness.due(harness.ticket())
                assertIs<StoreLoad.Recovering>(harness.native.store.load())
                val native = BindingObservation()
                val binding = harness.binding(this, native.host, ManualSessionScheduler())
                binding.use {
                    native.focus(first, 2)
                    binding.focusChanged().join()
                    native.focus(latest, 3)
                    binding.focusChanged().join()
                    assertEquals(1, harness.accent.writes)
                    assertEquals(1, harness.source.draws)
                    harness.accent.failAfter = false
                    harness.accent.failRestore = false
                    binding.retry().join()
                    assertIs<StoreLoad.Ready>(harness.native.store.load())
                    assertEquals(
                        latest,
                        harness
                            .snapshot()
                            .inputs.owner.target,
                    )
                    assertEquals(3L, harness.snapshot().focusGeneration)
                    assertEquals(2, harness.accent.writes)
                    assertEquals(1, harness.source.draws)
                }
            }
        }

    @Test
    fun `last close transfers effective pin captured before disposal without appearance writes`(): Unit =
        runBlocking {
            AutomaticHarness(directory, initialTime = SIX_HOURS / 2).use { harness ->
                val projectTarget = StudioTarget.Project(StudioProjectKey("closing"))
                harness.addProject(projectTarget)
                harness.focus(projectTarget, 2)
                val requested = harness.snapshot().inputs.appearance
                val outgoing = harness.snapshot().inputs.owner
                val native = BindingObservation()
                native.focus(projectTarget, 2)
                val project = assertNotNull(native.observation.focus.project)
                val resolved = ResolvedAccent(AccentResolver.Source.PROJECT_OVERRIDE, "#FF0000")
                native.observation =
                    native.observation.copy(
                        accent =
                            StudioAccentResult(
                                AccentContext.Ayu(AyuVariant.MIRAGE),
                                project,
                                resolved,
                                StudioList(emptyList()),
                            ),
                    )
                val binding = harness.binding(this, native.host, ManualSessionScheduler())
                binding.use {
                    val writes = harness.accent.writes
                    binding.projectClosing(project)
                    every { project.isDisposed } returns true
                    native.focus(StudioTarget.Global, 3)
                    native.observation = native.observation.copy(accent = null)
                    binding.focusChanged().join()
                    val accepted = harness.native.document()
                    val held = assertNotNull(accepted.runtime.effectiveContinuation)
                    assertEquals(outgoing, held.source)
                    assertEquals(requested, held.requested)
                    assertEquals(resolved, held.accent?.resolved)
                    assertEquals(requested, accepted.currents.global.appearance)
                    assertEquals(writes, harness.accent.writes)
                    assertEquals(0, harness.source.draws)
                    assertEquals(
                        StudioTarget.Global,
                        harness
                            .snapshot()
                            .inputs.owner.target,
                    )
                }
            }
        }

    @Test
    fun `before close observation cannot leak into a later ordinary project focus`(): Unit =
        runBlocking {
            AutomaticHarness(directory, initialTime = SIX_HOURS / 2).use { harness ->
                val outgoing = StudioTarget.Project(StudioProjectKey("outgoing"))
                val incoming = StudioTarget.Project(StudioProjectKey("incoming"))
                harness.addProject(outgoing)
                harness.addProject(incoming)
                harness.focus(outgoing, 2)
                val native = BindingObservation()
                native.focus(outgoing, 2)
                val binding = harness.binding(this, native.host, ManualSessionScheduler())
                binding.use {
                    binding.projectClosing(assertNotNull(native.observation.focus.project))
                    native.focus(incoming, 3)
                    binding.focusChanged().join()
                    assertEquals(
                        incoming,
                        harness
                            .snapshot()
                            .inputs.owner.target,
                    )
                    assertNull(
                        harness.native
                            .document()
                            .runtime.effectiveContinuation,
                    )
                    assertEquals(0, harness.source.draws)
                }
            }
        }

    @Test
    fun `duplicate accepted focus cannot suppress the next legitimate timer arm`(): Unit =
        runBlocking {
            AutomaticHarness(directory, initialTime = SIX_HOURS / 2).use { harness ->
                harness.focus(StudioTarget.Global, 1)
                val native = BindingObservation()
                val scheduler = BindingScheduler()
                val binding = harness.binding(this, native.host, scheduler)
                binding.use {
                    binding.focusChanged().join()
                    binding.consume(
                        RotationTransition(
                            harness.runtime.state,
                            StudioList(listOf(RotationCommand.ArmSchedule(harness.ticket()))),
                        ),
                    )
                    assertEquals(1, scheduler.scheduled)
                    assertEquals(1, scheduler.active)
                }
            }
        }

    @Test
    fun `closing binding cancels a focus already queued behind shared admission`(): Unit =
        runBlocking {
            AutomaticHarness(directory, initialTime = SIX_HOURS / 2).use { harness ->
                val target = StudioTarget.Project(StudioProjectKey("queued-disposal"))
                harness.addProject(target)
                val native = BindingObservation()
                val binding = harness.binding(this, native.host, ManualSessionScheduler())
                harness.native.mutex.lock()
                val queued =
                    try {
                        native.focus(target, 2)
                        val request = binding.focusChanged()
                        yield()
                        assertTrue(request.isActive)
                        binding.close()
                        request
                    } finally {
                        harness.native.mutex.unlock()
                        binding.close()
                    }
                queued.join()
                assertTrue(queued.isCancelled)
                assertEquals(0, harness.source.clockReads)
                assertEquals(0, harness.source.draws)
                assertEquals(0, harness.accent.writes)
            }
        }

    @Test
    fun `before close rejects a newly observed epoch that has not become the accepted owner`() =
        runBlocking {
            AutomaticHarness(directory, initialTime = SIX_HOURS / 2).use { harness ->
                val target = StudioTarget.Project(StudioProjectKey("same-target-new-epoch"))
                harness.addProject(target)
                harness.focus(target, 2)
                val native = BindingObservation()
                native.focus(target, 3)
                val project = assertNotNull(native.observation.focus.project)
                val observed = ResolvedAccent(AccentResolver.Source.PROJECT_OVERRIDE, "#FF0000")
                native.observation =
                    native.observation.copy(
                        accent =
                            StudioAccentResult(
                                AccentContext.Ayu(AyuVariant.MIRAGE),
                                project,
                                observed,
                                StudioList(emptyList()),
                            ),
                    )
                val binding = harness.binding(this, native.host, ManualSessionScheduler())
                binding.use {
                    binding.projectClosing(project)
                    native.focus(StudioTarget.Global, 4)
                    binding.focusChanged().join()
                    assertNull(
                        harness.native
                            .document()
                            .runtime.effectiveContinuation,
                    )
                }
            }
        }

    @Test
    fun `disposed binding rejects late timer focus and retry callbacks without observing native state`(): Unit =
        runBlocking {
            AutomaticHarness(directory, coldTarget = StudioTarget.Global, initialTime = SIX_HOURS / 2).use { harness ->
                val native = BindingObservation()
                val scheduler = ManualSessionScheduler()
                val binding = harness.binding(this, native.host, scheduler)
                binding.start().join()
                binding.close()
                every { native.host.observe() } throws IllegalStateException("Disposed native observation")
                scheduler.fireCancelled()
                scheduler.advance(SIX_HOURS)
                binding.focusChanged().join()
                binding.retry().join()
                yield()
                assertEquals(0, harness.accent.writes)
                assertEquals(0, harness.source.draws)
            }
        }
}

private class BindingScheduler : SessionScheduler {
    private val delegate = ManualSessionScheduler()
    var scheduled = 0
        private set
    var active = 0
        private set

    override fun schedule(
        delayMillis: Long,
        action: () -> Unit,
    ): ScheduledCall {
        scheduled++
        active++
        var isPending = true
        val call =
            delegate.schedule(delayMillis) {
                if (isPending) {
                    isPending = false
                    active--
                }
                action()
            }
        return ScheduledCall {
            if (isPending) {
                isPending = false
                active--
            }
            call.cancel()
        }
    }
}

private class BindingObservation {
    var observation =
        StudioNativeObservation(
            mockk(),
            mockk(),
            StudioNativeFocus(FocusSnapshot(StudioTarget.Global, 1), null),
            LicenseEntitlement.LICENSED,
            AyuVariant.MIRAGE,
            null,
            StudioList(emptyList()),
            null,
        )
    val host =
        mockk<StudioNativeHost> {
            every { observe() } answers { observation }
        }

    fun focus(
        target: StudioTarget,
        generation: Long,
    ) {
        val project =
            if (target is StudioTarget.Project) {
                mockk<Project> {
                    every { isDisposed } returns false
                    every { isDefault } returns false
                }
            } else {
                null
            }
        observation = observation.copy(focus = StudioNativeFocus(FocusSnapshot(target, generation), project))
    }
}

class RotationContextAdmissionTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `focused project reapplies appearance without profile activation or appointment reset`(): Unit =
        runBlocking {
            AutomaticHarness(directory, initialTime = SIX_HOURS / 2).use { harness ->
                val target = StudioTarget.Project(StudioProjectKey("incoming"))
                harness.addProject(target)
                val before = harness.native.document()

                harness.focus(target, 2)
                val reads = harness.source.clockReads
                harness.focus(target, 2)
                harness.focus(StudioTarget.Global, 1)

                val checkpoint = harness.snapshot().checkpoint
                assertEquals(RuntimeOwner(target, 23), checkpoint.owner)
                assertEquals(before.runtime.rotation?.schedule, checkpoint.schedule)
                assertEquals(3, checkpoint.sequenceCursorRaw)
                assertEquals(7L, checkpoint.successfulStepSerial)
                assertEquals(SIX_HOURS / 2, harness.ticket().remainingMillis)
                assertEquals(1, harness.arms.size)
                assertEquals(reads, harness.source.clockReads)
                assertEquals(0, harness.source.draws)
                assertEquals(1, harness.accent.writes)
                assertEquals(1, harness.native.effect(EffectOwner.FONT).writes)
                assertEquals(before.currents, harness.native.document().currents)
                assertEquals(before.catalog, harness.native.document().catalog)
                assertEquals(before.shared, harness.native.document().shared)
            }
        }

    @Test
    fun `latest focus invalidates an old ticket before queued native work enters the gate`(): Unit =
        runBlocking {
            AutomaticHarness(directory).use { harness ->
                val target = StudioTarget.Project(StudioProjectKey("incoming"))
                harness.addProject(target)
                val before = harness.native.document()
                harness.native.mutex.lock()
                val due = async(start = CoroutineStart.UNDISPATCHED) { harness.due(harness.ticket()) }
                val focus = async(start = CoroutineStart.UNDISPATCHED) { harness.focus(target, 2) }
                assertEquals(0, harness.source.clockReads)
                assertEquals(0, harness.source.draws)
                assertEquals(0, harness.accent.writes)
                harness.native.mutex.unlock()

                assertTrue(due.await().commands.isEmpty())
                focus.await()

                assertEquals(RuntimeOwner(target, 23), harness.snapshot().checkpoint.owner)
                assertEquals(7L, harness.snapshot().checkpoint.successfulStepSerial)
                assertEquals(before.runtime.rotation?.schedule, harness.snapshot().checkpoint.schedule)
                assertEquals(0, harness.source.draws)
                assertEquals(1, harness.accent.writes)
                assertEquals(before.currents, harness.native.document().currents)
            }
        }

    @Test
    fun `failed incoming appearance restores surfaces without changing accepted context`(): Unit =
        runBlocking {
            AutomaticHarness(directory).use { harness ->
                val target = StudioTarget.Project(StudioProjectKey("incoming"))
                harness.addProject(target)
                val before = harness.native.document()
                harness.native.effect(EffectOwner.FONT).failAfter = true

                harness.focus(target, 2)

                assertEquals(before, harness.native.document())
                assertEquals(before.runtime.rotation, harness.snapshot().checkpoint)
                assertIs<RotationRuntimeState.Faulted.Loaded>(harness.runtime.state)
                assertEquals(1, harness.native.events.count { it == "restore-ACCENT" })
                assertEquals(1, harness.native.events.count { it == "restore-FONT" })
                assertEquals(0, harness.source.draws)
                assertTrue(harness.arms.isEmpty())
                assertIs<StoreLoad.Ready>(harness.native.store.load())
            }
        }
}

class RotationContinuityAdmissionTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `last project transfers Current and effective accent without native replay or history reset`(): Unit =
        runBlocking {
            AutomaticHarness(directory, initialTime = SIX_HOURS / 2).use { harness ->
                val target = StudioTarget.Project(StudioProjectKey("closing"))
                harness.addProject(target)
                harness.focus(target, 2)
                val before = harness.native.document()
                val writes = harness.native.effects.sumOf { it.writes }
                val held =
                    dev.ayuislands.studio.model.StudioAccentContinuation(
                        AyuVariant.MIRAGE,
                        ResolvedAccent(
                            AccentResolver.Source.PROJECT_OVERRIDE,
                            "#FF0000",
                        ),
                        StudioList(emptyList()),
                    )

                harness.continuity(3, held)

                val after = harness.native.document()
                val continuation = assertNotNull(after.runtime.effectiveContinuation)
                assertEquals(RuntimeOwner(target, 23), continuation.source)
                assertEquals(
                    before.currents.projects
                        .single()
                        .current.appearance,
                    continuation.requested,
                )
                assertEquals(continuation.requested, after.currents.global.appearance)
                assertEquals(held, continuation.accent)
                assertEquals(before.currents.projects, after.currents.projects)
                assertEquals(before.shared, after.shared)
                assertEquals(before.catalog, after.catalog)
                assertEquals(before.runtime.rotation?.schedule, after.runtime.rotation?.schedule)
                assertEquals(before.runtime.rotation?.sequenceCursorRaw, after.runtime.rotation?.sequenceCursorRaw)
                assertEquals(writes, harness.native.effects.sumOf { it.writes })
                assertEquals(0, harness.source.draws)
                assertEquals(
                    StudioTarget.Global,
                    harness
                        .snapshot()
                        .inputs.owner.target,
                )
            }
        }
}

class RotationColdStartTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `explicit recovery retries an unloaded startup with retained observations after its target returns`(): Unit =
        runBlocking {
            val target = StudioTarget.Project(StudioProjectKey("returning-project"))
            AutomaticHarness(directory, coldTarget = target).use { harness ->
                harness.start()
                val fault = assertIs<RotationRuntimeState.Faulted.Unloaded>(harness.runtime.state)
                val observed = assertNotNull(fault.observation)
                assertEquals(1L, observed.generation)
                assertEquals(AyuVariant.MIRAGE, observed.availability.variant)
                assertEquals(true, observed.availability.lastDefinitivePro)
                val reads = harness.source.clockReads
                harness.start()
                assertEquals(reads, harness.source.clockReads)
                assertTrue(harness.arms.isEmpty())
                assertEquals(0, harness.source.draws)
                assertEquals(0, harness.accent.writes)
                harness.replaceAccepted { document ->
                    document.copy(
                        currents =
                            document.currents.copy(
                                projects =
                                    StudioList(
                                        listOf(
                                            StudioProjectRecord(
                                                target.key,
                                                "Returned",
                                                document.currents.global.copy(revision = 23),
                                            ),
                                        ),
                                    ),
                            ),
                    )
                }

                harness.recover()
                harness.recover()

                assertEquals(RuntimeOwner(target, 23), harness.snapshot().inputs.owner)
                assertEquals(RuntimeOwner(target, 23), harness.snapshot().checkpoint.owner)
                assertEquals(1L, harness.snapshot().observationGeneration)
                assertEquals(SIX_HOURS, harness.ticket().remainingMillis)
                assertEquals(1, harness.arms.size)
                assertEquals(7L, harness.snapshot().checkpoint.successfulStepSerial)
                assertEquals(3, harness.snapshot().checkpoint.sequenceCursorRaw)
                assertEquals(0, harness.source.draws)
                assertEquals(0, harness.accent.writes)
                assertIs<StoreLoad.Ready>(harness.native.store.load())
            }
        }

    @Test
    fun `cold startup reads no clock before admission and arms a saved future deadline only once`(): Unit =
        runBlocking {
            AutomaticHarness(directory, coldTarget = StudioTarget.Global).use { harness ->
                harness.source.advanceTo(SIX_HOURS / 2)
                harness.native.mutex.lock()
                val first = async(start = CoroutineStart.UNDISPATCHED) { harness.start() }
                val duplicate = async(start = CoroutineStart.UNDISPATCHED) { harness.start() }
                assertIs<RotationRuntimeState.Loading>(harness.runtime.state)
                assertEquals(0, harness.source.clockReads)
                assertEquals(0, harness.source.operations)
                assertEquals(0, harness.source.draws)
                assertEquals(0, harness.accent.writes)
                harness.native.mutex.unlock()

                first.await()
                duplicate.await()
                val reads = harness.source.clockReads
                val operations = harness.source.operations
                harness.start()

                assertEquals(reads, harness.source.clockReads)
                assertEquals(operations, harness.source.operations)
                assertEquals(1, harness.arms.size)
                assertEquals(SIX_HOURS / 2, harness.ticket().remainingMillis)
                assertEquals(harness.baseline.runtime.rotation, harness.snapshot().checkpoint)
                assertEquals(harness.baseline, harness.native.document())
                assertEquals(0, harness.source.draws)
                assertEquals(0, harness.accent.writes)
            }
        }

    @Test
    fun `overdue cold startup arms one due ticket and the accepted step cannot replay`(): Unit =
        runBlocking {
            AutomaticHarness(directory, coldTarget = StudioTarget.Global).use { harness ->
                harness.source.advanceTo(SIX_HOURS * 4)
                harness.start()
                val ticket = harness.ticket()
                assertEquals(0L, ticket.remainingMillis)
                assertEquals(0, harness.accent.writes)

                harness.due(ticket)
                harness.start()
                harness.due(ticket)

                assertEquals(8L, harness.snapshot().checkpoint.successfulStepSerial)
                assertEquals(1, harness.source.draws)
                assertEquals(1, harness.accent.writes)
                assertEquals(1, harness.accent.accepts)
                assertEquals(2, harness.arms.size)
                harness.assertPreferencesPreserved()
            }
        }

    @Test
    fun `recognized no history restores one step but an absent v3 checkpoint repairs only its schedule`(): Unit =
        runBlocking {
            for (noHistory in listOf(true, false)) {
                AutomaticHarness(
                    directory.resolve("history-$noHistory"),
                    coldTarget = StudioTarget.Global,
                ).use { harness ->
                    harness.replaceAccepted { document ->
                        document.copy(
                            runtime =
                                document.runtime.copy(
                                    legacy =
                                        document.runtime.legacy.copy(
                                            records =
                                                StudioList(
                                                    document.runtime.legacy.records.map { field ->
                                                        if (field.key.name == "accentRotationPresetIndex") {
                                                            field.copy(value = RawValue.Integer(Int.MIN_VALUE))
                                                        } else {
                                                            field
                                                        }
                                                    },
                                                ),
                                        ),
                                    rotation =
                                        if (noHistory) {
                                            assertNotNull(document.runtime.rotation).copy(
                                                sequenceCursorRaw = Int.MIN_VALUE,
                                                successfulStepSerial = 0,
                                                schedule = null,
                                                resume = RotationResume.NoHistory,
                                            )
                                        } else {
                                            null
                                        },
                                ),
                        )
                    }
                    val before = harness.native.document()
                    val started = harness.start()
                    harness.start()
                    val checkpoint = harness.snapshot().checkpoint

                    assertEquals(if (noHistory) 1 else 0, harness.accent.writes)
                    assertEquals(if (noHistory) 1 else 0, harness.source.draws)
                    assertEquals(if (noHistory) 1L else 0L, checkpoint.successfulStepSerial)
                    assertEquals(Int.MIN_VALUE, checkpoint.sequenceCursorRaw)
                    assertEquals(SIX_HOURS, harness.ticket().remainingMillis)
                    assertEquals(1, harness.arms.size)
                    assertEquals(before.currents, harness.native.document().currents)
                    assertEquals(
                        before.runtime.legacy,
                        harness.native
                            .document()
                            .runtime.legacy,
                    )
                    if (!noHistory) assertTrue(started.commands.any { it is RotationCommand.ReportFault })
                }
            }
        }

    @Test
    fun `cold journal settles its original owner before a new focused project gets a full interval`(): Unit =
        runBlocking {
            AutomaticHarness(directory).use { harness ->
                val target = StudioTarget.Project(StudioProjectKey("next-project"))
                harness.replaceAccepted { document ->
                    document.copy(
                        currents =
                            document.currents.copy(
                                projects =
                                    StudioList(
                                        listOf(
                                            StudioProjectRecord(
                                                target.key,
                                                "Next",
                                                document.currents.global.copy(revision = 23),
                                            ),
                                        ),
                                    ),
                            ),
                    )
                }
                harness.accent.afterApply = {
                    harness.native.files.arm(
                        IoEvent(IoOperation.REPLACE, "state.xml", IoBoundary.AFTER),
                        IoEvent(IoOperation.READ, "state.xml", IoBoundary.BEFORE),
                        IoEvent(IoOperation.READ, "state.xml", IoBoundary.BEFORE),
                    )
                }
                harness.due(harness.ticket())
                assertIs<StoreLoad.Recovering>(harness.native.store.load())
                harness.source.advanceTo(2 * SIX_HOURS)
                harness.restartCoordinators(cold = true, focus = target)

                harness.start()
                harness.start()

                val checkpoint = harness.snapshot().checkpoint
                assertEquals(RuntimeOwner(target, 23), checkpoint.owner)
                assertEquals(8L, checkpoint.successfulStepSerial)
                assertEquals(3, checkpoint.sequenceCursorRaw)
                assertNull(checkpoint.output)
                assertEquals(2 * SIX_HOURS, checkpoint.schedule?.scheduledAtEpochMillis)
                assertEquals(3 * SIX_HOURS, checkpoint.schedule?.deadlineEpochMillis)
                assertEquals(SIX_HOURS, harness.ticket().remainingMillis)
                assertEquals(1, harness.accent.writes)
                assertEquals(1, harness.accent.accepts)
                assertEquals(1, harness.source.draws)
                assertEquals(1, harness.arms.size)
                assertIs<StoreLoad.Ready>(harness.native.store.load())
            }
        }

    @Test
    fun `cold start projects the actual accepted project and a missing project never falls back to Global`(): Unit =
        runBlocking {
            val target = StudioTarget.Project(StudioProjectKey("focused-project"))
            for (present in listOf(true, false)) {
                AutomaticHarness(directory.resolve("project-$present"), coldTarget = target).use { harness ->
                    harness.source.advanceTo(SIX_HOURS / 2)
                    harness.replaceAccepted { document ->
                        val global = document.currents.global
                        val current =
                            global.copy(
                                revision = 23,
                                appearance =
                                    global.appearance.copy(
                                        accent =
                                            global.appearance.accent.copy(
                                                records =
                                                    StudioList(
                                                        global.appearance.accent.records.map { field ->
                                                            if (field.key.name == "accentRotationMode") {
                                                                field.copy(value = RawValue.Text("PRESET"))
                                                            } else {
                                                                field
                                                            }
                                                        },
                                                    ),
                                            ),
                                    ),
                            )
                        document.copy(
                            currents =
                                document.currents.copy(
                                    projects =
                                        StudioList(
                                            if (present) {
                                                listOf(StudioProjectRecord(target.key, "Focused", current))
                                            } else {
                                                emptyList()
                                            },
                                        ),
                                ),
                            runtime =
                                document.runtime.copy(
                                    rotation =
                                        assertNotNull(document.runtime.rotation).copy(
                                            owner = RuntimeOwner(target, current.revision),
                                        ),
                                ),
                        )
                    }
                    val started = harness.start()
                    if (present) {
                        assertEquals(RuntimeOwner(target, 23), harness.snapshot().inputs.owner)
                        assertEquals(
                            harness.native
                                .document()
                                .currents.projects
                                .single()
                                .current.appearance,
                            harness.snapshot().inputs.appearance,
                        )
                        assertEquals(SIX_HOURS / 2, harness.ticket().remainingMillis)
                    } else {
                        assertIs<RotationRuntimeState.Faulted.Unloaded>(harness.runtime.state)
                        assertTrue(started.commands.any { it is RotationCommand.ReportFault })
                        assertTrue(harness.arms.isEmpty())
                    }
                    assertEquals(0, harness.source.draws)
                    assertEquals(0, harness.accent.writes)
                }
            }
        }
}

class RotationRuntimeIntegrationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `busy admission coalesces duplicate due tickets and rechecks focus before random or setters`(): Unit =
        runBlocking {
            AutomaticHarness(directory).use { harness ->
                val due = harness.ticket()
                harness.native.mutex.lock()
                val first = async(start = CoroutineStart.UNDISPATCHED) { harness.due(due) }
                val duplicate = async(start = CoroutineStart.UNDISPATCHED) { harness.due(due) }
                assertTrue(duplicate.isCompleted)
                assertEquals(0, harness.source.draws)
                assertEquals(0, harness.source.clockReads)
                assertEquals(0, harness.source.operations)
                assertEquals(0, harness.source.transactions)
                assertEquals(0, harness.accent.writes)
                harness.runtime.observeFocus(FocusSnapshot(StudioTarget.Project(StudioProjectKey("other")), 2))
                harness.native.mutex.unlock()

                assertTrue(first.await().commands.isEmpty())
                assertTrue(duplicate.await().commands.isEmpty())
                assertEquals(harness.baseline, harness.native.document())
                assertEquals(0, harness.source.draws)
                assertEquals(0, harness.accent.writes)
                harness.runtime.observeFocus(FocusSnapshot(StudioTarget.Global, 3))
                assertTrue(harness.due(due).commands.isEmpty())
                assertEquals(0, harness.source.draws)
                harness.focus(StudioTarget.Global, 3)
                val contextualWrites = harness.accent.writes
                val contextualArms = harness.arms.size

                harness.due(harness.ticket())

                assertEquals(1, harness.source.draws)
                assertEquals(contextualWrites + 1, harness.accent.writes)
                assertEquals(contextualArms + 1, harness.arms.size)
            }
        }

    @Test
    fun `automatic step journals its exact choice before setters and schedules from actual completion`(): Unit =
        runBlocking {
            AutomaticHarness(directory).use { harness ->
                val due = harness.ticket()
                harness.accent.beforePrepare = {
                    val journal = assertIs<StoreLoad.Recovering>(harness.native.store.load()).journal
                    val frozen = assertNotNull(journal.recovery.rotationStep)
                    val purpose = assertIs<RecoveryPurpose.RotationStep>(journal.recovery.purpose)
                    assertEquals("automatic-runtime-1", journal.token.transactionId)
                    assertEquals(harness.source.firstOperation, purpose.operationId)
                    assertEquals(purpose.operationId, frozen.operationId)
                    assertEquals("#ABC123", frozen.hex)
                    assertEquals(
                        7L,
                        journal.candidate.runtime.rotation
                            ?.successfulStepSerial,
                    )
                    assertNull(
                        journal.candidate.runtime.rotation
                            ?.retryCandidate,
                    )
                    assertEquals(0, harness.accent.writes)
                }
                val completed = SIX_HOURS + TimeUnit.MINUTES.toMillis(7)
                harness.accent.afterApply = { harness.source.advanceTo(completed) }

                harness.due(due)
                harness.due(due)

                val checkpoint =
                    assertNotNull(
                        harness.native
                            .document()
                            .runtime.rotation,
                    )
                assertEquals(3, checkpoint.sequenceCursorRaw)
                assertEquals(8L, checkpoint.successfulStepSerial)
                assertEquals("#ABC123", checkpoint.output?.hex)
                assertEquals(completed, checkpoint.lastSuccessfulStepEpochMillis)
                assertEquals(completed + SIX_HOURS, checkpoint.schedule?.deadlineEpochMillis)
                assertEquals(1, harness.source.draws)
                assertEquals(1, harness.accent.writes)
                assertEquals(1, harness.accent.accepts)
                assertEquals(1, harness.arms.size)
                assertEquals(0, harness.native.flushes)
                assertNull(
                    harness.outcome(
                        harness.native
                            .document()
                            .header.transactionId,
                    ),
                )
                harness.assertPreferencesPreserved()
                assertIs<StoreLoad.Ready>(harness.native.store.load())
            }
        }

    @Test
    fun `restored automatic retries keep one frozen random choice and fault after the existing failure budget`(): Unit =
        runBlocking {
            AutomaticHarness(directory).use { harness ->
                harness.accent.failAfter = true
                val attempts = mutableListOf<RecoveryPurpose.RotationStep>()
                val choices = mutableListOf<RotationCandidate>()
                harness.accent.beforePrepare = {
                    val journal = assertIs<StoreLoad.Recovering>(harness.native.store.load()).journal
                    attempts += assertIs<RecoveryPurpose.RotationStep>(journal.recovery.purpose)
                    choices += assertNotNull(journal.recovery.rotationStep)
                }

                harness.due(harness.ticket())
                val retry =
                    assertNotNull(
                        harness.native
                            .document()
                            .runtime.rotation
                            ?.retryCandidate,
                    )
                assertEquals(choices.single(), retry)
                val attempted =
                    harness.native.updates
                        .filterIsInstance<StudioApplyUpdate.Journal>()
                        .first { it.result == SessionJournalEvent.Prepared }
                        .operationId
                assertNull(harness.outcome(attempted))
                assertNull(
                    harness.outcome(
                        harness.native
                            .document()
                            .header.transactionId,
                    ),
                )
                assertEquals(0, harness.accent.value)
                harness.nextDue()
                assertEquals(0, harness.accent.value)
                harness.nextDue()

                val faulted = assertIs<RotationRuntimeState.Faulted.Loaded>(harness.runtime.state)
                assertEquals(RuntimeFault.SessionCircuitBreaker, faulted.fault)
                assertEquals(1, harness.source.draws)
                assertEquals(3, harness.accent.writes)
                assertEquals(1, choices.distinct().size)
                assertEquals(3, attempts.map { it.operationId }.distinct().size)
                assertEquals(
                    7L,
                    harness.native
                        .document()
                        .runtime.rotation
                        ?.successfulStepSerial,
                )
                assertEquals(
                    3,
                    harness.native
                        .document()
                        .runtime.rotation
                        ?.sequenceCursorRaw,
                )
                assertEquals(2, harness.arms.size)
                assertEquals(0, harness.accent.value)
                harness.assertPreferencesPreserved()
                assertIs<StoreLoad.Ready>(harness.native.store.load())
            }
        }

    @Test
    fun `lost commit acknowledgement survives coordinator restart and settles without another step`(): Unit =
        runBlocking {
            AutomaticHarness(directory).use { harness ->
                harness.accent.afterApply = {
                    harness.native.files.arm(
                        IoEvent(IoOperation.REPLACE, "state.xml", IoBoundary.AFTER),
                        IoEvent(IoOperation.READ, "state.xml", IoBoundary.BEFORE),
                        IoEvent(IoOperation.READ, "state.xml", IoBoundary.BEFORE),
                    )
                }

                harness.due(harness.ticket())

                assertIs<RotationRuntimeState.Faulted.Loaded>(harness.runtime.state)
                assertTrue(harness.arms.isEmpty())
                val journal = assertIs<StoreLoad.Recovering>(harness.native.store.load()).journal
                val frozen = assertNotNull(journal.recovery.rotationStep)
                assertIs<AcceptanceResult.RecoveryRequired>(harness.outcome(journal.token.transactionId))
                val decoded =
                    assertIs<JournalDecodeResult.Ready>(StudioJournalCodec.decode(StudioJournalCodec.encode(journal)))
                assertEquals(journal, decoded.journal)
                assertIs<RecoveryPurpose.RotationStep>(decoded.journal.recovery.purpose)
                harness.restartCoordinators(cold = true)
                assertIs<RotationRuntimeState.Loading>(harness.runtime.state)

                harness.start()
                harness.start()

                val checkpoint =
                    assertNotNull(
                        harness.native
                            .document()
                            .runtime.rotation,
                    )
                assertEquals(8L, checkpoint.successfulStepSerial)
                assertEquals(frozen.hex, checkpoint.output?.hex)
                assertEquals(1, harness.source.draws)
                assertEquals(1, harness.accent.writes)
                assertEquals(1, harness.accent.accepts)
                assertEquals(1, harness.arms.size)
                assertTrue(harness.native.events.none { it.startsWith("restore-") })
                assertIs<StoreLoad.Ready>(harness.native.store.load())
            }
        }

    @Test
    fun `accepted cleanup failure keeps the durable step and retries only cleanup before arming`(): Unit =
        runBlocking {
            AutomaticHarness(directory).use { harness ->
                harness.native.beforePublish = { update ->
                    if (update is StudioApplyUpdate.Committed) {
                        harness.native.files.arm(IoEvent(IoOperation.REMOVE, "pending.xml", IoBoundary.BEFORE))
                    }
                }

                harness.due(harness.ticket())

                val accepted = harness.native.document()
                assertIs<AcceptanceResult.AcceptedCleanupPending>(harness.outcome(accepted.header.transactionId))
                assertEquals(
                    1,
                    harness.native.updates
                        .filterIsInstance<StudioApplyUpdate.Committed>()
                        .size,
                )
                assertEquals(8L, accepted.runtime.rotation?.successfulStepSerial)
                assertIs<RotationRuntimeState.Faulted.Loaded>(harness.runtime.state)
                assertTrue(harness.arms.isEmpty())
                assertIs<StoreLoad.Recovering>(harness.native.store.load())
                harness.native.beforePublish = null

                harness.recover()
                harness.recover()

                assertEquals(accepted, harness.native.document())
                assertEquals(
                    1,
                    harness.native.updates
                        .filterIsInstance<StudioApplyUpdate.Committed>()
                        .size,
                )
                assertNull(harness.outcome(accepted.header.transactionId))
                assertEquals(1, harness.source.draws)
                assertEquals(1, harness.accent.writes)
                assertEquals(1, harness.accent.accepts)
                assertEquals(1, harness.arms.size)
                assertIs<StoreLoad.Ready>(harness.native.store.load())
            }
        }

    @Test
    fun `unfinished rollback preserves a foreign write and cancellation until explicit shared recovery`(): Unit =
        runBlocking {
            for (cancelled in listOf(false, true)) {
                AutomaticHarness(directory.resolve(if (cancelled) "cancelled" else "failed")).use { harness ->
                    val stale = harness.ticket()
                    harness.accent.failAfter = true
                    harness.accent.failRestore = true
                    harness.accent.afterApply = {
                        harness.accent.value = 99
                        if (cancelled) throw CancellationException("cancel automatic effects")
                    }

                    if (cancelled) {
                        assertFailsWith<CancellationException> { harness.due(stale) }
                    } else {
                        harness.due(stale)
                    }

                    assertEquals(99, harness.accent.value)
                    assertIs<RotationRuntimeState.Faulted.Loaded>(harness.runtime.state)
                    assertIs<StoreLoad.Recovering>(harness.native.store.load())
                    assertTrue(harness.arms.isEmpty())
                    harness.due(stale)
                    assertEquals(1, harness.source.draws)
                    assertEquals(1, harness.accent.writes)
                    harness.accent.failRestore = false
                    harness.accent.failAfter = false
                    harness.accent.afterApply = null

                    harness.recover()

                    assertEquals(99, harness.accent.value)
                    assertEquals(
                        7L,
                        harness.native
                            .document()
                            .runtime.rotation
                            ?.successfulStepSerial,
                    )
                    assertNotNull(
                        harness.native
                            .document()
                            .runtime.rotation
                            ?.retryCandidate,
                    )
                    assertEquals(1, harness.arms.size)
                    harness.assertPreferencesPreserved()
                    assertIs<StoreLoad.Ready>(harness.native.store.load())
                }
            }
        }

    @Test
    fun `schedule-only journals round trip their purpose and older journals remain user acceptance`(): Unit =
        runBlocking {
            AutomaticHarness(directory).use { harness ->
                val captured = mutableListOf<PendingJournal>()
                harness.native.beforePublish = { update ->
                    if (update is StudioApplyUpdate.Journal && update.result == SessionJournalEvent.Prepared) {
                        captured += assertIs<StoreLoad.Recovering>(harness.native.store.load()).journal
                    }
                }
                val availability = harness.snapshot().inputs.availability

                harness.observe(1, availability.copy(effectiveFollowSystem = true))
                harness.observe(2, availability)

                assertEquals(2, captured.size)
                for (pending in captured) {
                    assertIs<RecoveryPurpose.RotationSchedule>(pending.recovery.purpose)
                    assertNull(pending.recovery.rotationStep)
                    assertTrue(pending.recovery.owners.isEmpty())
                    val decoded =
                        assertIs<JournalDecodeResult.Ready>(
                            StudioJournalCodec.decode(StudioJournalCodec.encode(pending)),
                        )
                    assertEquals(pending, decoded.journal)
                }
                val before =
                    captured.first().copy(
                        recovery = captured.first().recovery.copy(purpose = RecoveryPurpose.UserAcceptance),
                    )
                val envelope = StudioXml.parse(StudioJournalCodec.encode(before))
                val compressed = assertNotNull(StudioXml.readCheckedPayload(envelope, encoding = "gzip-base64")).bytes
                val payload =
                    withContext(harness.ioContext) {
                        GZIPInputStream(compressed.inputStream()).use { stream ->
                            StudioXml.parse(stream.readBytes())
                        }
                    }
                val oldPayload =
                    payload.copy(
                        children =
                            payload.children.map { child ->
                                if (child.name == "recovery") {
                                    child.copy(children = child.children.filterNot { it.name == "runtime-purpose" })
                                } else {
                                    child
                                }
                            },
                    )
                val oldBytes =
                    StudioXml.write(StudioXml.envelope("journal", envelope.attributes + ("schema" to "1"), oldPayload))
                val legacy = assertIs<JournalDecodeResult.Ready>(StudioJournalCodec.decode(oldBytes))
                assertEquals(before, legacy.journal)
                assertEquals(0, harness.source.draws)
                assertEquals(0, harness.accent.writes)
                assertEquals(0, harness.native.flushes)
                assertTrue(
                    harness.native.updates.none {
                        it is StudioApplyUpdate.Effect &&
                            it.result is SessionEffect.PreflightCompleted
                    },
                )
                assertEquals(
                    7L,
                    harness.native
                        .document()
                        .runtime.rotation
                        ?.successfulStepSerial,
                )
                assertEquals(
                    2 * SIX_HOURS,
                    harness.native
                        .document()
                        .runtime.rotation
                        ?.schedule
                        ?.deadlineEpochMillis,
                )
                assertEquals(1, harness.arms.size)
                harness.assertPreferencesPreserved()
            }
        }
}

private class AutomaticHarness(
    directory: Path,
    val ioContext: CoroutineContext = Dispatchers.IO,
    coldTarget: StudioTarget? = null,
    initialTime: Long = SIX_HOURS,
) : AutoCloseable {
    val native = StudioApplyFixture(directory)
    val source = AutomaticSources(initialTime)
    val baseline = seedRotation()
    val accent get() = native.effect(EffectOwner.ACCENT)
    val arms = mutableListOf<RotationTicket>()
    var runtime = coldTarget?.let { StudioRuntimeCoordinator(focus = FocusSnapshot(it, 1)) } ?: runtimeFor(baseline)
        private set
    private var environment = AutomaticEnvironment(native, runtime)
    private var apply = StudioApplyCoordinator(native.store, environment, native.effects, native.mutex)

    fun binding(
        scope: CoroutineScope,
        host: StudioNativeHost,
        scheduler: SessionScheduler,
    ): StudioRuntimeBinding =
        StudioRuntimeBinding(
            runtime,
            apply,
            source,
            {
                val observed = host.observe()
                StudioRuntimeObservation(
                    observed,
                    RuntimeAvailability(
                        lastDefinitivePro = observed.entitlement == LicenseEntitlement.LICENSED,
                        effectiveFollowSystem = false,
                        variant = observed.variant,
                        targetValid = true,
                        recoveryPending = false,
                    ),
                )
            },
            native::document,
            scope,
            scheduler,
            { fault -> native.events.add("runtime-fault:$fault") },
        )

    fun snapshot(): RotationSnapshot = assertIs<RotationRuntimeState.Loaded>(runtime.state).snapshot

    fun ticket(): RotationTicket = assertIs<RotationRuntimeState.Waiting>(runtime.state).ticket

    suspend fun nativeChange(change: StudioNativeChange): RotationTransition =
        execute(
            RuntimeDispatch.NativeChange(
                FocusSnapshot(snapshot().inputs.owner.target, snapshot().focusGeneration.coerceAtLeast(1)),
                change,
            ),
        )

    suspend fun due(appointment: RotationTicket): RotationTransition = execute(RuntimeDispatch.Due(appointment))

    suspend fun recover(): RotationTransition = execute(RuntimeDispatch.Recover)

    suspend fun continuity(
        generation: Long,
        accent: dev.ayuislands.studio.model.StudioAccentContinuation?,
    ): RotationTransition {
        val focused = FocusSnapshot(StudioTarget.Global, generation)
        runtime.observeFocus(focused)
        return execute(
            RuntimeDispatch.ContinuityTransfer(
                focused,
                RuntimeAvailability(
                    lastDefinitivePro = true,
                    effectiveFollowSystem = false,
                    variant = AyuVariant.MIRAGE,
                    targetValid = true,
                    recoveryPending = false,
                ),
                accent,
            ),
        )
    }

    suspend fun focus(
        target: StudioTarget,
        generation: Long,
    ): RotationTransition {
        val focused = FocusSnapshot(target, generation)
        runtime.observeFocus(focused)
        return execute(
            RuntimeDispatch.FocusChanged(
                focused,
                RuntimeAvailability(
                    lastDefinitivePro = true,
                    effectiveFollowSystem = false,
                    variant = AyuVariant.MIRAGE,
                    targetValid = true,
                    recoveryPending = false,
                ),
            ),
        )
    }

    fun addProject(target: StudioTarget.Project) {
        replaceAccepted { document ->
            val global = document.currents.global
            val selected =
                global.copy(
                    revision = 23,
                    appearance =
                        global.appearance.copy(
                            accent =
                                global.appearance.accent.copy(
                                    records =
                                        StudioList(
                                            global.appearance.accent.records.map { field ->
                                                if (field.key.name == "accentRotationIntervalHours") {
                                                    field.copy(value = RawValue.Integer(1))
                                                } else {
                                                    field
                                                }
                                            },
                                        ),
                                ),
                        ),
                )
            document.copy(
                currents =
                    document.currents.copy(
                        projects =
                            StudioList(
                                document.currents.projects + StudioProjectRecord(target.key, "Incoming", selected),
                            ),
                    ),
            )
        }
    }

    suspend fun start(): RotationTransition =
        execute(
            RuntimeDispatch.Start(
                1,
                RuntimeAvailability(
                    lastDefinitivePro = true,
                    effectiveFollowSystem = false,
                    variant = AyuVariant.MIRAGE,
                    targetValid = true,
                    recoveryPending = false,
                ),
            ),
        )

    fun replaceAccepted(change: (StudioDocument) -> StudioDocument) {
        val latest = native.document()
        val candidate =
            change(latest).let { changed ->
                changed.copy(
                    header =
                        changed.header.copy(
                            revision = latest.header.revision + 1,
                            transactionId = "seed-${latest.header.revision + 1}",
                        ),
                )
            }
        val token =
            assertIs<PrepareResult.Prepared>(
                native.store.prepare(
                    latest.header.revision,
                    candidate,
                    RecoveryPlan(StudioTarget.Global, StudioList(emptyList())),
                ),
            ).token
        assertIs<CommitResult.Committed>(native.store.commit(token, candidate.runtime))
        assertEquals(JournalResult.Acknowledged, native.store.finishAccepted(token))
    }

    fun outcome(transactionId: String): AcceptanceResult? = apply.outcome(transactionId)

    suspend fun observe(
        generation: Long,
        availability: RuntimeAvailability,
    ): RotationTransition = execute(RuntimeDispatch.Availability(generation, availability))

    suspend fun nextDue(): RotationTransition {
        val waiting = ticket()
        source.advanceTo(source.now.epochMillis + waiting.remainingMillis)
        return due(waiting)
    }

    fun restartCoordinators(
        cold: Boolean = false,
        focus: StudioTarget = StudioTarget.Global,
    ) {
        runtime = if (cold) StudioRuntimeCoordinator(focus = FocusSnapshot(focus, 1)) else runtimeFor(native.document())
        environment = AutomaticEnvironment(native, runtime)
        apply = StudioApplyCoordinator(native.store, environment, native.effects, native.mutex)
    }

    fun assertPreferencesPreserved() {
        val accepted = native.document()
        assertEquals(baseline.currents, accepted.currents)
        assertEquals(baseline.catalog, accepted.catalog)
        assertEquals(baseline.shared, accepted.shared)
        assertEquals(baseline.auxiliary, accepted.auxiliary)
        assertEquals(baseline.sessions, accepted.sessions)
        assertEquals(baseline.extensions, accepted.extensions)
        assertEquals(baseline.runtime.legacy, accepted.runtime.legacy)
    }

    private suspend fun execute(dispatch: RuntimeDispatch): RotationTransition =
        apply.executeRuntime(runtime, dispatch, source).also { result ->
            arms += result.commands.filterIsInstance<RotationCommand.ArmSchedule>().map { it.ticket }
        }

    private fun runtimeFor(document: StudioDocument): StudioRuntimeCoordinator {
        val checkpoint = assertNotNull(document.runtime.rotation)
        val inputs =
            RuntimeInputs(
                checkpoint.owner,
                document.currents.global.appearance,
                RuntimeAvailability(
                    lastDefinitivePro = true,
                    effectiveFollowSystem = false,
                    variant = AyuVariant.MIRAGE,
                    targetValid = true,
                    recoveryPending = false,
                ),
            )
        val snapshot = RotationSnapshot(checkpoint, inputs)
        val schedule = assertNotNull(checkpoint.schedule)
        val remaining = (schedule.deadlineEpochMillis - source.now.epochMillis).coerceAtLeast(0)
        val waiting =
            RotationRuntimeState.Waiting(
                snapshot,
                RotationTicket(
                    checkpoint.owner,
                    schedule.scheduleSerial,
                    remaining,
                    source.now.monotonicNanos,
                    remaining,
                ),
            )
        return StudioRuntimeCoordinator(waiting, FocusSnapshot(StudioTarget.Global, 1))
    }

    private fun seedRotation(): StudioDocument {
        val original = native.initial
        val record = sessionRecord(original)
        val selected = record.work.drafts.single { it.id == record.work.selected }
        val fields =
            listOf(
                "accentRotationEnabled" to RawValue.BooleanValue(true),
                "accentRotationMode" to RawValue.Text("RANDOM"),
                "accentRotationIntervalHours" to RawValue.Integer(6),
            )
        val edited =
            fields.fold(selected) { draft, (name, value) ->
                StudioDraftPolicy.edit(
                    draft,
                    FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, name)),
                    FieldValue.Present(value),
                )
            }
        val current = original.currents.global.copy(appearance = edited.appearance, revision = 1)
        val checkpoint =
            RotationRuntimeCheckpoint(
                RuntimeOwner(StudioTarget.Global, current.revision),
                3,
                7,
                schedule = RotationSchedule(0, SIX_HOURS, SIX_HOURS, 5),
            )
        val candidate =
            original.copy(
                header = original.header.copy(revision = 1, transactionId = "seed-automatic-runtime"),
                currents = original.currents.copy(global = current),
                runtime = original.runtime.copy(rotation = checkpoint),
            )
        val token =
            assertIs<PrepareResult.Prepared>(
                native.store.prepare(0, candidate, RecoveryPlan(StudioTarget.Global, StudioList(emptyList()))),
            ).token
        assertIs<CommitResult.Committed>(native.store.commit(token, candidate.runtime))
        assertEquals(JournalResult.Acknowledged, native.store.finishAccepted(token))
        return candidate
    }

    override fun close() = native.close()
}

private class AutomaticEnvironment(
    private val delegate: StudioApplyEnvironment,
    private val runtime: StudioRuntimeCoordinator,
) : StudioApplyEnvironment by delegate {
    override suspend fun acquireRuntime(
        operationId: UUID,
        snapshot: RotationSnapshot,
        plan: AcceptancePlan.Commit,
    ): StudioAcceptancePreparation =
        when (val acquired = delegate.acquireRuntime(operationId, snapshot, plan)) {
            is StudioAcceptancePreparation.Blocked -> {
                acquired
            }

            is StudioAcceptancePreparation.Prepared -> {
                acquired.copy(
                    lease =
                        object : StudioRuntimeLease by acquired.lease {
                            override suspend fun isValid(): Boolean =
                                acquired.lease.isValid() && runtime.isCurrentOwner(snapshot.inputs.owner)
                        },
                )
            }
        }
}

private class AutomaticSources(
    initialTime: Long,
) : StudioRuntimeSource {
    var now = ClockSample(initialTime, TimeUnit.MILLISECONDS.toNanos(initialTime))
        private set
    var draws = 0
        private set
    var clockReads = 0
        private set
    var operations = 0
        private set
    var transactions = 0
        private set
    val firstOperation = UUID(0, 1)

    override fun clock(): ClockSample {
        clockReads++
        return now
    }

    override fun operationId(): UUID = UUID(0, (++operations).toLong())

    override fun transactionId(): String = "automatic-runtime-${++transactions}"

    override fun randomColor(variant: AyuVariant): String {
        assertEquals(AyuVariant.MIRAGE, variant)
        draws++
        return if (draws == 1) "#ABC123" else "#DEF456"
    }

    fun advanceTo(epochMillis: Long) {
        now =
            ClockSample(epochMillis, now.monotonicNanos + TimeUnit.MILLISECONDS.toNanos(epochMillis - now.epochMillis))
    }
}

private val SIX_HOURS = TimeUnit.HOURS.toMillis(6)
