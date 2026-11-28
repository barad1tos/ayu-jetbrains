package dev.ayuislands.studio

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import dev.ayuislands.glow.FocusRingManager
import dev.ayuislands.glow.GlowAnimation
import dev.ayuislands.glow.GlowAnimator
import dev.ayuislands.glow.GlowFocusListener
import dev.ayuislands.glow.GlowGlassPane
import dev.ayuislands.glow.GlowOverlayAddition
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.glow.GlowPaneAppearance
import dev.ayuislands.glow.GlowPaneField
import dev.ayuislands.glow.GlowPlacement
import dev.ayuislands.glow.GlowShape
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.glow.waveform.WaveformBaseline
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.glow.waveform.WaveformMovement
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.GlowSettings
import dev.ayuislands.studio.model.AcceptanceObservation
import dev.ayuislands.studio.model.ApplyContext
import dev.ayuislands.studio.model.ApplyRequest
import dev.ayuislands.studio.model.AuthorityStatus
import dev.ayuislands.studio.model.EffectObservation
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.GlowDependency
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioGlowField
import dev.ayuislands.studio.model.StudioGlowSelection
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.model.StudioRuntimeState
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.TargetObservation
import dev.ayuislands.studio.model.TargetPresence
import dev.ayuislands.studio.persistence.CommitResolution
import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.NativeValue
import dev.ayuislands.studio.persistence.PendingJournal
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.persistence.StoreIssue
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioDraftStore
import dev.ayuislands.studio.persistence.StudioFiles
import dev.ayuislands.studio.persistence.openStudioFiles
import dev.ayuislands.studio.session.ApplyExecution
import dev.ayuislands.studio.session.ApplyFailure
import dev.ayuislands.studio.session.ApplyInputs
import dev.ayuislands.studio.session.ApplySettlement
import dev.ayuislands.studio.session.OwnerAcceptance
import dev.ayuislands.studio.session.OwnerApply
import dev.ayuislands.studio.session.OwnerRollback
import dev.ayuislands.studio.session.RestoreContinuation
import dev.ayuislands.studio.session.SettlementProblem
import dev.ayuislands.studio.session.StudioApplyCoordinator
import dev.ayuislands.studio.session.StudioApplyLease
import dev.ayuislands.studio.session.StudioApplySettlement
import dev.ayuislands.studio.session.StudioDraftPolicy
import dev.ayuislands.studio.session.StudioGlowApplyLease
import dev.ayuislands.studio.session.StudioGlowFocusOperation
import dev.ayuislands.studio.session.StudioGlowHolds
import dev.ayuislands.studio.session.StudioGlowOperation
import dev.ayuislands.studio.session.StudioGlowPaneOperation
import dev.ayuislands.studio.session.StudioGlowPlanning
import dev.ayuislands.studio.session.StudioGlowTarget
import dev.ayuislands.studio.session.StudioNativeEvidence
import dev.ayuislands.studio.session.StudioNativeWrite
import dev.ayuislands.studio.session.StudioOwnerJournal
import dev.ayuislands.studio.session.StudioOwnerOperation
import dev.ayuislands.studio.session.StudioOwnerWork
import dev.ayuislands.studio.session.Submission
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.Component
import java.awt.DefaultKeyboardFocusManager
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.ComponentListener
import java.awt.event.ContainerAdapter
import java.awt.event.ContainerEvent
import java.awt.event.HierarchyBoundsListener
import java.io.IOException
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.JRootPane
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioOwnerJournalTest {
    @TempDir
    lateinit var directory: Path

    private val ioDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @AfterTest
    fun closeDispatcher() = ioDispatcher.close()

    @Test
    fun `Apply retries Glow refresh with the committed document and preserves the peer pane`() =
        runBlocking { verifyGlowApply(accept = true) }

    @Test
    fun `Apply restores Glow membership before retrying a rollback refresh`() =
        runBlocking { verifyGlowApply(accept = false) }

    @Test
    fun `Glow adds a qualified pane after journaling and keeps its registration after commit`() =
        runBlocking { verifyGlowAddition(GlowAdditionOutcome.ACCEPTED) }

    @Test
    fun `Glow rolls back an insertion that threw after Swing attached the pane`() =
        runBlocking { verifyGlowAddition(GlowAdditionOutcome.INTERRUPTED) }

    @Test
    fun `Glow releases its listeners without detaching a pane another parent took over`() =
        runBlocking { verifyGlowAddition(GlowAdditionOutcome.REPARENTED) }

    @Test
    fun `committed Glow waits for its pane and exact listeners before releasing the hold`() =
        runBlocking { verifyGlowMountAcceptance() }

    @Test
    fun `Glow retirement preserves attached membership when Apply is cancelled`() =
        runBlocking { verifyGlowRetirement(GlowRetirementOutcome.ABORTED) }

    @Test
    fun `cancelling Glow retirement keeps an already hidden pane hidden`() =
        runBlocking { verifyGlowRetirement(GlowRetirementOutcome.HIDDEN) }

    @Test
    fun `Glow retires exact membership only once Apply is committed`() =
        runBlocking { verifyGlowRetirement(GlowRetirementOutcome.ACCEPTED) }

    @Test
    fun `Glow retirement releases its registration after another parent takes the pane`() =
        runBlocking { verifyGlowRetirement(GlowRetirementOutcome.REPARENTED) }

    @Test
    fun `cancelling Glow retirement leaves a reparented pane untouched`() =
        runBlocking { verifyGlowRetirement(GlowRetirementOutcome.MOVED_BEFORE_CANCEL) }

    @Test
    fun `cancelling Glow retirement restores visibility after its host changes roots`() =
        runBlocking { verifyGlowRetirement(GlowRetirementOutcome.HOST_MOVED_BEFORE_CANCEL) }

    @Test
    fun `accepting Glow retirement preserves a pane made visible by another writer`() =
        runBlocking { verifyGlowRetirement(GlowRetirementOutcome.RESHOWN) }

    @Test
    fun `closing a project settles its cancelled Glow retirement`() =
        runBlocking { verifyGlowRetirement(GlowRetirementOutcome.CLOSED_BEFORE_CANCEL) }

    @Test
    fun `closing a project settles its confirmed Glow retirement`() =
        runBlocking { verifyGlowRetirement(GlowRetirementOutcome.CLOSED_BEFORE_COMMIT) }

    @Test
    fun `accepting active retirement stops playback and updates focus to the surviving pane`() =
        runBlocking { verifyRetirementPlayback(accept = true) }

    @Test
    fun `cancelling active retirement resumes playback on the same pane`() =
        runBlocking { verifyRetirementPlayback(accept = false) }

    @Test
    fun `one Glow owner restores focus before pane playback after an interrupted Apply`() =
        runBlocking { verifyCombinedGlow(accept = false) }

    @Test
    fun `one Glow owner keeps the accepted pane and focus appearance after release`() =
        runBlocking { verifyCombinedGlow(accept = true) }

    @Test
    fun `shared Glow journals one border and writes no restoration before its evidence is durable`() =
        runBlocking {
            withFocusHolds { field, holds ->
                val files = FailingJournalFiles(openStudioFiles(directory))
                StudioAppearanceStore(files).use { store ->
                    val work = StudioOwnerWork(prepare(store, catalogTestDocument(), listOf(EffectOwner.GLOW)))
                    val runner = ownerJournal(store)
                    val original = withContext(glowEdt) { field.border }
                    val previousColors = withContext(glowEdt) { field.currentColors() }
                    var qualifications = 0

                    val applied =
                        runner.apply(
                            work,
                            qualifies = {
                                if (++qualifications == 2) {
                                    val receipts = withContext(ioDispatcher) { journalReceipts(store) }
                                    assertEquals(3, receipts.size)
                                    assertEquals(3, receipts.map { it.identity.resource }.distinct().size)
                                    assertTrue(receipts.all { it.status == RestoreStatus.PENDING })
                                    assertSame(original, field.border)
                                }
                                true
                            },
                        ) { captureFocus(work, holds) }
                    assertEquals(OwnerApply.Completed, applied)
                    val border = journalReceipts(store).single { it.identity.field.endsWith("/BORDER") }
                    assertEquals(RestoreStatus.OWNED, border.status)
                    val installed = withContext(glowEdt) { field.border.also { field.focused = false } }

                    files.failNextReplace = true
                    assertEquals(OwnerRollback.ReceiptFailed(EffectOwner.GLOW, StoreIssue.IO), runner.rollback(work))
                    withContext(glowEdt) {
                        assertSame(installed, field.border)
                        assertTrue(holds.all { it.isActive })
                    }
                    assertEquals(OwnerRollback.Settled, runner.rollback(work))

                    val receipts = journalReceipts(store)
                    assertEquals(List(4) { RestoreStatus.RESTORED }, receipts.map { it.status })
                    val restoration = receipts.single { it.id.endsWith("/restore/0") }
                    assertEquals(border.identity, restoration.identity)
                    val evidence = StudioNativeEvidence.fields(glowEvidenceValue(restoration.before))
                    assertEquals(glowEvidenceValue(border.lastWritten), evidence["observed"])
                    assertEquals(RawValue.BooleanValue(false), evidence["focused"])
                    val originalBorder = StudioNativeEvidence.fields(glowEvidenceValue(border.before))
                    val baseline = originalBorder.getValue("baseline")
                    assertEquals(baseline, glowEvidenceValue(restoration.lastWritten))
                    withContext(glowEdt) { assertEquals(previousColors, field.currentColors()) }
                }
            }
        }

    @Test
    fun `focus moving after durable restore preparation creates new evidence on retry`() =
        runBlocking {
            withFocusHolds { field, holds ->
                val files = FailingJournalFiles(openStudioFiles(directory))
                StudioAppearanceStore(files).use { store ->
                    val work = StudioOwnerWork(prepare(store, catalogTestDocument(), listOf(EffectOwner.GLOW)))
                    val runner = ownerJournal(store)
                    val original = withContext(glowEdt) { field.border }
                    val applied = runner.apply(work, qualifies = { true }) { captureFocus(work, holds) }
                    assertEquals(OwnerApply.Completed, applied)
                    val installed = withContext(glowEdt) { field.border.also { field.focused = false } }
                    files.afterPendingReplace = { SwingUtilities.invokeAndWait { field.focused = true } }

                    assertIs<OwnerRollback.OwnerFailed>(runner.rollback(work))
                    withContext(glowEdt) { assertSame(installed, field.border) }
                    val first = journalReceipts(store).single { it.id.endsWith("/restore/0") }
                    assertEquals(RestoreStatus.PENDING, first.status)

                    assertEquals(OwnerRollback.Settled, runner.rollback(work))
                    withContext(glowEdt) { assertSame(original, field.border) }
                    val receipts = journalReceipts(store)
                    val retry = receipts.single { it.id.endsWith("/restore/1") }
                    assertEquals(first.copy(status = RestoreStatus.RESTORED), receipts.single { it.id == first.id })
                    val observed = StudioNativeEvidence.fields(glowEvidenceValue(retry.before))
                    assertEquals(RawValue.BooleanValue(true), observed["focused"])
                    assertTrue(first.lastWritten != retry.lastWritten)
                    assertEquals(RestoreStatus.RESTORED, retry.status)
                }
            }
        }

    @Test
    fun `a rejected focus receipt releases the outer scope without writing a border`() =
        runBlocking {
            withFocusHolds { field, holds ->
                val files = FailingJournalFiles(openStudioFiles(directory))
                StudioAppearanceStore(files).use { store ->
                    val work = StudioOwnerWork(prepare(store, catalogTestDocument(), listOf(EffectOwner.GLOW)))
                    val runner = ownerJournal(store)
                    val original = withContext(glowEdt) { field.border }
                    files.failNextReplace = true
                    val applied = runner.apply(work, qualifies = { true }) { captureFocus(work, holds) }
                    assertEquals(OwnerApply.ReceiptFailed(EffectOwner.GLOW, StoreIssue.IO), applied)
                    assertTrue(journalReceipts(store).isEmpty())
                    val settlement =
                        StudioApplySettlement(store, runner, ioDispatcher, work) { accepted ->
                            assertNull(accepted)
                            withContext(glowEdt) {
                                assertTrue(holds.all { it.isActive })
                                holds.asReversed().forEach { it.release() }
                            }
                        }

                    assertEquals(ApplySettlement.NotAccepted, settlement.settle())
                    withContext(glowEdt) {
                        assertSame(original, field.border)
                        assertTrue(holds.none { it.isActive })
                    }
                }
            }
        }

    @Test
    fun `accepting shared focus keeps the applied border through outer lease release`() =
        runBlocking {
            withFocusHolds { field, holds ->
                StudioAppearanceStore(openStudioFiles(directory)).use { store ->
                    val work = StudioOwnerWork(prepare(store, catalogTestDocument(), listOf(EffectOwner.GLOW)))
                    val runner = ownerJournal(store)
                    val applied = runner.apply(work, qualifies = { true }) { captureFocus(work, holds) }
                    assertEquals(OwnerApply.Completed, applied)
                    val border = withContext(glowEdt) { field.border }
                    assertIs<CommitResult.Committed>(store.commit(work.journal.token, work.journal.candidate.runtime))
                    val settled =
                        StudioApplySettlement(store, runner, ioDispatcher, work) { accepted ->
                            assertEquals(work.journal.candidate, accepted)
                            withContext(glowEdt) {
                                assertTrue(holds.all { it.isActive })
                                holds.asReversed().forEach { it.release() }
                            }
                        }

                    assertEquals(ApplySettlement.Accepted(work.journal.candidate), settled.settle())
                    withContext(glowEdt) {
                        assertTrue(holds.none { it.isActive })
                        assertSame(border, field.border)
                        assertEquals(listOf(Color.MAGENTA, Color.MAGENTA), field.currentColors())
                    }
                }
            }
        }

    @Test
    fun `Glow journals selected pane settings before the setter and preserves a foreign edit on rollback`() {
        val original = Color(0xFFCC66)
        val changed = Color(0x5CCFE6)
        val pane = glowPane(original)
        val manager = glowManager(pane)
        val initial = catalogTestDocument()
        var refreshRequests = 0
        var qualifications = 0
        val request =
            StudioGlowTarget(manager) { _, current ->
                StudioGlowPaneOperation.Edit(
                    current.copy(color = changed, width = 9, intensity = 99),
                    setOf(GlowPaneField.COLOR, GlowPaneField.WIDTH),
                )
            }
        val holds = StudioGlowHolds()
        val files = FailingJournalFiles(openStudioFiles(directory))
        StudioAppearanceStore(files).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(EffectOwner.GLOW)))
            val runner = ownerJournal(store)
            runBlocking {
                try {
                    val result =
                        runner.apply(
                            work,
                            qualifies = {
                                if (++qualifications > 1) {
                                    val persisted =
                                        withContext(ioDispatcher) { assertIs<StoreLoad.Recovering>(store.load()) }
                                    assertEquals(List(2) { RestoreStatus.PENDING }, persisted.statuses())
                                    assertEquals(initial, persisted.document)
                                    assertSame(original, pane.glowColor)
                                    assertEquals(4, pane.glowWidth)
                                }
                                true
                            },
                        ) { captureGlow(work, holds, request) }

                    assertEquals(OwnerApply.Completed, result)
                    val applied = assertIs<StoreLoad.Recovering>(store.load())
                    assertEquals(listOf(RestoreStatus.OWNED, RestoreStatus.OWNED), applied.statuses())
                    val width = applied.journal.receipts.single { it.identity.field.endsWith("/WIDTH") }
                    assertEquals(RawValue.Integer(4), glowEvidenceValue(width.before))
                    assertEquals(RawValue.Integer(9), glowEvidenceValue(width.lastWritten))
                    withContext(glowEdt) {
                        assertSame(changed, pane.glowColor)
                        assertEquals(9, pane.glowWidth)
                        assertEquals(40, pane.glowIntensity)
                        pane.glowWidth = 14
                        manager.updateGlow()
                    }

                    files.failNextReplace = true
                    assertEquals(OwnerRollback.ReceiptFailed(EffectOwner.GLOW, StoreIssue.IO), runner.rollback(work))
                    assertEquals(0, refreshRequests)
                    withContext(glowEdt) {
                        assertFailsWith<IllegalStateException> { manager.holdUpdates() }
                    }
                    assertEquals(OwnerRollback.Settled, runner.rollback(work))
                    assertEquals(0, refreshRequests)
                    withContext(glowEdt) {
                        assertSame(original, pane.glowColor)
                        assertEquals(14, pane.glowWidth)
                        holds.release { refreshRequests++ }
                    }
                    assertEquals(1, refreshRequests)
                    val reverted = assertIs<StoreLoad.Recovering>(store.load())
                    assertEquals(listOf(RestoreStatus.RESTORED, RestoreStatus.FOREIGN), reverted.statuses())
                    assertEquals(width.copy(status = RestoreStatus.FOREIGN), reverted.journal.receipts.last())
                    assertEquals(JournalResult.Acknowledged, store.finishRollback(work.journal.token))
                } finally {
                    withContext(glowEdt) { manager.dispose() }
                }
            }
        }
    }

    @Test
    fun `a Glow pane changed before the first setter does not acquire its hold`() {
        val pane = glowPane(Color.ORANGE)
        val manager = glowManager(pane)
        val initial = catalogTestDocument()
        val holds = StudioGlowHolds()
        var qualifications = 0
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(EffectOwner.GLOW)))
            val runner = ownerJournal(store)
            runBlocking {
                try {
                    val result =
                        runner.apply(
                            work,
                            qualifies = {
                                if (++qualifications > 1) pane.glowWidth = 13
                                true
                            },
                        ) { captureGlow(work, holds, widthRequest(manager, 9)) }

                    assertIs<OwnerApply.OwnerFailed>(result)
                    withContext(glowEdt) { manager.holdUpdates().release() }
                    assertEquals(OwnerRollback.Settled, runner.rollback(work))
                    withContext(glowEdt) {
                        holds.release { error("An unwritten pane requested a refresh") }
                        assertEquals(13, pane.glowWidth)
                    }
                } finally {
                    withContext(glowEdt) { manager.dispose() }
                }
            }
        }
    }

    @Test
    fun `Glow rolls back an earlier pane when qualification is lost before the next write`() {
        val first = glowPane(Color.ORANGE)
        val second = glowPane(Color.ORANGE)
        val manager = glowManager(first, second)
        val initial = catalogTestDocument()
        val holds = StudioGlowHolds()
        var qualifications = 0
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(EffectOwner.GLOW)))
            val runner = ownerJournal(store)
            runBlocking {
                try {
                    val result =
                        runner.apply(work, qualifies = { ++qualifications < 3 }) {
                            captureGlow(work, holds, widthRequest(manager, 9))
                        }
                    assertEquals(OwnerApply.LeaseLost, result)
                    withContext(glowEdt) {
                        assertEquals(9, first.glowWidth)
                        assertEquals(4, second.glowWidth)
                    }

                    assertEquals(OwnerRollback.Settled, runner.rollback(work))
                    withContext(glowEdt) {
                        assertEquals(4, first.glowWidth)
                        assertEquals(4, second.glowWidth)
                        holds.release { error("An unchanged environment requested a refresh") }
                        manager.holdUpdates().release()
                    }
                    val restored = assertIs<StoreLoad.Recovering>(store.load())
                    assertEquals(listOf(RestoreStatus.RESTORED, RestoreStatus.RESTORED), restored.statuses())
                } finally {
                    withContext(glowEdt) { manager.dispose() }
                }
            }
        }
    }

    @Test
    fun `closing an applied project does not block rollback of another Glow pane`() {
        val closedPane = glowPane(Color.ORANGE)
        val openPane = glowPane(Color.ORANGE)
        val closedManager = glowManager(closedPane)
        val openManager = glowManager(openPane)
        val holds = StudioGlowHolds()
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, catalogTestDocument(), listOf(EffectOwner.GLOW)))
            val runner = ownerJournal(store)
            runBlocking {
                try {
                    val result =
                        runner.apply(work, qualifies = { true }) {
                            captureGlow(work, holds, widthRequest(closedManager, 9), widthRequest(openManager, 7))
                        }
                    assertEquals(OwnerApply.Completed, result)
                    withContext(glowEdt) {
                        assertEquals(9, closedPane.glowWidth)
                        assertEquals(7, openPane.glowWidth)
                        closedManager.dispose()
                    }

                    assertEquals(OwnerRollback.Settled, runner.rollback(work))
                    withContext(glowEdt) {
                        holds.release { error("An unchanged environment requested a refresh") }
                        assertEquals(9, closedPane.glowWidth)
                        assertEquals(4, openPane.glowWidth)
                        openManager.holdUpdates().release()
                    }
                    val restored = assertIs<StoreLoad.Recovering>(store.load())
                    assertEquals(listOf(RestoreStatus.FOREIGN, RestoreStatus.RESTORED), restored.statuses())
                    assertEquals(JournalResult.Acknowledged, store.finishRollback(work.journal.token))
                } finally {
                    withContext(glowEdt) { openManager.dispose() }
                }
            }
        }
    }

    @Test
    fun `Glow releases its hold after acceptance and leaves a no-op out of the journal`() {
        val pane = glowPane(Color.ORANGE)
        val manager = glowManager(pane)
        val initial = catalogTestDocument()
        val holds = StudioGlowHolds()
        val waveform =
            WaveformConfig(
                movement = WaveformMovement.COUNTER_CLOCKWISE,
                baseline = WaveformBaseline.OUTSIDE,
                amplitude = 17,
                intensity = 73,
                loopSeconds = 3.25f,
                traceDensity = 2,
                traceLength = 133,
            )
        val request =
            StudioGlowTarget(manager) { _, current ->
                StudioGlowPaneOperation.Edit(
                    current.copy(
                        style = GlowStyle.GRADIENT,
                        intensity = 63,
                        width = 7,
                        placement = GlowPlacement.SIDE_EDGES,
                        shape = GlowShape.WAVEFORM,
                        waveform = waveform,
                        isVisible = false,
                    ),
                    GlowPaneField.entries.toSet(),
                )
            }
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(EffectOwner.GLOW)))
            val runner = ownerJournal(store)
            runBlocking {
                try {
                    assertNull(captureGlow(work, holds, widthRequest(manager, 4)))
                    assertEquals(
                        OwnerApply.Completed,
                        runner.apply(work, qualifies = { true }) { captureGlow(work, holds, request) },
                    )
                    val pending = assertIs<StoreLoad.Recovering>(store.load())
                    val values =
                        pending.journal.receipts.associate { receipt ->
                            val native = receipt.lastWritten
                            assertEquals(StudioProcess(17, Instant.EPOCH), StudioNativeEvidence.readProcess(native))
                            receipt.identity.field.substringAfterLast('/') to glowEvidenceValue(native)
                        }
                    assertEquals(expectedGlowValues(), values)
                    assertIs<CommitResult.Committed>(store.commit(work.journal.token, initial.runtime))
                    assertEquals(OwnerAcceptance.Completed, runner.accept(work))
                    withContext(glowEdt) {
                        assertEquals(7, pane.glowWidth)
                        assertEquals(waveform, pane.captureAppearance().waveform)
                        assertFalse(pane.isVisible)
                        assertFailsWith<IllegalStateException> { manager.holdUpdates() }
                        holds.release { error("An unchanged environment requested a refresh") }
                        manager.holdUpdates().release()
                    }
                    assertEquals(JournalResult.Acknowledged, store.finishAccepted(work.journal.token))
                } finally {
                    withContext(glowEdt) { manager.dispose() }
                }
            }
        }
    }

    @Test
    fun `Glow resumes the original waveform after an aborted solid transition`() = settleSolidGlow(accept = false)

    @Test
    fun `Glow stops waveform playback after accepting solid`() = settleSolidGlow(accept = true)

    private fun settleSolidGlow(accept: Boolean) {
        val pane = glowPane(Color.ORANGE)
        val manager = glowManager(pane)
        val initial = catalogTestDocument()
        val holds = StudioGlowHolds()
        val request =
            StudioGlowTarget(manager) { _, current ->
                StudioGlowPaneOperation.Edit(current.copy(shape = GlowShape.SOLID), setOf(GlowPaneField.SHAPE))
            }
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(EffectOwner.GLOW)))
            val runner = ownerJournal(store)
            runBlocking {
                try {
                    val originalTimer =
                        withContext(glowEdt) {
                            pane.configureWaveform(GlowShape.WAVEFORM, WaveformConfig())
                            pane.activateWaveform(powerSaveEnabled = false)
                            pane.capturePlaybackTimers { true }.single()
                        }
                    val applied = runner.apply(work, qualifies = { true }) { captureGlow(work, holds, request) }
                    assertEquals(OwnerApply.Completed, applied)
                    withContext(glowEdt) {
                        assertEquals(GlowShape.SOLID, pane.captureAppearance().shape)
                        assertFalse(originalTimer.pause())
                    }
                    if (accept) {
                        assertIs<CommitResult.Committed>(store.commit(work.journal.token, initial.runtime))
                        assertEquals(OwnerAcceptance.Completed, runner.accept(work))
                    } else {
                        assertEquals(OwnerRollback.Settled, runner.rollback(work))
                    }
                    withContext(glowEdt) {
                        holds.release { error("An unchanged environment requested a refresh") }
                        if (accept) {
                            assertEquals(GlowShape.SOLID, pane.captureAppearance().shape)
                            assertTrue(pane.capturePlaybackTimers { true }.isEmpty())
                        } else {
                            assertTrue(pane.isWaveform)
                            assertTrue(originalTimer.pause(), "Rollback must resume the original waveform timer")
                        }
                    }
                } finally {
                    withContext(glowEdt) {
                        pane.stopAnimation()
                        manager.dispose()
                    }
                }
            }
        }
    }

    @Test
    fun `Glow refresh sees all managers released and reads the restored pane values`() {
        val firstPane = glowPane(Color.ORANGE)
        val secondPane = glowPane(Color.CYAN)
        val managers = listOf(glowManager(firstPane), glowManager(secondPane))
        val refreshes = mutableListOf<GlowOverlayManager>()
        val holds = StudioGlowHolds()
        val refresh: (GlowOverlayManager) -> Unit =
            { manager ->
                managers.forEach { it.holdUpdates().release() }
                assertEquals(listOf(4, 4), listOf(firstPane.glowWidth, secondPane.glowWidth))
                refreshes.add(manager)
            }
        val requests = managers.map { widthRequest(it, 9) }
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, catalogTestDocument(), listOf(EffectOwner.GLOW)))
            val runner = ownerJournal(store)
            val settlement =
                StudioApplySettlement(store, runner, ioDispatcher, work) {
                    withContext(glowEdt) { holds.release(refresh) }
                }
            runBlocking {
                try {
                    val focus = withContext(glowEdt) { holds.hold(managers.first()).focusCallbacks }
                    val applied =
                        runner.apply(work, qualifies = { true }) {
                            StudioGlowOperation.capture(
                                requests,
                                holds,
                                StudioProcess(17, Instant.EPOCH),
                                work.journal.token.transactionId,
                                glowEdt,
                            )
                        }
                    assertEquals(OwnerApply.Completed, applied)
                    withContext(glowEdt) {
                        assertSame(focus, holds.hold(managers.first()).focusCallbacks)
                        assertTrue(focus.isActive)
                        managers.forEach { it.updateGlow() }
                    }

                    assertEquals(ApplySettlement.NotAccepted, settlement.settle())
                    assertEquals(managers.asReversed(), refreshes)
                    withContext(glowEdt) { assertFalse(focus.isActive) }
                } finally {
                    withContext(glowEdt) { managers.forEach { it.dispose() } }
                }
            }
        }
    }

    @Test
    fun `Glow retries only the refresh that failed after managers were released`() =
        runBlocking {
            withContext(glowEdt) {
                val first = glowManager(glowPane(Color.ORANGE))
                val second = glowManager(glowPane(Color.CYAN))
                val attempts = mutableListOf<GlowOverlayManager>()
                val failure = IllegalStateException("Glow refresh failed")
                val holds = StudioGlowHolds()
                val refresh: (GlowOverlayManager) -> Unit =
                    {
                        attempts.add(it)
                        if (attempts.size == 1) throw failure
                    }
                try {
                    holds.hold(first)
                    holds.hold(second)
                    first.updateGlow()
                    second.updateGlow()

                    assertSame(failure, assertFailsWith<IllegalStateException> { holds.release(refresh) })
                    assertEquals(listOf(second, first), attempts)
                    first.holdUpdates().release()
                    second.holdUpdates().release()

                    holds.release(refresh)
                    holds.release(refresh)
                    assertEquals(listOf(second, first, second), attempts)
                } finally {
                    first.dispose()
                    second.dispose()
                }
            }
        }

    @Test
    fun `routed owners write only after their receipts are durable in order`() {
        val owners = listOf(EffectOwner.GLOW, EffectOwner.WORKSPACE)
        val initial = catalogTestDocument()
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, owners))
            val written = mutableListOf<EffectOwner>()
            val runner = ownerJournal(store)
            val result =
                runBlocking {
                    runner.apply(work, qualifies = { true }) { owner ->
                        assertEquals(owners.take(written.size), written)
                        effect(owner) {
                            val pending = assertIs<StoreLoad.Recovering>(store.load())
                            assertEquals(written + owner, pending.journal.receipts.map { it.owner })
                            assertEquals(
                                RestoreStatus.PENDING,
                                pending.journal.receipts
                                    .last()
                                    .status,
                            )
                            assertEquals(initial, pending.document)
                            written.add(owner)
                        }
                    }
                }
            assertEquals(OwnerApply.Completed, result)
            assertEquals(owners, written)
            assertEquals(owners, work.operations.keys.toList())
            val pending = assertIs<StoreLoad.Recovering>(store.load())
            assertTrue(pending.journal.receipts.all { it.status == RestoreStatus.OWNED })
            assertEquals(initial, pending.document)
        }
    }

    @Test
    fun `a receipt write rejected by the store prevents the native setter`() {
        val owner = EffectOwner.GLOW
        val files = FailingJournalFiles(openStudioFiles(directory))
        StudioAppearanceStore(files).use { store ->
            val initial = catalogTestDocument()
            val work = StudioOwnerWork(prepare(store, initial, listOf(owner)))
            files.failNextReplace = true
            val result =
                runBlocking {
                    ownerJournal(store).apply(work, qualifies = { true }) {
                        effect(owner) { error("A setter ran without a durable receipt") }
                    }
                }
            assertEquals(OwnerApply.ReceiptFailed(owner, StoreIssue.IO), result)
            val pending = assertIs<StoreLoad.Recovering>(store.load())
            assertTrue(pending.journal.receipts.isEmpty())
            assertEquals(initial, pending.document)
        }
    }

    @Test
    fun `a lease lost during receipt persistence keeps the setter from running`() {
        val owner = EffectOwner.GLOW
        val initial = catalogTestDocument()
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(owner)))
            val result =
                runBlocking {
                    val qualified = { assertIs<StoreLoad.Recovering>(store.load()).journal.receipts.isEmpty() }
                    ownerJournal(store).apply(work, qualified) {
                        effect(owner) { error("A setter ran after the lease was lost") }
                    }
                }
            assertEquals(OwnerApply.LeaseLost, result)
            val pending = assertIs<StoreLoad.Recovering>(store.load())
            assertEquals(
                RestoreStatus.PENDING,
                pending.journal.receipts
                    .single()
                    .status,
            )
            assertEquals(initial, pending.document)
        }
    }

    @Test
    fun `a setter that writes then throws retains its handle and before-image for rollback`() {
        val owner = EffectOwner.GLOW
        val initial = catalogTestDocument()
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(owner, EffectOwner.WORKSPACE)))
            var native = false
            val failure = IllegalStateException("Native refresh failed after writing")
            val operation =
                effect(owner) {
                    native = true
                    throw failure
                }
            val result =
                runBlocking {
                    ownerJournal(store).apply(work, qualifies = { true }) {
                        assertEquals(owner, it)
                        operation
                    }
                }
            val failed = assertIs<OwnerApply.OwnerFailed>(result)
            assertEquals(owner, failed.owner)
            assertTrue(generateSequence<Throwable>(failed.cause) { it.cause }.any { it === failure })
            assertTrue(native)
            assertEquals(mapOf(owner to operation), work.operations)
            val pending = assertIs<StoreLoad.Recovering>(store.load())
            assertEquals(nativeReceipt(owner), pending.journal.receipts.single())
            assertEquals(initial, pending.document)
        }
    }

    @Test
    fun `an operation without receipts cannot enter the native setter`() {
        val initial = catalogTestDocument()
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(EffectOwner.GLOW)))
            val unrecorded =
                object : StudioOwnerOperation {
                    override val receipts = emptyList<PortableReceipt>()

                    override suspend fun apply(writes: StudioNativeWrite): Boolean = error("Unrecorded setter ran")

                    override suspend fun restore(blocked: List<PortableReceipt>) = error("Unrecorded restore ran")

                    override suspend fun accept() = error("Unrecorded acceptance ran")
                }

            assertFailsWith<IllegalArgumentException> {
                runBlocking { ownerJournal(store).apply(work, qualifies = { true }) { unrecorded } }
            }
            assertTrue(work.operations.isEmpty())
            assertEquals(initial, assertIs<StoreLoad.Recovering>(store.load()).document)
        }
    }

    @Test
    fun `a lease observation failure reaches the transaction caller without running a setter`() {
        val initial = catalogTestDocument()
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(EffectOwner.GLOW)))
            val failure = IllegalStateException("Native lease observation failed")
            val observed =
                assertFailsWith<IllegalStateException> {
                    runBlocking {
                        ownerJournal(store).apply(work, qualifies = { throw failure }) {
                            error("Owner captured without a valid lease")
                        }
                    }
                }
            assertSame(failure, observed)
            assertTrue(work.operations.isEmpty())
            assertEquals(initial, assertIs<StoreLoad.Recovering>(store.load()).document)
        }
    }

    @Test
    fun `a no-op owner leaves no receipt and later owners still apply`() {
        val initial = catalogTestDocument()
        val owner = EffectOwner.WORKSPACE
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(EffectOwner.GLOW, owner)))
            var applied = false
            val result =
                runBlocking {
                    ownerJournal(store).apply(work, qualifies = { true }) {
                        if (it == owner) effect(owner) { applied = true } else null
                    }
                }
            assertEquals(OwnerApply.Completed, result)
            assertTrue(applied)
            assertEquals(setOf(owner), work.operations.keys)
            val journal = assertIs<StoreLoad.Recovering>(store.load()).journal
            assertEquals(listOf(nativeReceipt(owner).copy(status = RestoreStatus.OWNED)), journal.receipts)
        }
    }

    @Test
    fun `cancellation after journaling stops the setter and retains rollback evidence`() {
        val initial = catalogTestDocument()
        val owner = EffectOwner.GLOW
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(owner)))
            assertFailsWith<CancellationException> {
                runBlocking {
                    ownerJournal(store).apply(
                        work,
                        qualifies = {
                            val journal = assertIs<StoreLoad.Recovering>(store.load()).journal
                            if (journal.receipts.isNotEmpty()) this@runBlocking.cancel("Apply cancelled")
                            true
                        },
                    ) { effect(owner) { error("A setter ran after cancellation") } }
                }
            }
            assertEquals(setOf(owner), work.operations.keys)
            val pending = assertIs<StoreLoad.Recovering>(store.load())
            assertEquals(listOf(nativeReceipt(owner)), pending.journal.receipts)
            assertEquals(initial, pending.document)
        }
    }

    @Test
    fun `a failed readback receipt write retains the applied owner for rollback`() {
        val initial = catalogTestDocument()
        val owner = EffectOwner.GLOW
        val files = FailingJournalFiles(openStudioFiles(directory))
        StudioAppearanceStore(files).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(owner)))
            var native = false
            val operation =
                effect(owner) {
                    native = true
                    files.failNextReplace = true
                }
            val result = runBlocking { ownerJournal(store).apply(work, qualifies = { true }) { operation } }
            assertEquals(OwnerApply.ReceiptFailed(owner, StoreIssue.IO), result)
            assertTrue(native)
            assertSame(operation, work.operations[owner])
            val pending = assertIs<StoreLoad.Recovering>(store.load())
            assertEquals(listOf(nativeReceipt(owner)), pending.journal.receipts)
            assertEquals(initial, pending.document)
        }
    }

    @Test
    fun `terminal prepared evidence never reaches the native setter`() {
        val owner = EffectOwner.GLOW
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, catalogTestDocument(), listOf(owner)))
            var wrote = false
            val operation =
                object : StudioOwnerOperation {
                    override val receipts = listOf(nativeReceipt(owner).copy(status = RestoreStatus.RESTORED))

                    override suspend fun apply(writes: StudioNativeWrite): Boolean =
                        writes.execute(ioDispatcher) { wrote = true }

                    override suspend fun restore(blocked: List<PortableReceipt>) = error("Unexpected restore")

                    override suspend fun accept() = error("Unexpected acceptance")
                }
            assertFailsWith<IllegalArgumentException> {
                runBlocking { ownerJournal(store).apply(work, qualifies = { true }) { operation } }
            }
            assertFalse(wrote)
            assertTrue(work.operations.isEmpty())
            assertTrue(assertIs<StoreLoad.Recovering>(store.load()).journal.receipts.isEmpty())
        }
    }

    @Test
    fun `readback cannot append evidence after the native write`() {
        val owner = EffectOwner.GLOW
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, catalogTestDocument(), listOf(owner)))
            val prepared = nativeReceipt(owner)
            val operation =
                object : StudioOwnerOperation {
                    override var receipts = listOf(prepared)
                        private set

                    override suspend fun apply(writes: StudioNativeWrite): Boolean =
                        writes.execute(ioDispatcher) {
                            receipts =
                                listOf(
                                    prepared.copy(status = RestoreStatus.OWNED),
                                    prepared.copy(
                                        id = "unexpected",
                                        identity = prepared.identity.copy(field = "late-field"),
                                    ),
                                )
                        }

                    override suspend fun restore(blocked: List<PortableReceipt>) = error("Unexpected restore")

                    override suspend fun accept() = error("Unexpected acceptance")
                }
            val result = runBlocking { ownerJournal(store).apply(work, qualifies = { true }) { operation } }
            assertEquals(OwnerApply.ReceiptFailed(owner, StoreIssue.CONFLICT), result)
            assertEquals(listOf(prepared), assertIs<StoreLoad.Recovering>(store.load()).journal.receipts)
            assertSame(operation, work.operations[owner])
        }
    }

    @Test
    fun `losing qualification on the native dispatcher prevents the setter`() {
        val owner = EffectOwner.GLOW
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, catalogTestDocument(), listOf(owner)))
            var qualified = true
            var wrote = false
            val nativeDispatcher =
                Executor { command ->
                    qualified = false
                    command.run()
                }.asCoroutineDispatcher()
            val operation =
                object : StudioOwnerOperation {
                    override val receipts = listOf(nativeReceipt(owner))

                    override suspend fun apply(writes: StudioNativeWrite): Boolean =
                        writes.execute(nativeDispatcher) { wrote = true }

                    override suspend fun restore(blocked: List<PortableReceipt>) = error("Unexpected restore")

                    override suspend fun accept() = error("Unexpected acceptance")
                }
            val result = runBlocking { ownerJournal(store).apply(work, qualifies = { qualified }) { operation } }
            assertEquals(OwnerApply.LeaseLost, result)
            assertFalse(wrote)
            assertEquals(operation.receipts, assertIs<StoreLoad.Recovering>(store.load()).journal.receipts)
        }
    }

    private fun ownerJournal(store: StudioAppearanceStore): StudioOwnerJournal = StudioOwnerJournal(store, ioDispatcher)

    @Test
    fun `an interrupted Apply restores captured owners in reverse order and preserves foreign state`() {
        val initial = catalogTestDocument()
        val owners = listOf(EffectOwner.GLOW, EffectOwner.WORKSPACE)
        val native = mutableMapOf<EffectOwner, Boolean?>(EffectOwner.GLOW to false, EffectOwner.WORKSPACE to false)
        val restored = mutableListOf<EffectOwner>()
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, owners))
            val runner = ownerJournal(store)
            runBlocking {
                val applied =
                    runner.apply(work, qualifies = { true }) { owner ->
                        effect(
                            owner,
                            restoreStatus = { blocked ->
                                assertTrue(blocked.isEmpty())
                                restored.add(owner)
                                if (native[owner] == true) {
                                    native[owner] = false
                                    RestoreStatus.RESTORED
                                } else {
                                    RestoreStatus.FOREIGN
                                }
                            },
                        ) { native[owner] = true }
                    }
                assertEquals(OwnerApply.Completed, applied)
                native[EffectOwner.WORKSPACE] = null
                assertEquals(OwnerRollback.Settled, runner.rollback(work))
            }
            assertEquals(owners.reversed(), restored)
            assertEquals(mapOf(EffectOwner.GLOW to false, EffectOwner.WORKSPACE to null), native)
            val pending = assertIs<StoreLoad.Recovering>(store.load())
            assertEquals(
                listOf(RestoreStatus.RESTORED, RestoreStatus.FOREIGN),
                pending.journal.receipts.map { it.status },
            )
            assertEquals(initial, pending.document)
            assertEquals(JournalResult.Acknowledged, store.finishRollback(work.journal.token))
        }
    }

    @Test
    fun `a committed candidate cannot run a rollback setter`() {
        val initial = catalogTestDocument()
        val owner = EffectOwner.GLOW
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(owner)))
            val runner = ownerJournal(store)
            runBlocking {
                assertEquals(OwnerApply.Completed, runner.apply(work, qualifies = { true }) { effect(owner) {} })
                val accepted = assertIs<CommitResult.Committed>(store.commit(work.journal.token, initial.runtime))
                assertEquals(OwnerRollback.Decision(CommitResolution.Committed), runner.rollback(work))
                val pending = assertIs<StoreLoad.Recovering>(store.load())
                assertEquals(accepted.document, pending.document)
                assertEquals(
                    RestoreStatus.OWNED,
                    pending.journal.receipts
                        .single()
                        .status,
                )
            }
        }
    }

    @Test
    fun `partial rollback preparation is journaled before an earlier owner runs`() {
        val initial = catalogTestDocument()
        val older = EffectOwner.GLOW
        val newer = EffectOwner.WORKSPACE
        val failure = IllegalStateException("Preparation failed after capture")
        val evidence = nativeReceipt(newer).copy(id = "workspace-restore", identity = nativeReceipt(older).identity)
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(older, newer)))
            val runner = ownerJournal(store)
            val result =
                runBlocking {
                    runner
                        .apply(work, qualifies = { true }) { owner ->
                            if (owner == newer) {
                                rollbackOperation(owner, prepare = {
                                    it.add(evidence)
                                    throw failure
                                })
                            } else {
                                effect(owner, restoreStatus = { blocked ->
                                    assertTrue(evidence in blocked)
                                    assertTrue(
                                        evidence in assertIs<StoreLoad.Recovering>(store.load()).journal.receipts,
                                    )
                                    RestoreStatus.UNAVAILABLE
                                }) {}
                            }
                        }.also { assertEquals(OwnerApply.Completed, it) }
                    runner.rollback(work)
                }
            assertEquals(OwnerRollback.OwnerFailed(newer, failure), result)
            val pending = assertIs<StoreLoad.Recovering>(store.load())
            assertEquals(initial, pending.document)
            assertTrue(evidence in pending.journal.receipts)
        }
    }

    @Test
    fun `a colliding rollback receipt stops restoration before any setter`() {
        val initial = catalogTestDocument()
        val older = EffectOwner.GLOW
        val newer = EffectOwner.WORKSPACE
        val restored = mutableListOf<EffectOwner>()
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(older, newer)))
            val runner = ownerJournal(store)
            val result =
                runBlocking {
                    val applied =
                        runner.apply(work, qualifies = { true }) { owner ->
                            rollbackOperation(
                                owner,
                                prepare = { receipts ->
                                    if (owner == newer) receipts.add(nativeReceipt(newer).copy(id = older.name))
                                },
                                restore = { _, _ ->
                                    restored.add(owner)
                                    RestoreContinuation.COMPLETE
                                },
                            )
                        }
                    assertEquals(OwnerApply.Completed, applied)
                    runner.rollback(work)
                }
            assertEquals(OwnerRollback.ReceiptFailed(newer, StoreIssue.CONFLICT), result)
            assertTrue(restored.isEmpty())
            val pending = assertIs<StoreLoad.Recovering>(store.load())
            assertEquals(listOf(older.name, newer.name), pending.journal.receipts.map { it.id })
            assertEquals(initial, pending.document)
        }
    }

    @Test
    fun `cancellation after a native restore preserves its journal evidence`() {
        val owner = EffectOwner.GLOW
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, catalogTestDocument(), listOf(owner)))
            val runner = ownerJournal(store)
            runBlocking {
                val applied =
                    runner.apply(work, qualifies = { true }) {
                        rollbackOperation(owner, restore = { receipts, _ ->
                            receipts[0] = receipts.single().copy(status = RestoreStatus.RESTORED)
                            currentCoroutineContext().cancel(CancellationException("Cancelled after restore"))
                            RestoreContinuation.COMPLETE
                        })
                    }
                assertEquals(OwnerApply.Completed, applied)
                val rollback = launch { runner.rollback(work) }
                rollback.join()
                assertTrue(rollback.isCancelled)
            }
            val pending = assertIs<StoreLoad.Recovering>(store.load())
            val receipt = pending.journal.receipts.single()
            assertEquals(RestoreStatus.RESTORED, receipt.status)
        }
    }

    @Test
    fun `failed receipt save during cancellation keeps evidence available for retry`() {
        val owner = EffectOwner.GLOW
        val cancellation = CancellationException("Cancelled after restore")
        val files = FailingJournalFiles(openStudioFiles(directory))
        StudioAppearanceStore(files).use { store ->
            val work = StudioOwnerWork(prepare(store, catalogTestDocument(), listOf(owner)))
            val runner = ownerJournal(store)
            var writes = 0
            runBlocking {
                val applied =
                    runner.apply(work, qualifies = { true }) {
                        rollbackOperation(owner, restore = { receipts, _ ->
                            val receipt = receipts.single()
                            if (receipt.status == RestoreStatus.OWNED) {
                                writes += 1
                                receipts[0] = receipt.copy(status = RestoreStatus.RESTORED)
                                files.failNextReplace = true
                                throw cancellation
                            }
                            RestoreContinuation.COMPLETE
                        })
                    }
                assertEquals(OwnerApply.Completed, applied)
                val failure = assertFailsWith<CancellationException> { runner.rollback(work) }
                assertSame(cancellation, failure)
                val saveFailure = assertIs<IllegalStateException>(failure.suppressed.single())
                assertTrue(saveFailure.message.orEmpty().endsWith(StoreIssue.IO.name))
                val unfinished = assertIs<StoreLoad.Recovering>(store.load())
                val unconfirmed = unfinished.journal.receipts.single()
                assertEquals(RestoreStatus.OWNED, unconfirmed.status)
                assertEquals(OwnerRollback.Settled, runner.rollback(work))
            }
            assertEquals(1, writes)
            val pending = assertIs<StoreLoad.Recovering>(store.load())
            val receipt = pending.journal.receipts.single()
            assertEquals(RestoreStatus.RESTORED, receipt.status)
        }
    }

    @Test
    fun `a pending newer owner does not hide the earlier restore failure`() {
        val initial = catalogTestDocument()
        val older = EffectOwner.GLOW
        val newer = EffectOwner.WORKSPACE
        val failure = IllegalStateException("Could not restore earlier owner")
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(older, newer)))
            val runner = ownerJournal(store)
            val result =
                runBlocking {
                    val applied =
                        runner.apply(work, qualifies = { true }) { owner ->
                            rollbackOperation(owner, restore = { _, _ ->
                                if (owner == older) throw failure
                                RestoreContinuation.REQUIRED
                            })
                        }
                    assertEquals(OwnerApply.Completed, applied)
                    runner.rollback(work)
                }
            assertEquals(OwnerRollback.OwnerFailed(older, failure), result)
        }
    }

    @Test
    fun `a rejected rollback writeback can be retried without rewriting a restored field`() {
        val initial = catalogTestDocument()
        val owner = EffectOwner.GLOW
        val files = FailingJournalFiles(openStudioFiles(directory))
        StudioAppearanceStore(files).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(owner)))
            val runner = ownerJournal(store)
            var writes = 0
            runBlocking {
                val applied =
                    runner.apply(work, qualifies = { true }) {
                        rollbackOperation(owner, restore = { receipts, _ ->
                            if (receipts.single().status == RestoreStatus.OWNED) {
                                writes += 1
                                receipts[0] = receipts[0].copy(status = RestoreStatus.RESTORED)
                                files.failNextReplace = true
                            }
                            RestoreContinuation.COMPLETE
                        })
                    }
                assertEquals(OwnerApply.Completed, applied)
                assertEquals(OwnerRollback.ReceiptFailed(owner, StoreIssue.IO), runner.rollback(work))
                val pending = assertIs<StoreLoad.Recovering>(store.load())
                assertEquals(
                    RestoreStatus.OWNED,
                    pending.journal.receipts
                        .single()
                        .status,
                )
                assertEquals(OwnerRollback.Settled, runner.rollback(work))
            }
            val pending = assertIs<StoreLoad.Recovering>(store.load())
            assertEquals(
                RestoreStatus.RESTORED,
                pending.journal.receipts
                    .single()
                    .status,
            )
            assertEquals(1, writes)
            assertEquals(initial, pending.document)
        }
    }

    @Test
    fun `owners accept only after the durable generation changes`() {
        val initial = catalogTestDocument()
        val owners = listOf(EffectOwner.GLOW, EffectOwner.WORKSPACE)
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, owners))
            val accepted = mutableListOf<EffectOwner>()
            val runner = ownerJournal(store)
            runBlocking {
                assertEquals(
                    OwnerApply.Completed,
                    runner.apply(work, qualifies = { true }) { owner ->
                        effect(owner, accepted = { accepted.add(owner) }) {}
                    },
                )
                assertEquals(OwnerAcceptance.Decision(CommitResolution.NotCommitted), runner.accept(work))
                assertTrue(accepted.isEmpty())
                assertEquals(initial, assertIs<StoreLoad.Recovering>(store.load()).document)

                val commit = assertIs<CommitResult.Committed>(store.commit(work.journal.token, initial.runtime))
                assertEquals(OwnerAcceptance.Completed, runner.accept(work))
                assertEquals(OwnerAcceptance.Completed, runner.accept(work))
                assertEquals(owners, accepted)
                val pending = assertIs<StoreLoad.Recovering>(store.load())
                assertEquals(commit.document, pending.document)
                assertEquals(owners, pending.journal.receipts.map { it.owner })
            }
        }
    }

    @Test
    fun `retry acknowledges only the owners still awaiting acceptance`() {
        val initial = catalogTestDocument()
        val owners = listOf(EffectOwner.ACCENT, EffectOwner.FONT, EffectOwner.GLOW, EffectOwner.WORKSPACE)
        val failure = IllegalStateException("Font acknowledgement interrupted")
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, owners))
            val accepted = mutableListOf<EffectOwner>()
            val written = mutableListOf<EffectOwner>()
            var fail = true
            val runner = ownerJournal(store)
            runBlocking {
                assertEquals(
                    OwnerApply.Completed,
                    runner.apply(work, qualifies = { true }) { owner ->
                        if (owner == EffectOwner.GLOW) {
                            null
                        } else {
                            effect(
                                owner,
                                accepted = {
                                    if (owner == EffectOwner.FONT && fail) throw failure
                                    accepted.add(owner)
                                },
                            ) { written.add(owner) }
                        }
                    },
                )
                val commit = assertIs<CommitResult.Committed>(store.commit(work.journal.token, initial.runtime))
                assertEquals(OwnerAcceptance.OwnerFailed(EffectOwner.FONT, failure), runner.accept(work))
                assertEquals(listOf(EffectOwner.ACCENT), accepted)
                assertEquals(commit.document, assertIs<StoreLoad.Recovering>(store.load()).document)

                fail = false
                assertEquals(OwnerAcceptance.Completed, runner.accept(work))
                val effects = listOf(EffectOwner.ACCENT, EffectOwner.FONT, EffectOwner.WORKSPACE)
                assertEquals(effects, accepted)
                assertEquals(effects, written)
            }
        }
    }

    @Test
    fun `accepted receipts without a live handle keep recovery pending`() {
        val initial = catalogTestDocument()
        val owner = EffectOwner.GLOW
        StudioAppearanceStore(openStudioFiles(directory)).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(owner)))
            val receipt = nativeReceipt(owner).copy(status = RestoreStatus.OWNED)
            assertEquals(JournalResult.Acknowledged, store.recordRecovery(work.journal.token, listOf(receipt)))
            val commit = assertIs<CommitResult.Committed>(store.commit(work.journal.token, initial.runtime))
            assertEquals(OwnerAcceptance.HandleUnavailable(owner), runBlocking { ownerJournal(store).accept(work) })
            val pending = assertIs<StoreLoad.Recovering>(store.load())
            assertEquals(commit.document, pending.document)
            assertEquals(listOf(receipt), pending.journal.receipts)
        }
    }

    @Test
    fun `cleanup after accepted Apply retries only the unfinished step`() {
        val initial = catalogTestDocument()
        val owner = EffectOwner.GLOW
        val files = FailingJournalFiles(openStudioFiles(directory))
        var acknowledgements = 0
        var releases = 0
        val failure = IllegalStateException("Cannot release the native scope yet")

        StudioAppearanceStore(files).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(owner)))
            val runner = ownerJournal(store)
            runBlocking {
                assertEquals(
                    OwnerApply.Completed,
                    runner.apply(work, qualifies = { true }) { effect(it, accepted = { acknowledgements++ }) {} },
                )
                val committed = assertIs<CommitResult.Committed>(store.commit(work.journal.token, initial.runtime))
                val settlement =
                    StudioApplySettlement(store, runner, ioDispatcher, work) { accepted ->
                        assertEquals(committed.document, accepted)
                        if (++releases == 1) throw failure
                    }
                assertEquals(
                    ApplySettlement.AcceptedCleanupPending(committed.document, SettlementProblem.Release(failure)),
                    settlement.settle(),
                )
                assertEquals(committed.document, assertIs<StoreLoad.Recovering>(store.load()).document)

                val cleanupPending =
                    ApplySettlement.AcceptedCleanupPending(
                        committed.document,
                        SettlementProblem.Journal(StoreIssue.IO),
                    )
                files.loseNextRemovalAcknowledgement = true
                assertEquals(cleanupPending, settlement.settle())
                assertEquals(committed.document, assertIs<StoreLoad.Ready>(store.load()).document)
                files.failNextStateRead = true
                assertEquals(cleanupPending, settlement.settle())
                assertEquals(ApplySettlement.Accepted(committed.document), settlement.settle())
                assertEquals(2, releases)
                assertEquals(1, acknowledgements)
            }
        }
    }

    @Test
    fun `cleanup after rollback cannot restore an already settled native field again`() {
        val initial = catalogTestDocument()
        val owner = EffectOwner.GLOW
        val files = FailingJournalFiles(openStudioFiles(directory))
        var native = false
        var restores = 0
        var releases = 0

        StudioAppearanceStore(files).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, listOf(owner)))
            val runner = ownerJournal(store)
            runBlocking {
                assertEquals(
                    OwnerApply.Completed,
                    runner.apply(work, qualifies = { true }) {
                        effect(it, restoreStatus = {
                            restores++
                            native = false
                            RestoreStatus.RESTORED
                        }) { native = true }
                    },
                )
                assertTrue(native)
                val settlement =
                    StudioApplySettlement(store, runner, ioDispatcher, work) { accepted ->
                        assertNull(accepted)
                        releases++
                    }
                files.loseNextRemovalAcknowledgement = true
                assertEquals(
                    ApplySettlement.RecoveryRequired(SettlementProblem.Journal(StoreIssue.IO)),
                    settlement.settle(),
                )
                assertEquals(initial, assertIs<StoreLoad.Ready>(store.load()).document)
                assertFalse(native)

                // A user edit after restoration must survive a journal cleanup retry.
                native = true
                assertEquals(ApplySettlement.NotAccepted, settlement.settle())
                assertEquals(initial, assertIs<StoreLoad.Ready>(store.load()).document)
                assertTrue(native)
                assertEquals(1, restores)
                assertEquals(1, releases)
            }
        }
    }

    @Test
    fun `unreadable commit evidence prevents settlement of either outcome`() {
        val initial = catalogTestDocument()
        val files = FailingJournalFiles(openStudioFiles(directory))
        StudioAppearanceStore(files).use { store ->
            val work = StudioOwnerWork(prepare(store, initial, emptyList()))
            var releases = 0
            val settlement =
                StudioApplySettlement(store, ownerJournal(store), ioDispatcher, work) { accepted ->
                    assertNull(accepted)
                    releases++
                }
            files.failNextStateRead = true
            runBlocking {
                assertEquals(ApplySettlement.RecoveryRequired(SettlementProblem.Unconfirmed), settlement.settle())
                val pending = assertIs<StoreLoad.Recovering>(store.load())
                assertEquals(work.journal.token, pending.journal.token)
                assertEquals(initial, pending.document)
                assertEquals(0, releases)
                assertEquals(ApplySettlement.NotAccepted, settlement.settle())
                assertEquals(1, releases)
            }
        }
    }

    private val glowEdt: CoroutineDispatcher = Executor(SwingUtilities::invokeLater).asCoroutineDispatcher()

    private suspend fun verifyGlowApply(accept: Boolean) {
        val initial = catalogTestDocument()
        val record = journalRecord(initial)
        val draft = record.work.drafts.single()
        val terminal = FieldAddress(StudioGlowField.TERMINAL.key)
        val focusRing = FieldAddress(StudioGlowField.FOCUS_RING.key)
        val disabled = FieldValue.Present(RawValue.BooleanValue(false))
        val edit = StudioDraftPolicy.edit(StudioDraftPolicy.edit(draft, terminal, disabled), focusRing, disabled)
        val updated = record.copy(generation = 1, work = record.work.copy(drafts = StudioList(listOf(edit))))
        val request = ApplyRequest(updated, 1)
        val operation = UUID.randomUUID()
        val (selected, peer) = withContext(glowEdt) { GlowMount() to GlowMount() }
        try {
            val field = withContext(glowEdt) { JournalFocusField() }
            val (pane, peerPane) =
                withContext(glowEdt) {
                    installFocus(field, listOf(selected.manager, peer.manager).map(::focusManager))
                    seedRetiringPane(selected) to seedRetiringPane(peer)
                }
            val originalBorder = withContext(glowEdt) { field.border }
            StudioAppearanceStore(openStudioFiles(directory)).use { store ->
                withContext(ioDispatcher) { bootstrap(store, initial) }
                var qualified = true
                var releases = 0
                val refreshFailure = IllegalStateException("Glow refresh interrupted")
                val refreshes = mutableListOf<StudioDocument?>()
                val queue = Mutex()
                val drafts = StudioDraftStore(store)
                val lease =
                    object : StudioApplyLease {
                        override suspend fun qualifies(): Boolean = qualified

                        override suspend fun capture(owner: EffectOwner): StudioOwnerOperation =
                            error("Unexpected delegated owner: $owner")

                        override suspend fun settledRuntime(candidate: StudioDocument): StudioRuntimeState {
                            val pending = withContext(ioDispatcher) { assertIs<StoreLoad.Recovering>(store.load()) }
                            assertEquals(operation.toString(), pending.journal.token.transactionId)
                            val token = pending.journal.token
                            val decision = withContext(ioDispatcher) { store.resolveOutcome(token) }
                            assertEquals(CommitResolution.NotCommitted, decision)
                            assertTrue(pending.journal.receipts.all { it.owner == EffectOwner.GLOW })
                            val receipts = pending.journal.receipts
                            val membership = receipts.single { it.identity.field == "glow/overlay/membership" }
                            assertEquals(RestoreStatus.OWNED, membership.status)
                            withContext(glowEdt) {
                                assertFalse(pane.isVisible)
                                assertSame(selected.layer, pane.parent)
                                selected.manager.updateGlow()
                                assertSame(peerPane, peer.attached())
                                assertNotSame(originalBorder, field.border)
                            }
                            qualified = accept
                            return candidate.runtime
                        }

                        override suspend fun release(accepted: StudioDocument?) {
                            assertEquals(listOf(accepted, accepted), refreshes)
                            releases++
                        }
                    }
                val coordinator =
                    StudioApplyCoordinator(
                        store,
                        queue,
                        ioDispatcher,
                        flush = { queue.withLock { drafts.save(it, "glow-draft") } },
                        observe = { apply, _ ->
                            ApplyInputs(
                                ApplyContext(
                                    AcceptanceObservation(
                                        AuthorityStatus.READY,
                                        TargetObservation(apply.session.target, 1, TargetPresence.ACTIVE),
                                    ),
                                    StudioList(emptyList()),
                                ),
                                EffectObservation(1),
                            )
                        },
                        acquire = { plan ->
                            StudioGlowApplyLease(
                                lease,
                                plan,
                                StudioProcess(17, Instant.EPOCH),
                                glowEdt,
                                observeTargets = {
                                    listOf(
                                        StudioGlowApplyLease.Target(
                                            selected.manager,
                                            StudioGlowPlanning.Accents(Color.MAGENTA, Color.MAGENTA),
                                            available = true,
                                            scope = StudioGlowApplyLease.Scope.PANES_AND_FOCUS,
                                        ),
                                        StudioGlowApplyLease.Target(
                                            peer.manager,
                                            StudioGlowPlanning.Accents(null, Color.MAGENTA),
                                            available = true,
                                            scope = StudioGlowApplyLease.Scope.FOCUS_ONLY,
                                        ),
                                    )
                                },
                                refresh = { manager, accepted ->
                                    assertSame(selected.manager, manager)
                                    if (accepted == null) {
                                        assertSame(pane, selected.attached())
                                    } else {
                                        selected.verifyEmpty()
                                    }
                                    refreshes.add(accepted)
                                    if (refreshes.size == 1) throw refreshFailure
                                },
                            )
                        },
                        recover = { _, _ -> error("Apply must settle the live Glow owner") },
                    )
                val submission = coordinator.submit(request, operation)
                val pending = assertIs<Submission.Admitted<ApplyExecution>>(submission).result.settlement
                val problem =
                    if (accept) {
                        val acceptedPending = assertIs<ApplySettlement.AcceptedCleanupPending>(pending)
                        val durable =
                            withContext(ioDispatcher) { assertIs<StoreLoad.Recovering>(store.load()).document }
                        assertEquals(durable, acceptedPending.document)
                        assertEquals(acceptedPending.document, refreshes.single())
                        acceptedPending.problem
                    } else {
                        assertNull(refreshes.single())
                        assertIs<ApplySettlement.RecoveryRequired>(pending).problem
                    }
                val cause = assertIs<IllegalStateException>(assertIs<SettlementProblem.Release>(problem).cause)
                assertEquals(refreshFailure.message, cause.message)
                assertEquals(0, releases)
                val result = requireNotNull(coordinator.retry(operation))
                val stored = withContext(ioDispatcher) { assertIs<StoreLoad.Ready>(store.load()).document }
                if (accept) {
                    assertNull(result.failure)
                    assertEquals(stored, assertIs<ApplySettlement.Accepted>(result.settlement).document)
                    assertEquals(operation.toString(), stored.header.transactionId)
                    assertEquals(edit.appearance.glow, stored.currents.global.appearance.glow)
                } else {
                    assertEquals(ApplyFailure.LeaseLost, result.failure)
                    assertEquals(ApplySettlement.NotAccepted, result.settlement)
                    assertEquals(initial.currents, stored.currents)
                    assertEquals(request.session, stored.sessions.single())
                }
                val accepted = stored.takeIf { accept }
                assertEquals(listOf(accepted, accepted), refreshes)
                assertEquals(1, releases)
                withContext(glowEdt) {
                    assertSame(peerPane, peer.attached())
                    if (accept) {
                        assertNotSame(originalBorder, field.border)
                    } else {
                        assertSame(originalBorder, field.border)
                    }
                }
            }
        } finally {
            withContext(glowEdt) {
                selected.manager.dispose()
                peer.manager.dispose()
            }
        }
    }

    private enum class GlowAdditionOutcome(
        val status: RestoreStatus,
    ) {
        ACCEPTED(RestoreStatus.OWNED),
        INTERRUPTED(RestoreStatus.RESTORED),
        REPARENTED(RestoreStatus.FOREIGN),
    }

    private class GlowMount {
        private val root = JRootPane()
        val layer: JLayeredPane = root.layeredPane
        val host =
            object : JPanel() {
                override fun isShowing(): Boolean = true

                override fun isDisplayable(): Boolean = true
            }
        private val project = mockk<Project>(relaxed = true)
        val manager = GlowOverlayManager(project)
        val appearance =
            GlowPaneAppearance(
                Color.MAGENTA,
                GlowStyle.SOFT,
                64,
                7,
                GlowPlacement.ISLAND,
                GlowShape.SOLID,
                WaveformConfig(),
                true,
            )

        init {
            val windows = mockk<ToolWindowManager>()
            val terminal = mockk<ToolWindow>()
            every { project.getService(ToolWindowManager::class.java) } returns windows
            every { windows.toolWindowIdSet } returns linkedSetOf("Terminal", "Project")
            every { windows.getToolWindow("Terminal") } returns terminal
            every { terminal.isVisible } returns true
            every { terminal.component } returns host
            root.contentPane.add(host)
            host.setBounds(7, 9, 180, 90)
            layer.setBounds(0, 0, 400, 300)
        }

        private val initialChildren = layer.components.toList()
        private val initialComponents = host.componentListeners.toList()
        private val initialBounds = host.hierarchyBoundsListeners.toList()

        fun addedComponentListener(): ComponentListener =
            host.componentListeners.single { candidate -> initialComponents.none { it === candidate } }

        fun addedBoundsListener(): HierarchyBoundsListener =
            host.hierarchyBoundsListeners.single { candidate -> initialBounds.none { it === candidate } }

        fun verifyEmpty() {
            assertEquals(initialChildren, layer.components.toList())
            assertEquals(initialComponents, host.componentListeners.toList())
            assertEquals(initialBounds, host.hierarchyBoundsListeners.toList())
            assertTrue(manager.paneTargets.isEmpty())
        }

        fun attached(visible: Boolean = true): GlowGlassPane {
            val pane = manager.paneTargets.single().pane
            assertSame(layer, pane.parent)
            assertEquals(JLayeredPane.PALETTE_LAYER.toInt(), layer.getLayer(pane))
            assertEquals(initialChildren, layer.components.filterNot { it === pane })
            assertEquals(initialComponents.size + 1, host.componentListeners.size)
            assertEquals(initialBounds.size + 1, host.hierarchyBoundsListeners.size)
            assertEquals(appearance.copy(isVisible = visible), pane.captureAppearance())
            assertEquals(Rectangle(7, 9, 180, 90), pane.bounds)
            return pane
        }
    }

    private suspend fun verifyGlowAddition(outcome: GlowAdditionOutcome) {
        val mount = withContext(glowEdt) { GlowMount() }
        val holds = StudioGlowHolds()
        val failure = IllegalStateException("Interrupted after inserting the native pane")
        try {
            if (outcome == GlowAdditionOutcome.INTERRUPTED) {
                withContext(glowEdt) {
                    mount.layer.addContainerListener(
                        object : ContainerAdapter() {
                            override fun componentAdded(event: ContainerEvent) {
                                if (event.child is GlowGlassPane) throw failure
                            }
                        },
                    )
                }
            }
            StudioAppearanceStore(openStudioFiles(directory)).use { store ->
                val work = StudioOwnerWork(prepare(store, catalogTestDocument(), listOf(EffectOwner.GLOW)))
                val runner = ownerJournal(store)
                var qualifications = 0
                val applied =
                    runner.apply(work, qualifies = {
                        if (++qualifications == 2) {
                            val receipt = withContext(ioDispatcher) { journalReceipts(store).single() }
                            assertEquals(EffectOwner.GLOW, receipt.owner)
                            assertEquals(RestoreStatus.PENDING, receipt.status)
                            verifyMountEvidence(receipt)
                            mount.verifyEmpty()
                        }
                        true
                    }) { captureGlowAddition(mount, work, holds) }
                assertEquals(2, qualifications, "Apply must qualify the journaled write before mounting")
                val nativePane =
                    withContext(glowEdt) {
                        mount.layer.components
                            .filterIsInstance<GlowGlassPane>()
                            .single()
                    }
                val settlement =
                    StudioApplySettlement(store, runner, ioDispatcher, work) {
                        val receipt = withContext(ioDispatcher) { journalReceipts(store).single() }
                        assertEquals(outcome.status, receipt.status)
                        withContext(glowEdt) { holds.release {} }
                    }
                val expectedParent =
                    when (outcome) {
                        GlowAdditionOutcome.ACCEPTED -> {
                            assertEquals(OwnerApply.Completed, applied)
                            withContext(glowEdt) { assertSame(nativePane, mount.attached()) }
                            val committed = store.commit(work.journal.token, work.journal.candidate.runtime)
                            assertIs<CommitResult.Committed>(committed)
                            mount.layer
                        }
                        GlowAdditionOutcome.INTERRUPTED -> {
                            val reported = assertIs<OwnerApply.OwnerFailed>(applied).cause
                            assertEquals(failure.message, assertIs<IllegalStateException>(reported).message)
                            null
                        }
                        GlowAdditionOutcome.REPARENTED -> {
                            assertEquals(OwnerApply.Completed, applied)
                            withContext(glowEdt) { JPanel().apply { add(nativePane) } }
                        }
                    }
                val settled = settlement.settle()
                if (outcome == GlowAdditionOutcome.ACCEPTED) {
                    assertIs<ApplySettlement.Accepted>(settled)
                    withContext(glowEdt) { assertSame(nativePane, mount.attached()) }
                } else {
                    assertEquals(ApplySettlement.NotAccepted, settled)
                    withContext(glowEdt) { mount.verifyEmpty() }
                }
                withContext(glowEdt) { assertSame(expectedParent, nativePane.parent) }
            }
        } finally {
            withContext(glowEdt) {
                holds.release {}
                mount.manager.dispose()
            }
        }
    }

    private fun verifyMountEvidence(receipt: PortableReceipt) {
        assertEquals("glow/overlay/membership", receipt.identity.field)
        assertEquals(RawValue.Null, glowEvidenceValue(receipt.before))
        val process = StudioProcess(17, Instant.EPOCH)
        assertEquals(process, StudioNativeEvidence.readProcess(receipt.before))
        assertEquals(process, StudioNativeEvidence.readProcess(receipt.lastWritten))
        val planned = StudioNativeEvidence.fields(glowEvidenceValue(receipt.lastWritten))
        assertEquals(RawValue.Text("Terminal"), planned["id"])
        val members = setOf("host", "layer", "componentListener", "boundsListener")
        assertEquals(members + "id", planned.keys)
        val references = members.map { assertIs<RawValue.Text>(planned[it]).value }
        assertEquals(4, references.distinct().size)
        assertTrue(receipt.identity.resource !in references)
    }

    private suspend fun captureGlowAddition(
        mount: GlowMount,
        work: StudioOwnerWork,
        holds: StudioGlowHolds,
    ): StudioOwnerOperation? =
        StudioGlowOperation.capture(
            listOf(planTerminal(mount, enabled = true)),
            holds,
            StudioProcess(17, Instant.EPOCH),
            work.journal.token.transactionId,
            glowEdt,
        )

    private fun planTerminal(
        mount: GlowMount,
        enabled: Boolean,
    ): StudioGlowTarget =
        StudioGlowPlanning.target(
            mount.manager,
            StudioGlowSelection(fields = StudioList(listOf(FieldAddress(StudioGlowField.TERMINAL.key)))),
            GlowSettings(
                enabled = true,
                intensity = mapOf(GlowStyle.SOFT to mount.appearance.intensity),
                width = mapOf(GlowStyle.SOFT to mount.appearance.width),
                islandToggles = mapOf("Terminal" to enabled, "Project" to true),
            ),
            StudioGlowPlanning.Accents(mount.appearance.color.takeIf { enabled }, null),
            available = true,
        )

    private suspend fun verifyGlowMountAcceptance() {
        val mount = withContext(glowEdt) { GlowMount() }
        val holds = StudioGlowHolds()
        try {
            StudioAppearanceStore(openStudioFiles(directory)).use { store ->
                val work = StudioOwnerWork(prepare(store, catalogTestDocument(), listOf(EffectOwner.GLOW)))
                val runner = ownerJournal(store)
                val applied = runner.apply(work, qualifies = { true }) { captureGlowAddition(mount, work, holds) }
                assertEquals(OwnerApply.Completed, applied)
                val commit = store.commit(work.journal.token, work.journal.candidate.runtime)
                val committed = assertIs<CommitResult.Committed>(commit)
                val settlement =
                    StudioApplySettlement(store, runner, ioDispatcher, work) {
                        withContext(glowEdt) { holds.release {} }
                    }
                val pane = withContext(glowEdt) { mount.attached() }
                val componentListener = withContext(glowEdt) { mount.addedComponentListener() }
                val boundsListener = withContext(glowEdt) { mount.addedBoundsListener() }
                val foreign = withContext(glowEdt) { JPanel().apply { add(pane) } }

                assertMountAcceptancePending(settlement, mount)
                withContext(glowEdt) {
                    assertSame(foreign, pane.parent)
                    mount.layer.setLayer(pane, JLayeredPane.PALETTE_LAYER)
                    mount.layer.add(pane)
                    mount.host.removeComponentListener(componentListener)
                }
                assertMountAcceptancePending(settlement, mount)
                withContext(glowEdt) {
                    assertTrue(mount.host.componentListeners.none { it === componentListener })
                    mount.host.addComponentListener(componentListener)
                    mount.host.removeHierarchyBoundsListener(boundsListener)
                }
                assertMountAcceptancePending(settlement, mount)
                withContext(glowEdt) {
                    assertTrue(mount.host.hierarchyBoundsListeners.none { it === boundsListener })
                    mount.host.addHierarchyBoundsListener(boundsListener)
                }
                assertEquals(ApplySettlement.Accepted(committed.document), settlement.settle())
                withContext(glowEdt) { assertSame(pane, mount.attached()) }
            }
        } finally {
            withContext(glowEdt) {
                holds.release {}
                mount.manager.dispose()
            }
        }
    }

    private suspend fun assertMountAcceptancePending(
        settlement: StudioApplySettlement,
        mount: GlowMount,
    ) {
        val pending = assertIs<ApplySettlement.AcceptedCleanupPending>(settlement.settle())
        assertIs<OwnerAcceptance.OwnerFailed>(assertIs<SettlementProblem.AcceptOwners>(pending.problem).result)
        withContext(glowEdt) { assertFailsWith<IllegalStateException> { mount.manager.holdUpdates() } }
    }

    private enum class GlowRetirementOutcome(
        val committed: Boolean,
        val reparented: Boolean = false,
        val closed: Boolean = false,
    ) {
        ABORTED(false),
        HIDDEN(false),
        ACCEPTED(true),
        REPARENTED(true, reparented = true),
        MOVED_BEFORE_CANCEL(false, reparented = true),
        HOST_MOVED_BEFORE_CANCEL(false),
        RESHOWN(true),
        CLOSED_BEFORE_CANCEL(false, closed = true),
        CLOSED_BEFORE_COMMIT(true, closed = true),
        ;

        fun verifySettlement(settled: ApplySettlement) {
            if (committed) {
                assertIs<ApplySettlement.Accepted>(settled)
            } else {
                assertEquals(ApplySettlement.NotAccepted, settled)
            }
        }
    }

    private suspend fun verifyGlowRetirement(outcome: GlowRetirementOutcome) {
        val mount = withContext(glowEdt) { GlowMount() }
        val holds = StudioGlowHolds()
        try {
            val wasVisible = outcome != GlowRetirementOutcome.HIDDEN
            val pane = withContext(glowEdt) { seedRetiringPane(mount).apply { isVisible = wasVisible } }
            val order = withContext(glowEdt) { mount.layer.components.toList() }
            val componentListener = withContext(glowEdt) { mount.addedComponentListener() }
            val boundsListener = withContext(glowEdt) { mount.addedBoundsListener() }
            StudioAppearanceStore(openStudioFiles(directory)).use { store ->
                val work = StudioOwnerWork(prepare(store, catalogTestDocument(), listOf(EffectOwner.GLOW)))
                val runner = ownerJournal(store)
                val target = planTerminal(mount, enabled = false)
                var qualifications = 0
                val result =
                    runner.apply(work, qualifies = {
                        if (++qualifications == 2) {
                            val receipt = withContext(ioDispatcher) { journalReceipts(store).single() }
                            verifyRetirementEvidence(receipt, wasVisible)
                            assertEquals(wasVisible, pane.isVisible)
                        }
                        true
                    }) {
                        StudioGlowOperation.capture(
                            listOf(target),
                            holds,
                            StudioProcess(17, Instant.EPOCH),
                            work.journal.token.transactionId,
                            glowEdt,
                        )
                    }
                assertEquals(OwnerApply.Completed, result)
                assertEquals(2, qualifications, "The prepared receipt must be observed before visibility changes")
                withContext(glowEdt) {
                    assertFalse(pane.isVisible)
                    assertSame(mount.layer, pane.parent)
                    val registered = mount.manager.paneTargets.single()
                    assertSame(pane, registered.pane)
                    assertEquals(order, mount.layer.components.toList())
                    assertSame(componentListener, mount.addedComponentListener())
                    assertSame(boundsListener, mount.addedBoundsListener())
                }
                val foreign = withContext(glowEdt) { moveRetirementTarget(outcome, mount, pane) }
                if (outcome.committed) {
                    assertIs<CommitResult.Committed>(store.commit(work.journal.token, work.journal.candidate.runtime))
                }
                val settlement =
                    StudioApplySettlement(store, runner, ioDispatcher, work) {
                        withContext(glowEdt) { holds.release {} }
                    }
                outcome.verifySettlement(settlement.settle())
                withContext(glowEdt) {
                    when (outcome) {
                        GlowRetirementOutcome.ABORTED, GlowRetirementOutcome.HIDDEN, GlowRetirementOutcome.RESHOWN -> {
                            assertSame(pane, mount.attached(wasVisible))
                            assertEquals(order, mount.layer.components.toList())
                            assertSame(componentListener, mount.addedComponentListener())
                            assertSame(boundsListener, mount.addedBoundsListener())
                        }
                        GlowRetirementOutcome.ACCEPTED, GlowRetirementOutcome.REPARENTED,
                        GlowRetirementOutcome.CLOSED_BEFORE_CANCEL, GlowRetirementOutcome.CLOSED_BEFORE_COMMIT,
                        -> {
                            mount.verifyEmpty()
                            assertSame(foreign, pane.parent)
                        }
                        GlowRetirementOutcome.MOVED_BEFORE_CANCEL -> {
                            mount.verifyEmpty()
                            assertSame(requireNotNull(foreign), pane.parent)
                            assertEquals(mount.appearance.copy(isVisible = false), pane.captureAppearance())
                            val hold = mount.manager.holdUpdates()
                            try {
                                hold.prepareAttachment(GlowOverlayAddition("Terminal", mount.host, mount.appearance))
                            } finally {
                                hold.release()
                            }
                        }
                        GlowRetirementOutcome.HOST_MOVED_BEFORE_CANCEL -> {
                            assertSame(requireNotNull(foreign), mount.host.parent)
                            assertSame(mount.layer, pane.parent)
                            assertEquals(mount.appearance, pane.captureAppearance())
                            assertSame(componentListener, mount.addedComponentListener())
                            assertSame(boundsListener, mount.addedBoundsListener())
                        }
                    }
                }
            }
        } finally {
            withContext(glowEdt) {
                holds.release {}
                if (!mount.manager.isDisposed) mount.manager.dispose()
            }
        }
    }

    private fun moveRetirementTarget(
        outcome: GlowRetirementOutcome,
        mount: GlowMount,
        pane: GlowGlassPane,
    ): JPanel? {
        if (outcome == GlowRetirementOutcome.RESHOWN) pane.isVisible = true
        if (outcome.closed) mount.manager.dispose()
        return when {
            outcome.reparented -> JPanel().apply { add(pane) }
            outcome == GlowRetirementOutcome.HOST_MOVED_BEFORE_CANCEL ->
                JPanel().apply {
                    JRootPane().contentPane = this
                    add(mount.host)
                }
            else -> null
        }
    }

    private fun verifyRetirementEvidence(
        receipt: PortableReceipt,
        wasVisible: Boolean,
    ) {
        assertEquals(EffectOwner.GLOW, receipt.owner)
        assertEquals("glow/overlay/membership", receipt.identity.field)
        assertEquals(RestoreStatus.PENDING, receipt.status)
        val process = StudioProcess(17, Instant.EPOCH)
        assertEquals(process, StudioNativeEvidence.readProcess(receipt.before))
        assertEquals(process, StudioNativeEvidence.readProcess(receipt.lastWritten))
        val before = StudioNativeEvidence.fields(glowEvidenceValue(receipt.before))
        val planned = StudioNativeEvidence.fields(glowEvidenceValue(receipt.lastWritten))
        assertEquals(setOf("id", "host", "layer", "componentListener", "boundsListener", "visible"), before.keys)
        assertEquals(RawValue.Text("Terminal"), before["id"])
        assertEquals(RawValue.BooleanValue(wasVisible), before["visible"])
        assertEquals(before + ("visible" to RawValue.BooleanValue(false)), planned)
    }

    private fun seedRetiringPane(mount: GlowMount): GlowGlassPane {
        val hold = mount.manager.holdUpdates()
        val addition = GlowOverlayAddition("Terminal", mount.host, mount.appearance)
        try {
            val attachment = hold.prepareAttachment(addition)
            attachment.apply()
            attachment.accept()
            return mount.attached()
        } finally {
            hold.release()
        }
    }

    private class RetirementFocus : DefaultKeyboardFocusManager() {
        var focused: Component? = null

        override fun getPermanentFocusOwner(): Component? = focused
    }

    private suspend fun verifyRetirementPlayback(accept: Boolean) {
        val settings = mockk<AyuIslandsSettings>()
        every { settings.state } returns AyuIslandsState().apply { glowAnimation = GlowAnimation.REACTIVE.name }
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings
        val focus = RetirementFocus()
        val (mount, survivor, previousFocus) =
            withContext(glowEdt) {
                val mount = GlowMount()
                val survivor = JPanel().apply { setBounds(200, 9, 180, 90) }
                mount.host.parent.add(survivor)
                val previousFocus = KeyboardFocusManager.getCurrentKeyboardFocusManager()
                focus.focused = mount.host
                KeyboardFocusManager.setCurrentKeyboardFocusManager(focus)
                Triple(mount, survivor, previousFocus)
            }
        try {
            val pane = withContext(glowEdt) { seedRetiringPane(mount) }
            val timer =
                withContext(glowEdt) {
                    val animator = mount.manager.readPlayingAnimator()
                    val timerField = GlowAnimator::class.java.getDeclaredField("timer").apply { isAccessible = true }
                    val timer = assertIs<Timer>(timerField.get(animator))
                    val hold = mount.manager.holdUpdates()
                    val remaining = hold.prepareAttachment(GlowOverlayAddition("Project", survivor, mount.appearance))
                    remaining.apply()
                    remaining.accept()
                    hold.release()
                    timer
                }
            withContext(glowEdt) {
                assertTrue(timer.isRunning)
                val hold = mount.manager.holdUpdates()
                val retirement = hold.prepareRetirement("Terminal")
                hold.pausePlayback()
                retirement.apply()
                assertFalse(timer.isRunning)
                assertFalse(pane.isVisible)
                if (accept) {
                    focus.focused = survivor
                    retirement.accept()
                } else {
                    retirement.restore(isBlocked = false)
                }
                val refreshRequested = hold.release()
                if (accept) assertTrue(refreshRequested, "Membership removal must request a runtime refresh")
            }
            withContext(glowEdt) {
                val active = GlowOverlayManager::class.java.getDeclaredField("activeGlowId")
                active.isAccessible = true
                if (accept) {
                    assertEquals(false to "Project", timer.isRunning to active.get(mount.manager))
                    assertNull(pane.parent)
                } else {
                    assertTrue(timer.isRunning)
                    assertSame(mount.layer, pane.parent)
                    assertTrue(pane.isVisible)
                }
            }
        } finally {
            try {
                withContext(glowEdt) {
                    mount.manager.dispose()
                    KeyboardFocusManager.setCurrentKeyboardFocusManager(previousFocus)
                }
                // Disposal queues pane removal and stops the panes' fade timers on the EDT.
                withContext(glowEdt) { mount.verifyEmpty() }
            } finally {
                unmockkObject(AyuIslandsSettings.Companion)
            }
        }
    }

    private fun GlowOverlayManager.readPlayingAnimator(): GlowAnimator {
        val field = GlowOverlayManager::class.java.getDeclaredField("animator").apply { isAccessible = true }
        return assertIs<GlowAnimator>(field.get(this))
    }

    private suspend fun verifyCombinedGlow(accept: Boolean) {
        val pane = glowPane(Color.ORANGE)
        val manager = glowManager(pane)
        val focusPeer = glowManager()
        val untouchedPeer = glowManager()
        val field = JournalFocusField()
        val widthsAtBorderChange = mutableListOf<Int>()
        val holds = StudioGlowHolds()
        val original =
            withContext(glowEdt) {
                val focus = GlowOverlayManager::class.java.getDeclaredField("focusRingManager")
                focus.isAccessible = true
                val managers = listOf(untouchedPeer, manager, focusPeer)
                installFocus(field, managers.map { focus.get(it) as FocusRingManager })
                field.addPropertyChangeListener("border") { widthsAtBorderChange.add(pane.glowWidth) }
                field.border
            }
        try {
            val files = FailingJournalFiles(openStudioFiles(directory))
            StudioAppearanceStore(files).use { store ->
                val work = StudioOwnerWork(prepare(store, catalogTestDocument(), listOf(EffectOwner.GLOW)))
                val runner = ownerJournal(store)
                var qualifications = 0
                val applied =
                    runner.apply(work, qualifies = {
                        if (++qualifications == 2) {
                            val receipts = withContext(ioDispatcher) { journalReceipts(store) }
                            val fields = receipts.map { it.identity.field.substringAfterLast('/') }
                            val expectedFields = mapOf("WIDTH" to 1, "APPEARANCE" to 2, "BORDER" to 1)
                            assertEquals("WIDTH", fields.first())
                            assertEquals(expectedFields, fields.groupingBy { it }.eachCount())
                            assertEquals(4, receipts.map { it.id }.distinct().size)
                            assertTrue(receipts.all { it.owner == EffectOwner.GLOW })
                            assertTrue(receipts.all { it.status == RestoreStatus.PENDING })
                            assertEquals(4, pane.glowWidth)
                            assertSame(original, field.border)
                            assertFailsWith<IllegalStateException> { untouchedPeer.holdUpdates() }
                        }
                        true
                    }) {
                        val width = FieldAddress(StudioGlowField.SOFT_WIDTH.key)
                        val selection =
                            StudioGlowSelection(StudioList(listOf(width)), StudioList(listOf(GlowDependency.ACCENT)))
                        val settings = GlowSettings(width = mapOf(GlowStyle.SOFT to 9))
                        val paneTarget =
                            StudioGlowPlanning.target(
                                manager,
                                selection,
                                settings,
                                StudioGlowPlanning.Accents(pane = null, focus = Color.MAGENTA),
                                available = true,
                            )
                        val focusTarget =
                            StudioGlowPlanning.target(
                                focusPeer,
                                selection,
                                settings,
                                StudioGlowPlanning.Accents(pane = null, focus = Color.GREEN),
                                available = true,
                            )
                        StudioGlowOperation.capture(
                            listOf(paneTarget, focusTarget, StudioGlowTarget(untouchedPeer)),
                            holds,
                            StudioProcess(17, Instant.EPOCH),
                            work.journal.token.transactionId,
                            glowEdt,
                        )
                    }
                assertEquals(OwnerApply.Completed, applied)
                assertEquals(List(4) { RestoreStatus.OWNED }, journalReceipts(store).map { it.status })
                val installed = withContext(glowEdt) { field.border }
                val settlement =
                    StudioApplySettlement(store, runner, ioDispatcher, work) {
                        withContext(glowEdt) { holds.release { error("No external refresh was requested") } }
                    }
                if (accept) {
                    assertIs<CommitResult.Committed>(store.commit(work.journal.token, work.journal.candidate.runtime))
                    assertIs<ApplySettlement.Accepted>(settlement.settle())
                } else {
                    files.failNextReplace = true
                    val failure = OwnerRollback.ReceiptFailed(EffectOwner.GLOW, StoreIssue.IO)
                    assertEquals(
                        ApplySettlement.RecoveryRequired(SettlementProblem.RestoreOwners(failure)),
                        settlement.settle(),
                    )
                    withContext(glowEdt) {
                        assertSame(installed, field.border)
                        assertEquals(9, pane.glowWidth)
                    }
                    assertEquals(ApplySettlement.NotAccepted, settlement.settle())
                }
                withContext(glowEdt) {
                    assertEquals(if (accept) 9 else 4, pane.glowWidth)
                    assertSame(if (accept) installed else original, field.border)
                    val expected =
                        if (accept) {
                            listOf(Color.ORANGE, Color.MAGENTA, Color.GREEN)
                        } else {
                            listOf(Color.ORANGE, Color.CYAN, Color.CYAN)
                        }
                    assertEquals(expected, field.currentColors())
                    assertEquals(if (accept) listOf(9) else listOf(9, 9), widthsAtBorderChange)
                    manager.holdUpdates().release()
                    focusPeer.holdUpdates().release()
                    untouchedPeer.holdUpdates().release()
                }
            }
        } finally {
            withContext(glowEdt) {
                holds.release { error("No external refresh was requested") }
                focusPeer.dispose()
                manager.dispose()
                untouchedPeer.dispose()
            }
        }
    }

    private suspend fun withFocusHolds(
        test: suspend (JournalFocusField, List<FocusRingManager.CallbackHold>) -> Unit,
    ) {
        val (field, managers, holds) =
            withContext(glowEdt) {
                val input = JournalFocusField()
                val focusManagers = listOf(FocusRingManager(), FocusRingManager())
                installFocus(input, focusManagers)
                Triple(input, focusManagers, focusManagers.map { it.holdCallbacks() })
            }
        try {
            test(field, holds)
        } finally {
            withContext(glowEdt) {
                holds.asReversed().forEach { it.release() }
                managers.asReversed().forEach { it.dispose() }
            }
        }
    }

    private fun installFocus(
        input: JTextField,
        managers: List<FocusRingManager>,
    ) {
        val window = mockk<Window> { every { components } returns arrayOf(input) }
        mockkStatic(Window::class)
        try {
            every { Window.getWindows() } returns arrayOf(window)
            managers.forEachIndexed { index, manager ->
                val color = if (index == 0) Color.ORANGE else Color.CYAN
                manager.initializeFocusRingGlow(color, GlowStyle.SOFT, 50 + index * 10)
            }
        } finally {
            unmockkStatic(Window::class)
        }
    }

    private fun focusManager(manager: GlowOverlayManager): FocusRingManager {
        val field = GlowOverlayManager::class.java.getDeclaredField("focusRingManager")
        field.isAccessible = true
        return field.get(manager) as FocusRingManager
    }

    private suspend fun captureFocus(
        work: StudioOwnerWork,
        holds: List<FocusRingManager.CallbackHold>,
    ): StudioOwnerOperation? =
        StudioGlowFocusOperation.capture(
            holds,
            StudioProcess(17, Instant.EPOCH),
            work.journal.token.transactionId,
            glowEdt,
        ) { _, before -> before.copy(color = Color.MAGENTA) }

    private fun journalReceipts(store: StudioAppearanceStore): List<PortableReceipt> =
        assertIs<StoreLoad.Recovering>(store.load()).journal.receipts

    private class JournalFocusField : JTextField() {
        var focused = true

        override fun hasFocus(): Boolean = focused

        fun currentColors(): List<Color> =
            focusListeners.filterIsInstance<GlowFocusListener>().map { it.currentAppearance.color }
    }

    private suspend fun captureGlow(
        work: StudioOwnerWork,
        holds: StudioGlowHolds,
        vararg requests: StudioGlowTarget,
    ): StudioOwnerOperation? =
        StudioGlowOperation.capture(
            requests.asList(),
            holds,
            StudioProcess(17, Instant.EPOCH),
            work.journal.token.transactionId,
            glowEdt,
        )

    private fun glowPane(color: Color): GlowGlassPane = GlowGlassPane(color, GlowStyle.SOFT, 40, 4)

    private fun widthRequest(
        manager: GlowOverlayManager,
        width: Int,
    ): StudioGlowTarget =
        StudioGlowTarget(manager) { _, current ->
            StudioGlowPaneOperation.Edit(current.copy(width = width), setOf(GlowPaneField.WIDTH))
        }

    private fun glowEvidenceValue(native: NativeValue): RawValue =
        StudioNativeEvidence.fields(assertIs<NativeValue.Present>(native).value).getValue("value")

    private fun StoreLoad.Recovering.statuses(): List<RestoreStatus> = journal.receipts.map { it.status }

    private fun expectedGlowValues(): Map<String, RawValue> =
        mapOf(
            "STYLE" to RawValue.Text("GRADIENT"),
            "INTENSITY" to RawValue.Integer(63),
            "WIDTH" to RawValue.Integer(7),
            "PLACEMENT" to RawValue.Text("SIDE_EDGES"),
            "SHAPE" to RawValue.Text("WAVEFORM"),
            "MOVEMENT" to RawValue.Text("COUNTER_CLOCKWISE"),
            "BASELINE" to RawValue.Text("OUTSIDE"),
            "AMPLITUDE" to RawValue.Integer(17),
            "WAVEFORM_INTENSITY" to RawValue.Integer(73),
            "LOOP_SECONDS" to RawValue.FloatBits(3.25f.toRawBits()),
            "TRACE_DENSITY" to RawValue.Integer(2),
            "TRACE_LENGTH" to RawValue.Integer(133),
            "VISIBILITY" to RawValue.BooleanValue(false),
        )

    private fun prepare(
        store: StudioAppearanceStore,
        initial: StudioDocument,
        owners: List<EffectOwner>,
    ): PendingJournal {
        bootstrap(store, initial)
        val candidate = initial.copy(header = initial.header.copy(revision = 1, transactionId = "apply"))
        val route = RecoveryPlan(StudioTarget.Global, StudioList(owners))
        return assertIs<PrepareResult.Prepared>(store.prepare(0, candidate, route)).journal
    }

    private fun bootstrap(
        store: StudioAppearanceStore,
        initial: StudioDocument,
    ) {
        assertEquals(StoreLoad.Empty, store.load())
        assertEquals(JournalResult.Acknowledged, store.preserveSource(initial.header.migration))
        val initialPlan = RecoveryPlan(StudioTarget.Global, StudioList(emptyList()))
        val first = assertIs<PrepareResult.Prepared>(store.prepare(-1, initial, initialPlan)).token
        assertIs<CommitResult.Committed>(store.commit(first, initial.runtime))
        assertEquals(JournalResult.Acknowledged, store.finishAccepted(first))
    }

    private fun effect(
        owner: EffectOwner,
        restoreStatus: (List<PortableReceipt>) -> RestoreStatus = { error("Unexpected rollback setter") },
        accepted: () -> Unit = { error("Unexpected acceptance") },
        write: () -> Unit,
    ): StudioOwnerOperation =
        object : StudioOwnerOperation {
            private var receipt = nativeReceipt(owner)
            override val receipts: List<PortableReceipt> get() = listOf(receipt)

            override suspend fun apply(writes: StudioNativeWrite): Boolean =
                writes.execute(ioDispatcher) {
                    write()
                    receipt = receipt.copy(status = RestoreStatus.OWNED)
                }

            override suspend fun restore(blocked: List<PortableReceipt>): RestoreContinuation {
                receipt = receipt.copy(status = restoreStatus(blocked))
                return RestoreContinuation.COMPLETE
            }

            override suspend fun accept() = accepted()
        }

    private fun rollbackOperation(
        owner: EffectOwner,
        prepare: (MutableList<PortableReceipt>) -> Unit = {},
        restore: suspend (MutableList<PortableReceipt>, List<PortableReceipt>) -> RestoreContinuation = { _, _ ->
            error("Unexpected rollback setter")
        },
    ): StudioOwnerOperation =
        object : StudioOwnerOperation {
            private val current = mutableListOf(nativeReceipt(owner))
            override val receipts: List<PortableReceipt> get() = current.toList()

            override suspend fun apply(writes: StudioNativeWrite): Boolean =
                writes.execute(ioDispatcher) {
                    current[0] = current[0].copy(status = RestoreStatus.OWNED)
                }

            override suspend fun prepareRestore(blocked: List<PortableReceipt>) = prepare(current)

            override suspend fun restore(blocked: List<PortableReceipt>): RestoreContinuation =
                restore(current, blocked)

            override suspend fun accept() = error("Unexpected acceptance")
        }

    private fun nativeReceipt(owner: EffectOwner): PortableReceipt {
        val surface = if (owner == EffectOwner.GLOW) "Editor frame" else "Editor scrollbar"
        return PortableReceipt(
            id = owner.name,
            owner = owner,
            identity = NativeFieldIdentity(surface, "enabled", "live-editor"),
            before = NativeValue.Present(RawValue.BooleanValue(false)),
            lastWritten = NativeValue.Present(RawValue.BooleanValue(true)),
            status = RestoreStatus.PENDING,
        )
    }
}

private class FailingJournalFiles(
    private val files: StudioFiles,
) : StudioFiles by files {
    var failNextReplace = false
    var loseNextRemovalAcknowledgement = false
    var failNextStateRead = false
    var afterPendingReplace: (() -> Unit)? = null

    override fun read(name: String): ByteArray? {
        if (failNextStateRead && name == "state.xml") {
            failNextStateRead = false
            throw IOException("Cannot read the current generation")
        }
        return files.read(name)
    }

    override fun remove(name: String) {
        files.remove(name)
        if (loseNextRemovalAcknowledgement && name == "pending.xml") {
            loseNextRemovalAcknowledgement = false
            throw IOException("Lost acknowledgement after deleting the pending journal")
        }
    }

    override fun replace(
        source: String,
        destination: String,
    ) {
        if (failNextReplace && destination == "pending.xml") {
            failNextReplace = false
            throw IOException("Cannot replace the pending journal")
        }
        files.replace(source, destination)
        if (destination == "pending.xml") {
            val callback = afterPendingReplace
            afterPendingReplace = null
            callback?.invoke()
        }
    }
}

internal fun glowManager(vararg panes: GlowGlassPane): GlowOverlayManager {
    val manager = GlowOverlayManager(mockk<Project>(relaxed = true))
    val entries = GlowOverlayManager::class.java.getDeclaredField("overlays")
    entries.isAccessible = true
    val overlays = entries.get(manager)
    val put = overlays.javaClass.getMethod("put", Any::class.java, Any::class.java)
    val entryType = GlowOverlayManager::class.java.declaredClasses.single { it.simpleName == "OverlayEntry" }
    val constructor = entryType.declaredConstructors.single { it.parameterCount == 5 }
    constructor.isAccessible = true
    panes.forEachIndexed { index, pane ->
        val root = JLayeredPane()
        root.add(pane)
        put.invoke(overlays, "pane-$index", constructor.newInstance(pane, JPanel(), root, null, null))
    }
    return manager
}
