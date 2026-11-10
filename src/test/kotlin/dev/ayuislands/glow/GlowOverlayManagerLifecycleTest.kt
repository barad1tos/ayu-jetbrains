package dev.ayuislands.glow

import com.intellij.ide.PowerSaveMode
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.MessageBusConnection
import dev.ayuislands.accent.AccentChangeListener
import dev.ayuislands.accent.AccentChangedTopic
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.AccentResolver
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.glow.waveform.CrossWindowBridge
import dev.ayuislands.glow.waveform.MAX_TRACE_LENGTH
import dev.ayuislands.glow.waveform.RouteConnectorId
import dev.ayuislands.glow.waveform.RouteCoordinator
import dev.ayuislands.glow.waveform.RouteEvent
import dev.ayuislands.glow.waveform.RouteGraph
import dev.ayuislands.glow.waveform.RouteRootId
import dev.ayuislands.glow.waveform.RouteSnapshot
import dev.ayuislands.glow.waveform.WaveformBaseline
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.glow.waveform.WaveformEdge
import dev.ayuislands.glow.waveform.WaveformEngine
import dev.ayuislands.glow.waveform.WaveformEvent
import dev.ayuislands.glow.waveform.WaveformMovement
import dev.ayuislands.glow.waveform.WaveformPainter
import dev.ayuislands.glow.waveform.WaveformRouteLayer
import dev.ayuislands.glow.waveform.WaveformState
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.HeldGlowManager
import dev.ayuislands.studio.session.StudioGlowPlayback
import dev.ayuislands.studio.session.StudioGlowReferences
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.Color
import java.awt.EventQueue
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import java.time.Instant
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.RepaintManager
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Lifecycle integration coverage for [GlowOverlayManager].
 *
 * Historically `updateGlow()` painted overlays even when no Ayu variant was
 * active, relying on three `else DEFAULT_ACCENT_HEX` fallbacks. The current
 * shape pulls that into a single [AccentContext.detect] guard at the head of
 * `updateGlow()` — overlays get disposed when no accent context is active, and
 * the three fallbacks are gone (regression-locked by [GlowFallbackBannedApiGuardTest]).
 */
@RunInEdt
class GlowOverlayManagerLifecycleTest {
    private val mockApplication = mockk<com.intellij.openapi.application.Application>(relaxed = true)
    private val mockSettings = mockk<AyuIslandsSettings>(relaxed = true)
    private val state = AyuIslandsState()
    private val mockProjectManager = mockk<ProjectManager>(relaxed = true)

    @BeforeTest
    fun setUp() {
        mockkStatic(SwingUtilities::class)

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns mockApplication
        every { mockApplication.isActive } returns true

        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns mockSettings
        every { mockSettings.state } answers {
            dev.ayuislands.studio.StudioFixtures
                .legacyView(state)
                .settings
        }
        state.glowEnabled = true
        state.externalThemeEnhancementsEnabled = false
        state.lastAppliedAccentHex = null
        state.lastApplyOk = false

        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } returns true

        mockkStatic(PowerSaveMode::class)
        every { PowerSaveMode.isEnabled() } returns false

        mockkStatic(ProjectManager::class)
        every { ProjectManager.getInstance() } returns mockProjectManager
        every { mockProjectManager.openProjects } returns emptyArray()

        mockkObject(AccentResolver)
        every { AccentResolver.resolve(any(), any<AccentContext>()) } returns "#5CCFE6"
        every { AccentResolver.resolve(any(), any<AyuVariant>()) } returns "#5CCFE6"

        mockkObject(AyuVariant.Companion)
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
        clearAllMocks()
    }

    @Test
    fun `lifecycle and timer callbacks share the real EDT`() {
        assertTrue(EventQueue.isDispatchThread())
    }

    @Test
    fun `playback capture preserves native order and actual mode without stopping anything`() {
        val manager = GlowOverlayManager(stubProject("playback-capture"))
        val first = spyk(GlowGlassPane(Color.ORANGE, GlowStyle.SOFT, 35, 6))
        val second = spyk(GlowGlassPane(Color.CYAN, GlowStyle.SHARP_NEON, 70, 4))
        seedAttachedPane(manager, first, "Terminal")
        seedAttachedPane(manager, second, "Editor")
        val running = mockk<GlowAnimator>(relaxed = true)
        every { running.currentAnimation } returns GlowAnimation.BREATHE
        setAnimator(manager, running)
        val hold = manager.holdUpdates()
        val before = listOf(first.captureAppearance(), second.captureAppearance())

        val pause = manager.capturePlayback(hold)

        assertEquals(listOf("Terminal", "Editor"), pause.targets.map { it.id })
        assertEquals(GlowAnimation.BREATHE, pause.animation)
        assertNull(pause.routeConfig)
        assertFalse(pause.isComplete)
        verify(exactly = 0) { first.stopAnimation() }
        verify(exactly = 0) { second.stopAnimation() }
        assertSame(running, readAnimator(manager))
        pause.stop()
        assertTrue(pause.isComplete)
        assertNull(readAnimator(manager))
        assertEquals(before, listOf(first.captureAppearance(), second.captureAppearance()))
        assertEquals(listOf("Terminal", "Editor"), readOverlaysMap(manager).keys.toList())
        assertSame(first, readGlassPane(manager, "Terminal"))
        assertSame(second, readGlassPane(manager, "Editor"))
        hold.release()
    }

    @Test
    fun `playback pause retains actual route configuration after native disposal`() {
        val manager = GlowOverlayManager(stubProject("playback-route-capture"))
        val config = WaveformConfig(movement = WaveformMovement.CHAOTIC, amplitude = 27, loopSeconds = 18f)
        val controller = readRouteController(manager)
        val hold = manager.holdUpdates()
        try {
            controller.configure(RoutePlayback(config, emptyList()))

            val pause = manager.capturePlayback(hold)

            assertSame(config, pause.routeConfig)
            assertTrue(controller.isActive)
            assertFalse(pause.isComplete)
            pause.stop()
            assertTrue(pause.isComplete)
            assertFalse(controller.isActive)
            assertSame(config, pause.routeConfig)
        } finally {
            controller.dispose()
            hold.release()
        }
    }

    @Test
    fun `playback pause retries only failed cleanup and leaves other panes attached`() {
        val manager = GlowOverlayManager(stubProject("playback-retry"))
        val first = spyk(GlowGlassPane(Color.ORANGE, GlowStyle.SOFT, 35, 6))
        val second = spyk(GlowGlassPane(Color.CYAN, GlowStyle.SOFT, 70, 4))
        seedAttachedPane(manager, first, "Terminal")
        seedAttachedPane(manager, second, "Editor")
        val failure = IllegalStateException("native timer shutdown")
        var shouldFail = true
        every { first.stopAnimation() } answers {
            callOriginal()
            if (shouldFail) throw failure
        }
        val hold = manager.holdUpdates()
        val pause = manager.capturePlayback(hold)

        assertSame(failure, assertFailsWith<IllegalStateException> { pause.stop() })
        assertFalse(pause.isComplete)
        verify(exactly = 1) { second.stopAnimation() }
        shouldFail = false
        pause.stop()
        pause.stop()
        assertTrue(pause.isComplete)
        verify(exactly = 2) { first.stopAnimation() }
        verify(exactly = 1) { second.stopAnimation() }
        assertNotNull(first.parent)
        assertNotNull(second.parent)
        hold.release()
    }

    @Test
    fun `playback pause leaves replaced targets untouched and completes independent cleanup`() {
        val manager = GlowOverlayManager(stubProject("playback-replacement"))
        val original = spyk(GlowGlassPane(Color.ORANGE, GlowStyle.SOFT, 35, 6))
        val other = spyk(GlowGlassPane(Color.CYAN, GlowStyle.SOFT, 70, 4))
        val replacement = spyk(GlowGlassPane(Color.PINK, GlowStyle.SOFT, 40, 3))
        seedAttachedPane(manager, original, "Terminal")
        seedAttachedPane(manager, other, "Editor")
        val hold = manager.holdUpdates()
        val pause = manager.capturePlayback(hold)
        seedAttachedPane(manager, replacement, "Terminal")

        assertFailsWith<IllegalStateException> { pause.stop() }
        assertFalse(pause.isComplete)
        verify(exactly = 0) { original.stopAnimation() }
        verify(exactly = 0) { replacement.stopAnimation() }
        verify(exactly = 1) { other.stopAnimation() }
        assertSame(replacement, readGlassPane(manager, "Terminal"))
        hold.release()
        assertFailsWith<IllegalStateException> { pause.stop() }
    }

    @Test
    fun `playback pause requires its active manager hold and observes newer blockers before effects`() {
        val manager = GlowOverlayManager(stubProject("playback-lease"))
        val other = GlowOverlayManager(stubProject("other-playback-lease"))
        val pane = spyk(GlowGlassPane(Color.ORANGE, GlowStyle.SOFT, 35, 6))
        seedAttachedPane(manager, pane, "Editor")
        val hold = manager.holdUpdates()
        val otherHold = other.holdUpdates()

        assertFailsWith<IllegalStateException> { manager.capturePlayback(otherHold) }
        val pause = manager.capturePlayback(hold)
        assertFailsWith<IllegalStateException> { pause.stop(isBlocked = true) }
        verify(exactly = 0) { pane.stopAnimation() }
        pause.stop()
        assertTrue(pause.isComplete)
        hold.release()
        assertFailsWith<IllegalStateException> { manager.capturePlayback(hold) }
        otherHold.release()
    }

    private fun seedAttachedPane(
        manager: GlowOverlayManager,
        pane: GlowGlassPane,
        id: String,
    ) {
        val root = javax.swing.JRootPane()
        val host = JPanel()
        root.contentPane.add(host)
        root.layeredPane.add(pane)
        seedOverlaysMapWithMocks(manager, pane, host, root.layeredPane, id)
    }

    @Test
    fun `restart validation requires the captured hold and completed native shutdown`() {
        val manager = GlowOverlayManager(stubProject("restart-validation"))
        val other = GlowOverlayManager(stubProject("other-restart-validation"))
        val pane = spyk(GlowGlassPane(Color.ORANGE, GlowStyle.SOFT, 35, 6))
        seedAttachedPane(manager, pane, "Editor")
        val hold = manager.holdUpdates()
        val otherHold = other.holdUpdates()
        val pause = manager.capturePlayback(hold)
        try {
            assertFailsWith<IllegalStateException> { pause.checkRestart(hold) }
            pause.stop()
            assertFailsWith<IllegalStateException> { pause.checkRestart(otherHold) }
            pause.checkRestart(hold)
            verify(exactly = 1) { pane.stopAnimation() }
        } finally {
            hold.release()
            otherHold.release()
        }
        assertFailsWith<IllegalStateException> { pause.checkRestart(hold) }
    }

    @Test
    fun `restart validation rejects replacement after an otherwise completed pause`() {
        val manager = GlowOverlayManager(stubProject("restart-replacement"))
        val original = GlowGlassPane(Color.ORANGE, GlowStyle.SOFT, 35, 6)
        val replacement = spyk(GlowGlassPane(Color.CYAN, GlowStyle.SHARP_NEON, 70, 4))
        seedAttachedPane(manager, original, "Editor")
        val hold = manager.holdUpdates()
        try {
            val pause = manager.capturePlayback(hold)
            pause.stop()
            seedAttachedPane(manager, replacement, "Editor")

            assertFailsWith<IllegalStateException> { pause.checkRestart(hold) }

            assertTrue(pause.isComplete)
            assertSame(replacement, readGlassPane(manager, "Editor"))
            verify(exactly = 0) { replacement.stopAnimation() }
        } finally {
            hold.release()
        }
    }

    @Test
    fun `Studio playback settles original evidence only after actual animation resumes`() {
        val manager = GlowOverlayManager(stubProject("studio-playback"))
        val pane = GlowGlassPane(Color.ORANGE, GlowStyle.SOFT, 35, 6)
        seedAttachedPane(manager, pane, "Editor")
        val keyboard = mockk<KeyboardFocusManager>()
        mockkStatic(KeyboardFocusManager::class)
        every { KeyboardFocusManager.getCurrentKeyboardFocusManager() } returns keyboard
        every { keyboard.permanentFocusOwner } returns readOverlayHost(manager, "Editor")
        val previous = GlowAnimator()
        previous.start(GlowAnimation.BREATHE) { pane.animationAlpha = it }
        setAnimator(manager, previous)
        state.glowAnimation = GlowAnimation.PULSE.name
        val hold = manager.holdUpdates()
        try {
            val pause = manager.capturePlayback(hold)
            val playback =
                StudioGlowPlayback(
                    StudioGlowReferences("transaction", StudioProcess(42, Instant.EPOCH)),
                    HeldGlowManager(manager, hold),
                    pause,
                    activationRequest(pause.targets, GlowAnimation.PULSE),
                )
            val original = playback.receipts.single()
            val newer = original.copy(id = "newer", identity = original.identity.copy(ownerToken = "newer"))
            assertFailsWith<IllegalStateException> { playback.pause(listOf(newer)) }
            assertSame(previous, readAnimator(manager))
            val other = newer.copy(identity = newer.identity.copy(resource = "another-manager"))
            playback.pause(listOf(other))
            playback.start(emptyMap(), { _, _ -> }, {})
            assertEquals(GlowAnimation.PULSE, assertNotNull(readAnimator(manager)).currentAnimation)
            assertEquals(RestoreStatus.OWNED, playback.receipts.single().status)
            assertFailsWith<IllegalStateException> { playback.stopForward(listOf(newer)) }
            assertEquals(GlowAnimation.PULSE, assertNotNull(readAnimator(manager)).currentAnimation)
            playback.stopForward(emptyList())
            assertEquals(original, playback.receipts.single())
            assertNull(readAnimator(manager))

            playback.prepareRestore(emptyList())
            val prepared = playback.receipts
            assertTrue(prepared.size > 1)
            assertNull(readAnimator(manager))
            assertTrue(playback.restore(emptyList()))
            val resumed = assertNotNull(readAnimator(manager))
            assertEquals(GlowAnimation.BREATHE, resumed.currentAnimation)
            assertEquals(GlowAnimation.PULSE.name, state.glowAnimation)
            assertTrue(playback.receipts.all { it.status == RestoreStatus.RESTORED })
            for (receipt in prepared) {
                assertEquals(receipt, playback.receipts.single { it.id == receipt.id }.copy(status = receipt.status))
            }
            playback.prepareRestore(emptyList())
            assertTrue(playback.restore(emptyList()))
            assertSame(resumed, readAnimator(manager))
            assertEquals(prepared.size, playback.receipts.size)
        } finally {
            hold.release()
            manager.dispose()
        }
    }

    @Test
    fun `Studio rollback retains failed startup health and acknowledges a distinct retry`() {
        val manager = GlowOverlayManager(stubProject("studio-playback-retry"))
        val pane = spyk(GlowGlassPane(Color.ORANGE, GlowStyle.SOFT, 35, 6))
        pane.configureWaveform(GlowShape.WAVEFORM, WaveformConfig())
        seedAttachedPane(manager, pane, "Editor")
        val keyboard = mockk<KeyboardFocusManager>()
        mockkStatic(KeyboardFocusManager::class)
        every { KeyboardFocusManager.getCurrentKeyboardFocusManager() } returns keyboard
        every { keyboard.permanentFocusOwner } returns readOverlayHost(manager, "Editor")
        every { PowerSaveMode.isEnabled() } returns true
        var shouldFail = true
        every { pane.activateWaveform(any(), any(), any()) } answers {
            callOriginal()
            check(!shouldFail) { "After native activation" }
        }
        val hold = manager.holdUpdates()
        try {
            val pause = manager.capturePlayback(hold)
            val playback =
                StudioGlowPlayback(
                    StudioGlowReferences("transaction", StudioProcess(42, Instant.EPOCH)),
                    HeldGlowManager(manager, hold),
                    pause,
                    activationRequest(pause.targets),
                )
            playback.pause(emptyList())
            playback.stopForward(emptyList())
            playback.prepareRestore(emptyList())
            assertFailsWith<IllegalStateException> { playback.restore(emptyList()) }
            assertNotNull(pane.currentWaveformEngine)
            playback.prepareRestore(emptyList())
            assertFalse(playback.restore(emptyList()))
            assertNull(pane.currentWaveformEngine)
            val cleaned = playback.receipts
            assertEquals(RestoreStatus.PENDING, cleaned.first().status)
            assertTrue(cleaned.drop(1).all { it.status == RestoreStatus.RESTORED })

            shouldFail = false
            playback.prepareRestore(emptyList())
            assertTrue(playback.receipts.size > cleaned.size)
            assertEquals(
                playback.receipts.size,
                playback.receipts
                    .map { it.id }
                    .distinct()
                    .size,
            )
            assertEquals(cleaned, playback.receipts.take(cleaned.size))
            assertTrue(playback.restore(emptyList()))
            assertTrue(playback.receipts.all { it.status == RestoreStatus.RESTORED })
            val restored = assertNotNull(pane.currentWaveformEngine)
            playback.prepareRestore(emptyList())
            assertTrue(playback.restore(emptyList()))
            assertSame(restored, pane.currentWaveformEngine)
            verify(exactly = 2) { pane.activateWaveform(any(), any(), any()) }
        } finally {
            hold.release()
            manager.dispose()
        }
    }

    @Test
    fun `restart restores actual animation on the same focused pane without rewriting preferences`() {
        val manager = GlowOverlayManager(stubProject("restart-same-focus"))
        val pane = GlowGlassPane(Color.ORANGE, GlowStyle.SOFT, 35, 6)
        seedAttachedPane(manager, pane, "Editor")
        val keyboard = mockk<KeyboardFocusManager>()
        mockkStatic(KeyboardFocusManager::class)
        every { KeyboardFocusManager.getCurrentKeyboardFocusManager() } returns keyboard
        every { keyboard.permanentFocusOwner } returns readOverlayHost(manager, "Editor")
        setActiveGlow(manager, "Editor")
        val previous = GlowAnimator()
        previous.start(GlowAnimation.BREATHE) { pane.animationAlpha = it }
        setAnimator(manager, previous)
        state.glowAnimation = GlowAnimation.PULSE.name
        val appearance = pane.captureAppearance()
        val hold = manager.holdUpdates()
        try {
            val pause = manager.capturePlayback(hold)
            pause.stop()
            val restart = manager.preparePlayback(hold, pause)
            assertNull(readAnimator(manager))
            assertFailsWith<IllegalStateException> { restart.checkApplied() }

            restart.start()
            val resumed = assertNotNull(readAnimator(manager))
            assertEquals(GlowAnimation.BREATHE, resumed.currentAnimation)
            restart.start()
            assertSame(resumed, readAnimator(manager))
            assertEquals(appearance, pane.captureAppearance())
            assertEquals(GlowAnimation.PULSE.name, state.glowAnimation)
            restart.stop()
            assertNull(readAnimator(manager))
            assertEquals(GlowAnimation.NONE, resumed.currentAnimation)
            assertFailsWith<IllegalStateException> { restart.start() }
        } finally {
            hold.release()
            manager.dispose()
        }
    }

    @Test
    fun `forward activation uses new targets and explicit mode without rebasing the original pause`() {
        val manager = GlowOverlayManager(stubProject("activation-new-target"))
        val original = spyk(GlowGlassPane(Color.ORANGE, GlowStyle.SOFT, 35, 6))
        val added = spyk(GlowGlassPane(Color.CYAN, GlowStyle.SHARP_NEON, 70, 4))
        seedAttachedPane(manager, original, "Editor")
        val keyboard = spyk(KeyboardFocusManager.getCurrentKeyboardFocusManager())
        mockkStatic(KeyboardFocusManager::class)
        every { KeyboardFocusManager.getCurrentKeyboardFocusManager() } returns keyboard
        every { keyboard.permanentFocusOwner } returns readOverlayHost(manager, "Editor")
        val previous = GlowAnimator()
        previous.start(GlowAnimation.BREATHE) { original.animationAlpha = it }
        setAnimator(manager, previous)
        state.glowAnimation = GlowAnimation.NONE.name
        val hold = manager.holdUpdates()
        try {
            val pause = manager.capturePlayback(hold)
            pause.stop()
            seedAttachedPane(manager, added, "Terminal")
            every { keyboard.permanentFocusOwner } returns readOverlayHost(manager, "Terminal")
            added.configureRouteMode(true)
            val activation =
                manager.preparePlayback(
                    hold,
                    pause,
                    activationRequest(
                        manager.capturePlayback(hold).targets.filter { it.id == "Terminal" },
                        GlowAnimation.PULSE,
                    ),
                )
            assertNull(readAnimator(manager))
            verify(exactly = 0) { added.startFadeIn(any()) }
            assertFailsWith<IllegalStateException> { pause.checkRestart(hold) }

            activation.start()

            assertEquals(GlowAnimation.PULSE, assertNotNull(readAnimator(manager)).currentAnimation)
            assertFalse(added.hasRouteMode())
            verify(exactly = 1) { added.startFadeIn(any()) }
            verify(exactly = 0) { original.startFadeIn(any()) }
            verify(exactly = 0) { original.startFadeOut(any()) }
            verify(exactly = 1) { original.stopAnimation() }
            verify(exactly = 0) { added.stopAnimation() }
            assertEquals(GlowAnimation.NONE.name, state.glowAnimation)
            assertEquals(GlowAnimation.BREATHE, pause.animation)
            assertEquals(listOf("Editor"), pause.targets.map { it.id })
            activation.stop()
            added.parent.remove(added)
            (readOverlaysMap(manager) as MutableMap<*, *>).remove("Terminal")
            every { keyboard.permanentFocusOwner } returns readOverlayHost(manager, "Editor")
            val restored = manager.preparePlayback(hold, pause)
            restored.start()
            assertEquals(GlowAnimation.BREATHE, assertNotNull(readAnimator(manager)).currentAnimation)
            restored.stop()
        } finally {
            hold.release()
            manager.dispose()
        }
    }

    @Test
    fun `forward route uses eligible targets in native order and leaves excluded panes untouched`() {
        val manager = GlowOverlayManager(stubProject("activation-route-subset"))
        val config = WaveformConfig(movement = WaveformMovement.CHAOTIC, amplitude = 21)
        val first = GlowGlassPane(Color.CYAN, GlowStyle.SOFT, 50, 8)
        val hidden = GlowGlassPane(Color.ORANGE, GlowStyle.GRADIENT, 23, 5)
        val last = GlowGlassPane(Color.CYAN, GlowStyle.SOFT, 50, 8)
        first.configureWaveform(GlowShape.WAVEFORM, config)
        last.configureWaveform(GlowShape.WAVEFORM, config)
        hidden.isVisible = false
        hidden.animationAlpha = 0.3f
        seedAttachedPane(manager, first, "Editor")
        seedAttachedPane(manager, hidden, "Project")
        seedAttachedPane(manager, last, "Terminal")
        val before = hidden.captureAppearance()
        val hold = manager.holdUpdates()
        try {
            val pause = manager.capturePlayback(hold)
            pause.stop()
            val activation =
                manager.preparePlayback(
                    hold,
                    pause,
                    activationRequest(
                        manager.capturePlayback(hold).targets.filter { it.id != "Project" },
                        routeConfig = config,
                    ),
                )
            activation.start()
            val playback = assertNotNull(readRouteController(manager).currentPlayback)
            assertSame(config, playback.config)
            assertEquals(listOf("Editor", "Terminal"), playback.targets.map { it.id })
            assertEquals(before, hidden.captureAppearance())
            assertEquals(0.3f, hidden.animationAlpha)
            assertFalse(hidden.hasRouteMode())
            activation.accept()
            pause.overlays.prepareRemoval("Project").cleanup()
            assertEquals(listOf("Editor", "Terminal"), readOverlaysMap(manager).keys.toList())
            assertTrue(readRouteController(manager).isActive)
            assertSame(config, readRouteController(manager).currentConfig)
        } finally {
            hold.release()
            manager.dispose()
        }
    }

    @Test
    fun `rollback preserves an explicitly active route even without current panes`() {
        val manager = GlowOverlayManager(stubProject("restart-empty-route"))
        val controller = readRouteController(manager)
        val config = WaveformConfig(movement = WaveformMovement.CHAOTIC, amplitude = 21)
        val hold = manager.holdUpdates()
        try {
            controller.configure(RoutePlayback(config, emptyList()))
            val pause = manager.capturePlayback(hold)
            pause.stop()
            val restart = manager.preparePlayback(hold, pause)
            restart.start()

            assertTrue(controller.isActive)
            assertSame(config, assertNotNull(controller.currentPlayback).config)

            restart.stop()
        } finally {
            hold.release()
            manager.dispose()
        }
    }

    @Test
    fun `accepted old overlay cleanup preserves the new animator and active pane`() {
        val manager = GlowOverlayManager(stubProject("accepted-overlay-cleanup"))
        val previousPane = spyk(GlowGlassPane(Color.ORANGE, GlowStyle.SOFT, 35, 6))
        val activePane = GlowGlassPane(Color.CYAN, GlowStyle.SHARP_NEON, 70, 4)
        seedAttachedPane(manager, previousPane, "Project")
        seedAttachedPane(manager, activePane, "Terminal")
        val keyboard = spyk(KeyboardFocusManager.getCurrentKeyboardFocusManager())
        mockkStatic(KeyboardFocusManager::class)
        every { KeyboardFocusManager.getCurrentKeyboardFocusManager() } returns keyboard
        every { keyboard.permanentFocusOwner } returns readOverlayHost(manager, "Terminal")
        val hold = manager.holdUpdates()
        try {
            val pause = manager.capturePlayback(hold)
            val removal = pause.overlays.prepareRemoval("Project")
            val hiddenAppearance = previousPane.captureAppearance().copy(isVisible = false)
            val hidden = requireNotNull(pause.overlays.capturePane("Project", hiddenAppearance))
            pause.stop()
            hidden.apply()
            val activation =
                manager.preparePlayback(
                    hold,
                    pause,
                    activationRequest(
                        manager.capturePlayback(hold).targets.filter { it.id == "Terminal" },
                        GlowAnimation.PULSE,
                    ),
                )
            activation.start()
            val animator = assertNotNull(readAnimator(manager))
            val appearance = activePane.captureAppearance()
            activation.accept()
            hidden.accept()

            removal.cleanup()
            removal.cleanup()
            activation.accept()

            assertSame(animator, readAnimator(manager))
            assertEquals(GlowAnimation.PULSE, animator.currentAnimation)
            assertSame(activePane, readGlassPane(manager, "Terminal"))
            assertEquals(appearance, activePane.captureAppearance())
            assertEquals(listOf("Terminal"), readOverlaysMap(manager).keys.toList())
            verify(exactly = 1) { previousPane.stopAnimation() }
        } finally {
            hold.release()
            manager.dispose()
        }
    }

    @Test
    fun `activation requires exact completed pause and known target identities`() {
        val manager = GlowOverlayManager(stubProject("activation-admission"))
        val other = GlowOverlayManager(stubProject("activation-other"))
        val hold = manager.holdUpdates()
        val otherHold = other.holdUpdates()
        try {
            val pause = manager.capturePlayback(hold)
            assertFailsWith<IllegalStateException> {
                manager.preparePlayback(
                    hold,
                    pause,
                    activationRequest(emptyList()),
                )
            }
            pause.stop()
            assertFailsWith<IllegalStateException> {
                other.preparePlayback(
                    otherHold,
                    pause,
                    activationRequest(emptyList()),
                )
            }
            assertFailsWith<IllegalArgumentException> {
                manager.preparePlayback(
                    hold,
                    pause,
                    activationRequest(
                        listOf(
                            RouteOverlay(
                                "missing",
                                GlowGlassPane(Color.CYAN, GlowStyle.SOFT, 50, 8),
                                JPanel(),
                                JLayeredPane(),
                            ),
                        ),
                    ),
                )
            }
            val activation =
                manager.preparePlayback(
                    hold,
                    pause,
                    activationRequest(emptyList(), GlowAnimation.PULSE),
                )
            activation.start()
            assertNull(readAnimator(manager))
            activation.stop()
        } finally {
            hold.release()
            otherHold.release()
            manager.dispose()
            other.dispose()
        }
    }

    @Test
    fun `activation rejects a changed census before starting native playback`() {
        val manager = GlowOverlayManager(stubProject("activation-census-change"))
        val original = spyk(GlowGlassPane(Color.ORANGE, GlowStyle.SOFT, 35, 6))
        seedAttachedPane(manager, original, "Editor")
        val hold = manager.holdUpdates()
        try {
            val pause = manager.capturePlayback(hold)
            pause.stop()
            val activation =
                manager.preparePlayback(
                    hold,
                    pause,
                    activationRequest(pause.targets),
                )
            seedAttachedPane(manager, GlowGlassPane(Color.CYAN, GlowStyle.SOFT, 50, 8), "Project")

            assertFailsWith<IllegalStateException> { activation.start() }

            verify(exactly = 0) { original.startFadeIn(any()) }
            verify(exactly = 0) { original.startFadeOut(any()) }
            activation.stop()
        } finally {
            hold.release()
            manager.dispose()
        }
    }

    @Test
    fun `activation rejects same id replacement before late preparation`() {
        val manager = GlowOverlayManager(stubProject("activation-replaced-target"))
        val original = GlowGlassPane(Color.ORANGE, GlowStyle.SOFT, 35, 6)
        val replacement = spyk(GlowGlassPane(Color.CYAN, GlowStyle.SOFT, 50, 8))
        seedAttachedPane(manager, original, "Editor")
        val hold = manager.holdUpdates()
        try {
            val pause = manager.capturePlayback(hold)
            val request = activationRequest(pause.targets)
            pause.stop()
            seedAttachedPane(manager, replacement, "Editor")

            assertFailsWith<IllegalStateException> { manager.preparePlayback(hold, pause, request) }
            verify(exactly = 0) { replacement.startFadeIn(any()) }
            verify(exactly = 0) { replacement.startFadeOut(any()) }
            assertSame(replacement, readGlassPane(manager, "Editor"))
        } finally {
            hold.release()
            manager.dispose()
        }
    }

    @Test
    fun `activation freezes target order and environment before late preparation`() {
        val manager = GlowOverlayManager(stubProject("activation-frozen-intent"))
        val first = spyk(GlowGlassPane(Color.ORANGE, GlowStyle.SOFT, 35, 6))
        val last = GlowGlassPane(Color.CYAN, GlowStyle.SOFT, 50, 8)
        seedAttachedPane(manager, first, "Editor")
        seedAttachedPane(manager, last, "Terminal")
        val hold = manager.holdUpdates()
        try {
            val pause = manager.capturePlayback(hold)
            val targets =
                pause.targets.map { GlowPlaybackTarget(it, it.pane.captureAppearance(), it.pane.waveformFailure) }
            val mutableTargets = targets.toMutableList()
            val request =
                GlowPlaybackRequest.Activate(
                    GlowAnimation.NONE,
                    null,
                    mutableTargets,
                    capturePlaybackEnvironment(pause.targets),
                )
            mutableTargets.clear()
            assertEquals(targets, request.targets)
            pause.stop()
            val reversed = activationRequest(pause.targets.reversed())
            assertFailsWith<IllegalStateException> { manager.preparePlayback(hold, pause, reversed) }
            every { PowerSaveMode.isEnabled() } returns true
            assertFailsWith<IllegalStateException> { manager.preparePlayback(hold, pause, request) }
            verify(exactly = 0) { first.startFadeIn(any()) }
            verify(exactly = 0) { first.startFadeOut(any()) }
        } finally {
            hold.release()
            manager.dispose()
        }
    }

    @Test
    fun `activation rejects foreign appearance and failure before late preparation`() {
        val manager = GlowOverlayManager(stubProject("activation-foreign-fields"))
        val pane = spyk(GlowGlassPane(Color.ORANGE, GlowStyle.SOFT, 35, 6))
        seedAttachedPane(manager, pane, "Editor")
        val hold = manager.holdUpdates()
        try {
            val pause = manager.capturePlayback(hold)
            val request = activationRequest(pause.targets)
            pause.stop()
            pane.glowColor = Color(Color.ORANGE.rgb)
            assertFailsWith<IllegalStateException> { manager.preparePlayback(hold, pause, request) }
            pane.glowColor = Color.ORANGE
            pane.glowIntensity = 36
            assertFailsWith<IllegalStateException> { manager.preparePlayback(hold, pause, request) }
            assertEquals(36, pane.glowIntensity)
            pane.glowIntensity = 35
            pane.failRouteWaveform(IllegalStateException("foreign failure"))
            val failure = assertNotNull(pane.waveformFailure)
            assertFailsWith<IllegalStateException> { manager.preparePlayback(hold, pause, request) }
            assertSame(failure, pane.waveformFailure)
            verify(exactly = 0) { pane.startFadeIn(any()) }
            verify(exactly = 0) { pane.startFadeOut(any()) }
        } finally {
            hold.release()
            manager.dispose()
        }
    }

    @Test
    fun `activation admits recorded engines and rejects an unrecorded replacement`() {
        val manager = GlowOverlayManager(stubProject("activation-engine-provenance"))
        val pane = spyk(GlowGlassPane(Color.CYAN, GlowStyle.SOFT, 50, 8))
        seedAttachedPane(manager, pane, "Editor")
        val hold = manager.holdUpdates()
        try {
            val pause = manager.capturePlayback(hold)
            pause.stop()
            val engines = mutableMapOf<GlowGlassPane, WaveformEngine>()
            pane.configureWaveform(GlowShape.WAVEFORM, WaveformConfig()) { engines[pane] = it }
            val request = activationRequest(pause.targets)
            assertSame(engines[pane], pane.currentWaveformEngine)
            manager.preparePlayback(hold, pause, request, engines)
            pane.stopAnimation()
            pane.configureWaveform(GlowShape.WAVEFORM, WaveformConfig())
            val replacement = assertNotNull(pane.currentWaveformEngine)
            val before = replacement.state

            assertFailsWith<IllegalStateException> { manager.preparePlayback(hold, pause, request, engines) }
            assertFailsWith<IllegalStateException> { manager.preparePlayback(hold, pause, request) }
            assertSame(replacement, pane.currentWaveformEngine)
            assertEquals(before, replacement.state)
            verify(exactly = 0) { pane.startFadeIn(any()) }
            verify(exactly = 0) { pane.startFadeOut(any()) }
        } finally {
            hold.release()
            manager.dispose()
        }
    }

    @Test
    fun `activation admits the engine prepared on a detached addition`() {
        val project = stubProject("activation-detached-engine")
        val manager = GlowOverlayManager(project)
        val root = javax.swing.JRootPane()
        val host = JPanel().apply { setSize(400, 300) }
        root.contentPane.add(host)
        state.glowEnabled = true
        state.glowShape = GlowShape.WAVEFORM.name
        val hold = manager.holdUpdates()
        try {
            val pause = manager.capturePlayback(hold)
            val addition =
                assertNotNull(
                    manager.prepareOverlay(
                        GlowHost("Editor", host, true),
                        loadGlowRuntimeInput(state),
                        AccentContext.Ayu(AyuVariant.MIRAGE),
                        hold,
                    ),
                )
            val pane = addition.entry.glassPane
            val engine = assertNotNull(pane.currentWaveformEngine)
            val target = RouteOverlay("Editor", pane, host, root.layeredPane)
            val request = activationRequest(listOf(target))
            pause.stop()
            addition.apply()
            val activation = manager.preparePlayback(hold, pause, request, mapOf(pane to engine))
            assertSame(engine, pane.currentWaveformEngine)
            activation.start()
            activation.stop()
            addition.restore()
        } finally {
            hold.release()
            manager.dispose()
        }
    }

    private fun activationRequest(
        targets: List<RouteOverlay>,
        animation: GlowAnimation = GlowAnimation.NONE,
        routeConfig: WaveformConfig? = null,
    ): GlowPlaybackRequest.Activate =
        GlowPlaybackRequest.Activate(
            animation,
            routeConfig,
            targets.map { GlowPlaybackTarget(it, it.pane.captureAppearance(), it.pane.waveformFailure) },
            capturePlaybackEnvironment(targets),
        )

    @Test
    fun `route restart rejects a foreign engine before touching any prepared pane`() {
        rejectForeignRouteEngine(duringPublication = false)
    }

    @Test
    fun `route restart rechecks native engines after publishing its retained route`() {
        rejectForeignRouteEngine(duringPublication = true)
    }

    private fun rejectForeignRouteEngine(duringPublication: Boolean) {
        val manager = GlowOverlayManager(stubProject("restart-foreign-route-engine"))
        val controller = readRouteController(manager)
        val config = WaveformConfig(movement = WaveformMovement.CHAOTIC)
        val first = GlowGlassPane(Color.CYAN, GlowStyle.SOFT, 50, 8)
        val last = GlowGlassPane(Color.CYAN, GlowStyle.SOFT, 50, 8)
        for (pane in listOf(first, last)) pane.configureWaveform(GlowShape.WAVEFORM, config)
        seedAttachedPane(manager, first, "Editor")
        seedAttachedPane(manager, last, "Terminal")
        val hold = manager.holdUpdates()
        try {
            val pause = manager.capturePlayback(hold)
            pause.stop()
            first.animationAlpha = 0.4f
            val restart = manager.preparePlayback(hold, pause, activationRequest(pause.targets, routeConfig = config))
            var foreign: WaveformEngine? = null
            var before: WaveformState? = null
            val replaceEngine = {
                last.configureWaveform(GlowShape.WAVEFORM, config)
                val engine = assertNotNull(last.currentWaveformEngine)
                engine.handle(WaveformEvent.Activate(powerSaveEnabled = true))
                foreign = engine
                before = engine.state
            }
            if (!duringPublication) replaceEngine()

            assertFailsWith<IllegalStateException> {
                restart.start(onRouteCreated = { if (duringPublication) replaceEngine() })
            }

            val retained = assertNotNull(foreign)
            assertSame(retained, last.currentWaveformEngine)
            assertEquals(assertNotNull(before), retained.state)
            assertFalse(controller.isActive)
            assertNull(controller.currentPlayback)
            if (!duringPublication) assertEquals(0.4f, first.animationAlpha)
            restart.stop()
            assertSame(retained, last.currentWaveformEngine)
            assertEquals(before, retained.state)
        } finally {
            hold.release()
            manager.dispose()
        }
    }

    @Test
    fun `route pane activation preserves an engine replaced while its earlier peer repaints`() {
        val manager = GlowOverlayManager(stubProject("restart-foreign-peer-engine"))
        val controller = readRouteController(manager)
        val config = WaveformConfig(movement = WaveformMovement.CHAOTIC)
        val first = GlowGlassPane(Color.CYAN, GlowStyle.SOFT, 50, 8)
        val last = GlowGlassPane(Color.CYAN, GlowStyle.SOFT, 50, 8)
        for (pane in listOf(first, last)) pane.configureWaveform(GlowShape.WAVEFORM, config)
        seedAttachedPane(manager, first, "Editor")
        seedAttachedPane(manager, last, "Terminal")
        val hold = manager.holdUpdates()
        val previous = RepaintManager.currentManager(first)
        var foreign: WaveformEngine? = null
        var before: WaveformState? = null
        val observer =
            object : RepaintManager() {
                override fun addDirtyRegion(
                    component: javax.swing.JComponent,
                    x: Int,
                    y: Int,
                    width: Int,
                    height: Int,
                ) {
                    if (component === first && controller.currentPlayback != null && foreign == null) {
                        last.configureWaveform(GlowShape.WAVEFORM, config)
                        val engine = assertNotNull(last.currentWaveformEngine)
                        engine.handle(WaveformEvent.Activate(powerSaveEnabled = true))
                        foreign = engine
                        before = engine.state
                    }
                    super.addDirtyRegion(component, x, y, width, height)
                }
            }
        try {
            val pause = manager.capturePlayback(hold)
            pause.stop()
            val restart = manager.preparePlayback(hold, pause, activationRequest(pause.targets, routeConfig = config))
            RepaintManager.setCurrentManager(observer)

            assertFailsWith<IllegalStateException> { restart.start() }

            val retained = assertNotNull(foreign)
            assertSame(retained, last.currentWaveformEngine)
            assertEquals(assertNotNull(before), retained.state)
            restart.stop()
            assertSame(retained, last.currentWaveformEngine)
            assertEquals(before, retained.state)
        } finally {
            RepaintManager.setCurrentManager(previous)
            hold.release()
            manager.dispose()
        }
    }

    @Test
    fun `restart evidence freezes native preparation while reporting current cleanup progress`() {
        val manager = GlowOverlayManager(stubProject("restart-frozen-evidence"))
        val pane = GlowGlassPane(Color.ORANGE, GlowStyle.SOFT, 35, 6)
        seedAttachedPane(manager, pane, "Editor")
        val hold = manager.holdUpdates()
        try {
            val pause = manager.capturePlayback(hold)
            pause.stop()
            val restart = manager.preparePlayback(hold, pause)
            val prepared = restart.record
            val expected = prepared.intent.targets.single()
            assertSame(pane, expected.overlay.pane)
            assertSame(Color.ORANGE, expected.appearance.color)
            assertNull(expected.failure)
            assertEquals(GlowCheckpointPhase.PREPARED, prepared.phase)
            assertFalse(prepared.intent.environment.isPowerSaveEnabled)
            pane.glowColor = Color.CYAN
            pane.failRouteWaveform(IllegalStateException("Foreign occurrence"))
            val failure = assertNotNull(pane.waveformFailure)
            every { PowerSaveMode.isEnabled() } returns true

            assertSame(prepared.intent, restart.record.intent)
            val recordedTarget =
                restart.record.intent.targets
                    .single()
            assertSame(Color.ORANGE, recordedTarget.appearance.color)
            assertNull(recordedTarget.failure)
            assertFalse(restart.record.intent.environment.isPowerSaveEnabled)
            assertFailsWith<IllegalStateException> { restart.start() }
            restart.stop()
            assertEquals(GlowCheckpointPhase.RESTORED, restart.record.phase)
            assertEquals(GlowCheckpointPhase.PREPARED, prepared.phase)
            assertSame(prepared.intent, restart.record.intent)
            assertSame(Color.CYAN, pane.glowColor)
            assertSame(failure, pane.waveformFailure)
        } finally {
            hold.release()
            manager.dispose()
        }
    }

    @Test
    fun `restart rejects a changed environment before creating native resources`() {
        val manager = GlowOverlayManager(stubProject("restart-stale-environment"))
        val pane = spyk(GlowGlassPane(Color.ORANGE, GlowStyle.SOFT, 35, 6))
        seedAttachedPane(manager, pane, "Editor")
        val hold = manager.holdUpdates()
        try {
            val pause = manager.capturePlayback(hold)
            pause.stop()
            val restart = manager.preparePlayback(hold, pause)
            every { PowerSaveMode.isEnabled() } returns true

            assertFailsWith<IllegalStateException> { restart.start() }
            verify(exactly = 0) { pane.startFadeIn(any()) }
            verify(exactly = 0) { pane.startFadeOut(any()) }
            assertNull(readAnimator(manager))
            restart.stop()
        } finally {
            hold.release()
            manager.dispose()
        }
    }

    @Test
    fun `route restart hands off its exact request without restarting accepted playback`() {
        val manager = GlowOverlayManager(stubProject("restart-route-handoff"))
        val controller = readRouteController(manager)
        val config = WaveformConfig(movement = WaveformMovement.CHAOTIC, amplitude = 27, loopSeconds = 18f)
        val pane = GlowGlassPane(Color.CYAN, GlowStyle.SOFT, 50, 8)
        pane.configureWaveform(GlowShape.WAVEFORM, config)
        pane.animationAlpha = 0.4f
        seedAttachedPane(manager, pane, "Editor")
        val hold = manager.holdUpdates()
        try {
            controller.configure(RoutePlayback(config, manager.capturePlayback(hold).targets))
            val pause = manager.capturePlayback(hold)
            pause.stop()
            val restart = manager.preparePlayback(hold, pause)
            restart.start()
            val selected = assertNotNull(controller.currentPlayback)
            assertSame(config, selected.config)
            assertEquals(1f, pane.animationAlpha)
            restart.start()
            assertSame(selected, controller.currentPlayback)
            restart.accept()
            restart.accept()
            assertNull(controller.currentPlayback)
            assertTrue(controller.isActive)
            assertSame(config, controller.currentConfig)
            assertFailsWith<IllegalStateException> { restart.stop() }
        } finally {
            hold.release()
            manager.dispose()
        }
    }

    @Test
    fun `accepted restart releases ownership after application activity changes`() {
        val manager = GlowOverlayManager(stubProject("restart-accepted-environment"))
        val hold = manager.holdUpdates()
        try {
            val pause = manager.capturePlayback(hold)
            pause.stop()
            val restart = manager.preparePlayback(hold, pause)
            restart.start()
            every { mockApplication.isActive } returns false
            assertFailsWith<IllegalStateException> { restart.checkApplied() }

            restart.accept()
            restart.accept()
            assertTrue(hold.needsRefresh)
            assertFailsWith<IllegalStateException> { restart.stop() }
        } finally {
            hold.release()
            manager.dispose()
        }
    }

    @Test
    fun `restart never replaces a foreign route installed by the alpha repaint`() {
        val manager = GlowOverlayManager(stubProject("restart-repaint-route"))
        val controller = readRouteController(manager)
        val config = WaveformConfig(movement = WaveformMovement.CHAOTIC, amplitude = 27)
        val pane = GlowGlassPane(Color.CYAN, GlowStyle.SOFT, 50, 8)
        pane.configureWaveform(GlowShape.WAVEFORM, config)
        seedAttachedPane(manager, pane, "Editor")
        val hold = manager.holdUpdates()
        val previous = RepaintManager.currentManager(pane)
        val replacement = RoutePlayback(config.copy(amplitude = 17), emptyList())
        var shouldReplace = true
        val observer =
            object : RepaintManager() {
                override fun addDirtyRegion(
                    component: javax.swing.JComponent,
                    x: Int,
                    y: Int,
                    width: Int,
                    height: Int,
                ) {
                    if (component === pane && shouldReplace) {
                        shouldReplace = false
                        controller.configure(replacement)
                    }
                    super.addDirtyRegion(component, x, y, width, height)
                }
            }
        try {
            controller.configure(RoutePlayback(config, manager.capturePlayback(hold).targets))
            val pause = manager.capturePlayback(hold)
            pause.stop()
            val restart = manager.preparePlayback(hold, pause)
            RepaintManager.setCurrentManager(observer)

            assertFailsWith<IllegalStateException> { restart.start() }
            assertFalse(shouldReplace)
            assertSame(replacement, controller.currentPlayback)
            restart.stop()
            assertSame(replacement, controller.currentPlayback)
            assertTrue(controller.isActive)
        } finally {
            RepaintManager.setCurrentManager(previous)
            hold.release()
            manager.dispose()
        }
    }

    @Test
    fun `failed restart retains cleanup and requires a new attempt`() {
        val manager = GlowOverlayManager(stubProject("restart-partial-failure"))
        val pane = GlowGlassPane(Color.CYAN, GlowStyle.SOFT, 50, 8)
        pane.configureWaveform(GlowShape.WAVEFORM, WaveformConfig(amplitude = 17))
        seedAttachedPane(manager, pane, "Editor")
        val keyboard = mockk<KeyboardFocusManager>()
        mockkStatic(KeyboardFocusManager::class)
        every { KeyboardFocusManager.getCurrentKeyboardFocusManager() } returns keyboard
        every { keyboard.permanentFocusOwner } returns readOverlayHost(manager, "Editor")
        val hold = manager.holdUpdates()
        try {
            val pause = manager.capturePlayback(hold)
            pause.stop()
            val appearance = pane.captureAppearance()
            val restart = manager.preparePlayback(hold, pause)
            val cause = IllegalStateException("Engine registration failed after fade startup")
            assertSame(
                cause,
                assertFailsWith<IllegalStateException> {
                    restart.start(onEngineCreated = { _, _ -> throw cause })
                },
            )
            assertFailsWith<IllegalStateException> { restart.checkApplied() }
            assertFailsWith<IllegalStateException> { restart.start() }
            restart.stop()
            restart.stop()
            assertEquals(appearance, pane.captureAppearance())
            val replacement = manager.preparePlayback(hold, pause)
            replacement.start()
            replacement.checkApplied()
            replacement.stop()
            assertEquals(appearance, pane.captureAppearance())
        } finally {
            hold.release()
            manager.dispose()
        }
    }

    @Test
    fun `restart cleanup preserves a replacement animator and fade`() {
        val manager = GlowOverlayManager(stubProject("restart-replaced-animator"))
        val pane = GlowGlassPane(Color.CYAN, GlowStyle.SOFT, 50, 8)
        seedAttachedPane(manager, pane, "Editor")
        val keyboard = mockk<KeyboardFocusManager>()
        mockkStatic(KeyboardFocusManager::class)
        every { KeyboardFocusManager.getCurrentKeyboardFocusManager() } returns keyboard
        every { keyboard.permanentFocusOwner } returns readOverlayHost(manager, "Editor")
        val previous = GlowAnimator()
        previous.start(GlowAnimation.BREATHE) { pane.animationAlpha = it }
        setAnimator(manager, previous)
        val hold = manager.holdUpdates()
        try {
            val pause = manager.capturePlayback(hold)
            pause.stop()
            val restart = manager.preparePlayback(hold, pause)
            restart.start()
            val owned = assertNotNull(readAnimator(manager))
            val replacement = GlowAnimator()
            replacement.start(GlowAnimation.PULSE) { pane.animationAlpha = it }
            setAnimator(manager, replacement)
            var replacementFade: Timer? = null
            pane.startFadeIn { replacementFade = it }

            restart.stop()

            assertEquals(GlowAnimation.NONE, owned.currentAnimation)
            assertSame(replacement, readAnimator(manager))
            assertEquals(GlowAnimation.PULSE, replacement.currentAnimation)
            assertTrue(assertNotNull(replacementFade).isRunning)
        } finally {
            hold.release()
            manager.dispose()
        }
    }

    @Test
    fun `playback pause retains failed animator shutdown before entering platform disposal`() {
        val manager = GlowOverlayManager(stubProject("playback-animator-failure"))
        val pane = spyk(GlowGlassPane(Color.ORANGE, GlowStyle.SOFT, 35, 6))
        seedAttachedPane(manager, pane, "Editor")
        val running = mockk<GlowAnimator>(relaxed = true)
        val failure = IllegalStateException("animator timer shutdown")
        every { running.stop() } throws failure
        setAnimator(manager, running)
        val hold = manager.holdUpdates()
        val pause = manager.capturePlayback(hold)

        assertSame(failure, assertFailsWith<IllegalStateException> { pause.stop() })
        assertFalse(pause.isComplete)
        assertSame(running, readAnimator(manager))
        verify(exactly = 0) { running.dispose() }
        verify(exactly = 1) { pane.stopAnimation() }
        every { running.stop() } just Runs
        pause.stop()
        assertTrue(pause.isComplete)
        assertNull(readAnimator(manager))
        verify(exactly = 1) { running.dispose() }
        verify(exactly = 1) { pane.stopAnimation() }
        hold.release()
    }

    @Test
    fun `held updates retain native panes and coalesce accent without changing preferences`() {
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        every { SwingUtilities.invokeLater(any()) } just Runs
        val manager = GlowOverlayManager(stubProject("held-appearance"))
        markManagerWarm(manager)
        val pane = GlowGlassPane(Color.ORANGE, GlowStyle.SOFT, 35, 6)
        seedOverlaysMapWithMocks(manager, pane, mockk(relaxed = true), mockk(relaxed = true))
        val before = pane.captureAppearance()
        val hold = manager.holdUpdates()
        val finalAccent = AccentHex.require("#123456")

        manager.updateGlow(AccentHex.require("#5CCFE6"))
        manager.updateGlow(finalAccent)
        manager.previewPlacements(GlowPlacement.SIDE_EDGES, GlowPlacement.SIDE_EDGES)
        every { LicenseChecker.isLicensedOrGrace() } returns false
        manager.updateGlow()

        assertEquals(before, pane.captureAppearance())
        assertSame(pane, readGlassPane(manager, PROJECT_ID))
        assertEquals(finalAccent, hold.pendingAccent)
        assertTrue(hold.needsRefresh)
        assertTrue(state.glowEnabled)
        assertNull(state.lastAppliedAccentHex)
        hold.release()
        manager.updateGlow()
        assertTrue(readOverlaysMap(manager).isEmpty())
    }

    @Test
    fun `held updates stop armed initialization callbacks until a fresh refresh`() {
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        val scheduled = mutableListOf<Runnable>()
        every { SwingUtilities.invokeLater(any()) } answers { scheduled += firstArg<Runnable>() }
        val project = stubProject("held-initialization")
        val manager = GlowOverlayManager(project)
        markManagerWarm(manager)
        val windows = mockk<ToolWindowManager>(relaxed = true)
        mockkStatic(ToolWindowManager::class)
        every { ToolWindowManager.getInstance(project) } returns windows
        manager.initialize()
        val stale = scheduled.single()
        val hold = manager.holdUpdates()

        stale.run()

        verify(exactly = 0) { windows.toolWindowIdSet }
        assertTrue(hold.needsRefresh)
        hold.release()
        stale.run()
        verify(exactly = 0) { windows.toolWindowIdSet }
    }

    @Test
    fun `held off EDT accent publication retains the exact payload`() {
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        val scheduled = mutableListOf<Runnable>()
        every { SwingUtilities.invokeLater(any()) } answers { scheduled += firstArg<Runnable>() }
        val project = stubProject("held-accent")
        val connection = mockk<MessageBusConnection>(relaxed = true)
        every { project.messageBus.connect(any<Disposable>()) } returns connection
        val accentListener = slot<AccentChangeListener>()
        every { connection.subscribe(eq(AccentChangedTopic.TOPIC), capture(accentListener)) } just Runs
        val manager = GlowOverlayManager(project)
        manager.initialize()
        val hold = manager.holdUpdates()
        val accent = AccentHex.require("#123456")
        every { SwingUtilities.isEventDispatchThread() } returns false
        accentListener.captured.accentChanged(project, accent, AccentResolver.Source.GLOBAL)
        every { SwingUtilities.isEventDispatchThread() } answers { EventQueue.isDispatchThread() }

        scheduled.toList().forEach(Runnable::run)

        assertEquals(accent, hold.pendingAccent)
        hold.release()
        manager.dispose()
    }

    @Test
    fun `held geometry callbacks do not move the captured pane`() {
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        val scheduled = mutableListOf<Runnable>()
        every { SwingUtilities.invokeLater(any()) } answers { scheduled += firstArg<Runnable>() }
        val manager = GlowOverlayManager(stubProject("held-bounds"))
        val host = spyk(JPanel(null))
        val rootPane = mockk<javax.swing.JRootPane>(relaxed = true)
        val layeredPane = JLayeredPane()
        every { host.width } returns 120
        every { host.height } returns 80
        every { host.isShowing } returns true
        every { rootPane.layeredPane } returns layeredPane
        every { SwingUtilities.getRootPane(host) } returns rootPane
        var position = Point(50, 40)
        every { SwingUtilities.convertPoint(host, 0, 0, layeredPane) } answers { position }
        val moved = slot<java.awt.event.ComponentListener>()
        every { host.addComponentListener(capture(moved)) } answers { callOriginal() }
        attachOverlay(manager, PROJECT_ID, host)
        val pane = readGlassPane(manager, PROJECT_ID)
        val before = pane.bounds
        val hold = manager.holdUpdates()
        position = Point(150, 140)

        scheduled.toList().forEach(Runnable::run)
        moved.captured.componentMoved(
            java.awt.event.ComponentEvent(host, java.awt.event.ComponentEvent.COMPONENT_MOVED),
        )

        assertEquals(before, pane.bounds)
        assertTrue(hold.needsRefresh)
        hold.release()
        manager.dispose()
    }

    @Test
    fun `queued Power Save cannot target a replacement manager after a hold`() {
        val scheduled = mutableListOf<Runnable>()
        every { SwingUtilities.invokeLater(any()) } answers { scheduled += firstArg<Runnable>() }
        val project = stubProject("power-save-owner")
        val original = GlowOverlayManager(project)
        val replacement = GlowOverlayManager(project)
        val originalPane = mockk<GlowGlassPane>(relaxed = true)
        val replacementPane = mockk<GlowGlassPane>(relaxed = true)
        seedOverlaysMapWithMocks(original, originalPane, mockk(relaxed = true), mockk(relaxed = true))
        seedOverlaysMapWithMocks(replacement, replacementPane, mockk(relaxed = true), mockk(relaxed = true))
        setActiveGlow(original, PROJECT_ID)
        setActiveGlow(replacement, PROJECT_ID)
        every { project.getService(GlowOverlayManager::class.java) } returns original
        every { mockProjectManager.openProjects } returns arrayOf(project)
        every { SwingUtilities.isEventDispatchThread() } returns false
        GlowOverlayManager.broadcastPowerSave(true)
        every { SwingUtilities.isEventDispatchThread() } answers { EventQueue.isDispatchThread() }
        original.holdUpdates().release()
        every { project.getService(GlowOverlayManager::class.java) } returns replacement

        scheduled.single().run()

        verify(exactly = 0) { originalPane.changeWaveformPowerSave(any()) }
        verify(exactly = 0) { replacementPane.changeWaveformPowerSave(any()) }
    }

    @Test
    fun `updateGlow disposes overlays when AyuVariant isAyuActive is false`() {
        // User switches from Ayu to Darcula. Historically `updateGlow` walked
        // the `else DEFAULT_ACCENT_HEX` fallback and kept the orange glow
        // painting on top of Darcula's blue chrome. The guard at the head
        // disposes every overlay so the screen is left in the LAF's natural
        // state.
        //
        // Seed the overlay map with explicit glassPane/host/layeredPane mocks
        // so we can assert `detachOverlayEntry`'s expected side-effects fire —
        // `stopAnimation` on the glassPane, `remove` + `repaint` on the
        // layeredPane. Map-empty stays as the sanity net but is no longer the
        // only signal.
        every { AyuVariant.isAyuActive() } returns false
        every { AyuVariant.detect() } returns null

        val project = stubProject("test-project")
        val manager = GlowOverlayManager(project)

        val glassPane = mockk<GlowGlassPane>(relaxed = true)
        val host = mockk<javax.swing.JComponent>(relaxed = true)
        val layeredPane = mockk<JLayeredPane>(relaxed = true)
        seedOverlaysMapWithMocks(manager, glassPane, host, layeredPane)

        manager.updateGlow()

        val overlaysAfter = readOverlaysMap(manager)
        assertTrue(
            overlaysAfter.isEmpty(),
            "updateGlow with isAyuActive=false MUST leave overlays map empty (disposal contract)",
        )
        // `detachOverlayEntry` side-effects must fire on each entry — proves
        // the disposal path actually walked the map rather than just clearing
        // it.
        verify { glassPane.stopAnimation() }
        verify { layeredPane.remove(glassPane) }
        verify { layeredPane.repaint(any<Int>(), any<Int>(), any<Int>(), any<Int>()) }
    }

    @Test
    fun `updateGlow removes overlays before accent resolution when license is unavailable`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        val project = stubProject("unlicensed-project")
        val manager = GlowOverlayManager(project)
        val glassPane = mockk<GlowGlassPane>(relaxed = true)
        seedOverlaysMapWithMocks(manager, glassPane, mockk(relaxed = true), mockk(relaxed = true))

        manager.updateGlow()

        assertTrue(readOverlaysMap(manager).isEmpty())
        verify(exactly = 0) { AccentResolver.resolve(any(), any<AccentContext>()) }
        verify(exactly = 0) { glassPane.glowColor = any() }
    }

    @Test
    fun `removing overlays disposes the manager animator`() {
        every { LicenseChecker.isLicensedOrGrace() } returns false
        val manager = GlowOverlayManager(stubProject("animator-disposal-project"))
        val animator = mockk<GlowAnimator>(relaxed = true)
        setAnimator(manager, animator)

        manager.updateGlow()

        verify(exactly = 1) { animator.dispose() }
        assertNull(readAnimator(manager))
    }

    @Test
    fun `selecting no animation stops the previous timer on the retained pane`() {
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        every { SwingUtilities.invokeLater(any()) } just Runs
        for (animation in listOf(GlowAnimation.PULSE, GlowAnimation.BREATHE, GlowAnimation.REACTIVE)) {
            val manager = GlowOverlayManager(stubProject("animation-$animation"))
            markManagerWarm(manager)
            val pane = GlowGlassPane(Color.ORANGE, GlowStyle.SOFT, 35, 6)
            seedOverlaysMapWithMocks(manager, pane, mockk(relaxed = true), mockk(relaxed = true))
            setActiveGlow(manager, PROJECT_ID)
            val animator = GlowAnimator()
            animator.start(animation) { alpha -> pane.animationAlpha = alpha }
            val timerField = GlowAnimator::class.java.getDeclaredField("timer").apply { isAccessible = true }
            val timer = timerField.get(animator) as Timer
            setAnimator(manager, animator)
            state.glowAnimation = GlowAnimation.NONE.name

            try {
                manager.updateGlow()

                assertFalse(timer.isRunning, "$animation timer must stop when None is selected")
                assertNull(readAnimator(manager))
                assertSame(pane, readGlassPane(manager, PROJECT_ID))
                assertEquals(1.0f, pane.animationAlpha)
                assertEquals(GlowAnimation.NONE.name, state.glowAnimation)
            } finally {
                manager.dispose()
                pane.stopAnimation()
            }
        }
    }

    @Test
    fun `disabling the active target disposes its animator`() {
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        every { SwingUtilities.invokeLater(any()) } just Runs
        val manager = GlowOverlayManager(stubProject("disabled-target"))
        markManagerWarm(manager)
        seedOverlaysMapWithMocks(manager, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        val animator = mockk<GlowAnimator>(relaxed = true)
        setAnimator(manager, animator)
        setActiveGlow(manager, PROJECT_ID)
        state.glowProject = false

        manager.updateGlow()

        verify(exactly = 1) { animator.dispose() }
        assertNull(readAnimator(manager))
        assertTrue(readOverlaysMap(manager).isEmpty())
        assertFalse(state.glowProject)
        assertTrue(state.glowEnabled)
    }

    @Test
    fun `failed target removal retains the same entry for retry`() {
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        every { SwingUtilities.invokeLater(any()) } just Runs
        val manager = GlowOverlayManager(stubProject("target-removal-retry"))
        markManagerWarm(manager)
        val pane = mockk<GlowGlassPane>(relaxed = true)
        val layeredPane = mockk<JLayeredPane>(relaxed = true)
        seedOverlaysMapWithMocks(manager, pane, mockk(relaxed = true), layeredPane)
        val original = assertNotNull(readOverlaysMap(manager)[PROJECT_ID])
        state.glowProject = false
        every { layeredPane.remove(pane) } throws IllegalStateException("interrupted detach")

        assertFailsWith<IllegalStateException> { manager.updateGlow() }
        assertSame(original, readOverlaysMap(manager)[PROJECT_ID])

        every { layeredPane.remove(pane) } just Runs
        manager.updateGlow()

        verify(exactly = 2) { layeredPane.remove(pane) }
        assertTrue(readOverlaysMap(manager).isEmpty())
        assertFalse(state.glowProject)
    }

    @Test
    fun `cold license restoration initializes subscriptions and schedules overlays`() {
        val project = stubProject("license-restore-project")
        val messageBus = mockk<MessageBus>()
        val connection = mockk<MessageBusConnection>(relaxed = true)
        val hub = mockk<KeystrokeHub>(relaxed = true)
        every { project.messageBus } returns messageBus
        every { messageBus.connect(any<Disposable>()) } returns connection
        every { mockApplication.getService(KeystrokeHub::class.java) } returns hub
        every { SwingUtilities.invokeLater(any()) } just Runs
        val manager = GlowOverlayManager(project)

        every { LicenseChecker.isLicensedOrGrace() } returns false
        manager.initialize()

        verify(exactly = 0) { messageBus.connect(any<Disposable>()) }

        every { LicenseChecker.isLicensedOrGrace() } returns true
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE

        manager.updateGlow()

        verify(exactly = 1) { messageBus.connect(manager) }
        verify(atLeast = 1) { SwingUtilities.invokeLater(any()) }
        verify(exactly = 1) { hub.initialize() }
    }

    @Test
    fun `cold chaotic update starts central routing`() {
        state.glowEnabled = true
        state.glowEditor = true
        state.glowGit = true
        state.glowShape = GlowShape.WAVEFORM.name
        state.waveformDirection = WaveformMovement.CHAOTIC.name
        val project = stubProject("cold-chaotic-project")
        val messageBus = mockk<MessageBus>()
        val connection = mockk<MessageBusConnection>(relaxed = true)
        val hub = mockk<KeystrokeHub>(relaxed = true)
        every { project.messageBus } returns messageBus
        every { messageBus.connect(any<Disposable>()) } returns connection
        every { mockApplication.getService(KeystrokeHub::class.java) } returns hub
        every { SwingUtilities.invokeLater(any()) } just Runs
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        val focusManager = mockk<KeyboardFocusManager>(relaxed = true)
        mockkStatic(KeyboardFocusManager::class)
        every { KeyboardFocusManager.getCurrentKeyboardFocusManager() } returns focusManager
        val manager = GlowOverlayManager(project)
        val commitHost = seedRouteOverlays(manager, project)
        every { focusManager.permanentFocusOwner } returns commitHost

        try {
            manager.updateGlow()

            assertNotNull(readRouteCoordinator(manager))
            assertEquals("Commit", readRouteState(manager).currentSurfaceId)
            assertEquals(1, routeTimerCount(manager))
            assertTrue(readGlassPane(manager, "Editor").hasRouteMode())
            assertTrue(readGlassPane(manager, "Commit").hasRouteMode())
        } finally {
            manager.dispose()
            stopOverlayAnimations(manager)
        }
    }

    @Test
    fun `license downgrade blocks editor and tool window reattach without changing surface preferences`() {
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        val manager = GlowOverlayManager(stubProject("downgraded-overlay-project"))
        seedOverlaysMapWithMocks(
            manager,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            key = "Project",
        )
        every { LicenseChecker.isLicensedOrGrace() } returns false

        manager.updateGlow()
        val host = attachableHost()
        attachOverlay(manager, "Project", host)
        attachOverlay(manager, "Editor", host, isEditorOverlay = true)

        assertTrue(readOverlaysMap(manager).isEmpty())
        assertTrue(state.glowProject)
        assertTrue(state.glowEditor)
    }

    @Test
    fun `disabled glow blocks editor and tool window reattach without changing surface preferences`() {
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        val manager = GlowOverlayManager(stubProject("disabled-overlay-project"))
        seedOverlaysMapWithMocks(
            manager,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            key = "Editor",
        )
        state.glowEnabled = false

        manager.updateGlow()
        val host = attachableHost()
        attachOverlay(manager, "Project", host)
        attachOverlay(manager, "Editor", host, isEditorOverlay = true)

        assertTrue(readOverlaysMap(manager).isEmpty())
        assertFalse(state.glowEnabled)
        assertTrue(state.glowProject)
        assertTrue(state.glowEditor)
    }

    @Test
    fun `failed waveform keeps expanded bounds for aligned solid fallback`() {
        val project = stubProject("waveform-fallback-project")
        val manager = GlowOverlayManager(project)
        val host = mockk<javax.swing.JComponent>(relaxed = true)
        val layeredPane = mockk<JLayeredPane>(relaxed = true)
        val pane =
            GlowGlassPane(
                glowColor = Color(0xFF8F40),
                glowStyle = GlowStyle.SOFT,
                glowIntensity = 80,
                glowWidth = 12,
                isEditorOverlay = false,
            )
        pane.configureWaveform(GlowShape.WAVEFORM, WaveformConfig(amplitude = 16))
        setWaveformFailed(pane)
        every { host.isShowing } returns true
        every { host.width } returns 120
        every { host.height } returns 80
        every { SwingUtilities.convertPoint(host, 0, 0, layeredPane) } returns Point(10, 20)

        invokeUpdateOverlayBounds(manager, pane, host, layeredPane)

        val margin = pane.waveformMargin
        assertEquals(
            Rectangle(10 - margin, 20 - margin, 120 + margin * 2, 80 + margin * 2),
            pane.bounds,
        )
    }

    @Test
    fun `updateGlow pushes waveform shape and effective config to overlays`() {
        every { AyuVariant.isAyuActive() } returns true
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        state.glowShape = GlowShape.WAVEFORM.name
        state.waveformDirection = WaveformMovement.COUNTER_CLOCKWISE.name
        state.waveformBaseline = WaveformBaseline.CENTERED.name
        state.waveformTraceDensity = 99
        state.waveformTraceLength = Int.MAX_VALUE
        state.waveformAmplitude = 99
        state.waveformIntensity = -1
        state.waveformLoopSeconds = 99f
        val project = stubProject("waveform-config-project")
        val manager = GlowOverlayManager(project)
        markManagerWarm(manager)
        val glassPane = mockk<GlowGlassPane>(relaxed = true)
        seedOverlaysMapWithMocks(manager, glassPane, mockk(relaxed = true), mockk(relaxed = true))

        manager.updateGlow()

        verify(exactly = 1) {
            glassPane.configureWaveform(
                GlowShape.WAVEFORM,
                WaveformConfig(
                    movement = WaveformMovement.COUNTER_CLOCKWISE,
                    baseline = WaveformBaseline.CENTERED,
                    traceDensity = 4,
                    traceLength = MAX_TRACE_LENGTH,
                    amplitude = 40,
                    intensity = 0,
                    loopSeconds = 40f,
                ),
            )
        }
    }

    @Test
    fun `keystroke reaches only the active waveform overlay`() {
        state.glowShape = GlowShape.WAVEFORM.name
        val project = stubProject("typing-project")
        val manager = GlowOverlayManager(project)
        val active = mockk<GlowGlassPane>(relaxed = true)
        val inactive = mockk<GlowGlassPane>(relaxed = true)
        every { active.isWaveform } returns true
        seedOverlaysMapWithMocks(manager, active, mockk(relaxed = true), mockk(relaxed = true), key = "active")
        seedOverlaysMapWithMocks(manager, inactive, mockk(relaxed = true), mockk(relaxed = true), key = "inactive")
        setActiveGlow(manager)

        manager.input.onKeystroke()

        verify(exactly = 1) { active.onWaveformKeystroke(any()) }
        verify(exactly = 0) { inactive.onWaveformKeystroke(any()) }
    }

    @Test
    fun `failed waveform routes keystrokes to the reactive fallback animator`() {
        state.glowShape = GlowShape.WAVEFORM.name
        state.glowAnimation = GlowAnimation.REACTIVE.name
        val manager = GlowOverlayManager(stubProject("waveform-fallback-input-project"))
        val active = mockk<GlowGlassPane>(relaxed = true)
        every { active.isWaveform } returns false
        seedOverlaysMapWithMocks(manager, active, mockk(relaxed = true), mockk(relaxed = true), key = "active")
        setActiveGlow(manager)

        manager.input.onKeystroke()

        val fallbackAnimator = readAnimator(manager) ?: error("Reactive fallback animator was not started")
        verify(exactly = 0) { active.onWaveformKeystroke(any()) }
        assertTrue(fallbackAnimator.reactiveBoost > 0.0f)

        every { LicenseChecker.isLicensedOrGrace() } returns false
        manager.updateGlow()
    }

    @Test
    fun `Power Save fanout reaches every open project`() {
        val project1 = stubProject("power-save-1")
        val project2 = stubProject("power-save-2")
        val manager1 = mockk<GlowOverlayManager>()
        val manager2 = mockk<GlowOverlayManager>()
        val input1 = mockk<GlowInputSink>(relaxed = true)
        val input2 = mockk<GlowInputSink>(relaxed = true)
        every { mockProjectManager.openProjects } returns arrayOf(project1, project2)
        every { project1.getService(GlowOverlayManager::class.java) } returns manager1
        every { project2.getService(GlowOverlayManager::class.java) } returns manager2
        every { manager1.input } returns input1
        every { manager2.input } returns input2

        GlowOverlayManager.broadcastPowerSave(enabled = true)

        verify(exactly = 1) { input1.onPowerSaveChanged(true) }
        verify(exactly = 1) { input2.onPowerSaveChanged(true) }
    }

    @Test
    fun `Power Save reaches the active waveform through the real input sink`() {
        val manager = GlowOverlayManager(stubProject("power-save-input-project"))
        val active = mockk<GlowGlassPane>(relaxed = true)
        seedOverlaysMapWithMocks(manager, active, mockk(relaxed = true), mockk(relaxed = true), key = "active")
        setActiveGlow(manager)

        manager.input.onPowerSaveChanged(enabled = true)

        verify(exactly = 1) { active.changeWaveformPowerSave(true) }
    }

    @Test
    fun `focus handoff deactivates old waveform and activates only the new overlay`() {
        state.glowShape = GlowShape.WAVEFORM.name
        val project = stubProject("focus-project")
        val manager = GlowOverlayManager(project)
        val old = mockk<GlowGlassPane>(relaxed = true)
        val new = mockk<GlowGlassPane>(relaxed = true)
        every { old.isWaveform } returns true
        every { new.isWaveform } returns true
        seedOverlaysMapWithMocks(manager, old, mockk(relaxed = true), mockk(relaxed = true), key = "old")
        seedOverlaysMapWithMocks(manager, new, mockk(relaxed = true), mockk(relaxed = true), key = "new")

        invokeMoveGlowFocus(manager)

        verify(exactly = 1) { old.deactivateWaveform() }
        verify(exactly = 1) { new.activateWaveform(powerSaveEnabled = false) }
        verify(exactly = 0) { old.activateWaveform(any()) }
        verify(exactly = 1) { old.startFadeOut() }
        verify(exactly = 1) { new.startFadeIn() }
    }

    @Test
    fun `chaotic movement owns one central timer and disables pane timers`() {
        state.glowEnabled = true
        state.glowEditor = true
        state.glowGit = true
        state.glowShape = GlowShape.WAVEFORM.name
        state.waveformDirection = WaveformMovement.CHAOTIC.name
        val project = stubProject("chaotic-route-project")
        val manager = GlowOverlayManager(project)
        seedRouteOverlays(manager, project)
        markManagerWarm(manager)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE

        try {
            manager.updateGlow()
            rebuildRouteGraph(manager)

            assertNotNull(readRouteCoordinator(manager))
            assertEquals(1, routeTimerCount(manager))
            assertFalse(readGlassPane(manager, "Editor").hasWaveformTimer())
            assertFalse(readGlassPane(manager, "Commit").hasWaveformTimer())
        } finally {
            manager.dispose()
            stopOverlayAnimations(manager)
        }
    }

    @Test
    fun `entering chaotic routing restores full animation alpha`() {
        state.glowEnabled = true
        state.glowEditor = true
        state.glowGit = true
        state.glowShape = GlowShape.WAVEFORM.name
        state.waveformDirection = WaveformMovement.CHAOTIC.name
        val project = stubProject("chaotic-animation-alpha-project")
        val manager = GlowOverlayManager(project)
        seedRouteOverlays(manager, project)
        markManagerWarm(manager)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        val editor = readGlassPane(manager, "Editor")
        val commit = readGlassPane(manager, "Commit")
        editor.animationAlpha = 0.25f
        commit.animationAlpha = 0.5f

        try {
            manager.updateGlow()

            assertEquals(1f, editor.animationAlpha)
            assertEquals(1f, commit.animationAlpha)
        } finally {
            manager.dispose()
            stopOverlayAnimations(manager)
        }
    }

    @Test
    fun `chaotic route graph uses screen coordinates and filters unavailable islands`() {
        state.glowEnabled = true
        state.glowEditor = true
        state.glowGit = true
        state.glowShape = GlowShape.WAVEFORM.name
        state.waveformDirection = WaveformMovement.CHAOTIC.name
        val project = stubProject("chaotic-graph-project")
        val manager = GlowOverlayManager(project)
        seedRouteOverlays(manager, project)
        markManagerWarm(manager)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE

        try {
            manager.updateGlow()
            rebuildRouteGraph(manager)

            val graph = requireNotNull(readRouteGraph(manager))
            val commitTrack = graph.surfaces.getValue("Commit").track
            assertTrue(commitTrack.samples.minOf { sample -> sample.x } >= 408f)

            val commitHost = readOverlayHost(manager, "Commit")
            every { commitHost.isShowing } returns false
            rebuildRouteGraph(manager)
            assertFalse(requireNotNull(readRouteGraph(manager)).surfaces.containsKey("Commit"))

            val editorHost = readOverlayHost(manager, "Editor")
            every { editorHost.isShowing } returns false
            rebuildRouteGraph(manager)
            assertTrue(requireNotNull(readRouteGraph(manager)).surfaces.isEmpty())
            assertEquals(0, routeTimerCount(manager))
            assertTrue(readRouteLayers(manager).isEmpty())

            every { editorHost.isShowing } returns true
            every { commitHost.isShowing } returns true
            state.glowGit = false
            rebuildRouteGraph(manager)
            assertFalse(requireNotNull(readRouteGraph(manager)).surfaces.containsKey("Commit"))
        } finally {
            manager.dispose()
            stopOverlayAnimations(manager)
        }
    }

    @Test
    fun `chaotic mode keeps every enabled island base visible`() {
        state.glowEnabled = true
        state.glowEditor = true
        state.glowGit = true
        state.glowShape = GlowShape.WAVEFORM.name
        state.waveformDirection = WaveformMovement.CHAOTIC.name
        val project = stubProject("chaotic-base-visibility-project")
        val manager = GlowOverlayManager(project)
        seedRouteOverlays(manager, project)
        markManagerWarm(manager)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE

        try {
            manager.updateGlow()
            rebuildRouteGraph(manager)

            val editor = readGlassPane(manager, "Editor")
            val commit = readGlassPane(manager, "Commit")
            editor.advanceFade()
            commit.advanceFade()

            assertTrue(editor.readFadeAlpha() > 0f)
            assertTrue(commit.readFadeAlpha() > 0f)
        } finally {
            manager.dispose()
            stopOverlayAnimations(manager)
        }
    }

    @Test
    fun `chaotic handoff keeps island base opacity stable`() {
        state.glowEnabled = true
        state.glowEditor = true
        state.glowGit = true
        state.glowShape = GlowShape.WAVEFORM.name
        state.waveformDirection = WaveformMovement.CHAOTIC.name
        state.waveformLoopSeconds = 1.5f
        val project = stubProject("chaotic-handoff-opacity-project")
        val manager = GlowOverlayManager(project)
        seedRouteOverlays(manager, project)
        markManagerWarm(manager)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE

        try {
            manager.updateGlow()
            rebuildRouteGraph(manager)

            val editor = readGlassPane(manager, "Editor")
            val commit = readGlassPane(manager, "Commit")
            editor.setFadeAlpha(1f)
            commit.setFadeAlpha(1f)
            val initialSurface = readRouteState(manager).currentSurfaceId
            val controller = readRouteController(manager)
            stopRouteTimer(manager)
            assertTrue(controller.handle(RouteEvent.Tick(0L)))

            var surfaceChanged = false
            for (tick in 1L..100L) {
                assertTrue(controller.handle(RouteEvent.Tick(tick * 100L)))
                editor.advanceFade()
                commit.advanceFade()
                assertEquals(1f, editor.readFadeAlpha(), 0.001f)
                assertEquals(1f, commit.readFadeAlpha(), 0.001f)
                if (readRouteState(manager).currentSurfaceId != initialSurface) {
                    surfaceChanged = true
                    break
                }
            }

            assertTrue(surfaceChanged, "Expected the chaotic route to hand off between islands")
        } finally {
            manager.dispose()
            stopOverlayAnimations(manager)
        }
    }

    @Test
    fun `Power Save suspends and resumes the central chaotic timer`() {
        state.glowEnabled = true
        state.glowEditor = true
        state.glowGit = true
        state.glowShape = GlowShape.WAVEFORM.name
        state.waveformDirection = WaveformMovement.CHAOTIC.name
        val project = stubProject("chaotic-power-save-project")
        val manager = GlowOverlayManager(project)
        seedRouteOverlays(manager, project)
        markManagerWarm(manager)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE

        try {
            manager.updateGlow()
            rebuildRouteGraph(manager)
            manager.input.onKeystroke()
            manager.input.onPowerSaveChanged(enabled = true)

            assertEquals(0, routeTimerCount(manager))
            assertFalse(readGlassPane(manager, "Editor").hasWaveformTimer())
            assertFalse(readGlassPane(manager, "Commit").hasWaveformTimer())

            manager.input.onPowerSaveChanged(enabled = false)
            assertEquals(1, routeTimerCount(manager))
        } finally {
            manager.dispose()
            stopOverlayAnimations(manager)
        }
    }

    @Test
    fun `global application activation suspends and resumes the central chaotic timer`() {
        state.glowEnabled = true
        state.glowEditor = true
        state.glowGit = true
        state.glowShape = GlowShape.WAVEFORM.name
        state.waveformDirection = WaveformMovement.CHAOTIC.name
        val project = stubProject("chaotic-application-project")
        val manager = GlowOverlayManager(project)
        seedRouteOverlays(manager, project)
        markManagerWarm(manager)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        val frame = mockk<IdeFrame>()
        every { frame.project } returns stubProject("other-project")

        try {
            manager.updateGlow()
            rebuildRouteGraph(manager)
            val listener = readActivationListener(manager)

            listener.applicationDeactivated(frame)
            assertEquals(0, routeTimerCount(manager))

            listener.applicationActivated(frame)
            assertEquals(1, routeTimerCount(manager))
        } finally {
            manager.dispose()
            stopOverlayAnimations(manager)
        }
    }

    @Test
    fun `chaotic route starts suspended while the application is inactive`() {
        state.glowEnabled = true
        state.glowEditor = true
        state.glowGit = true
        state.glowShape = GlowShape.WAVEFORM.name
        state.waveformDirection = WaveformMovement.CHAOTIC.name
        val project = stubProject("inactive-chaotic-project")
        val manager = GlowOverlayManager(project)
        seedRouteOverlays(manager, project)
        markManagerWarm(manager)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        every { mockApplication.isActive } returns false

        try {
            manager.updateGlow()
            rebuildRouteGraph(manager)

            assertEquals(0, routeTimerCount(manager))
        } finally {
            manager.dispose()
            stopOverlayAnimations(manager)
        }
    }

    @Test
    fun `late overlay joins active chaotic route mode`() {
        state.glowEnabled = true
        state.glowEditor = true
        state.glowGit = true
        state.glowShape = GlowShape.WAVEFORM.name
        state.waveformDirection = WaveformMovement.CHAOTIC.name
        val project = stubProject("late-chaotic-overlay-project")
        val manager = GlowOverlayManager(project)
        seedRouteOverlays(manager, project)
        markManagerWarm(manager)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE

        try {
            manager.updateGlow()
            rebuildRouteGraph(manager)

            attachOverlay(manager, LATE_OVERLAY_KEY, attachableHost())

            assertTrue(lateOverlayPane(manager).hasRouteMode())
        } finally {
            manager.dispose()
            stopOverlayAnimations(manager)
        }
    }

    @Test
    fun `leaving chaotic movement restores active and inactive fades`() {
        state.glowEnabled = true
        state.glowEditor = true
        state.glowGit = true
        state.glowShape = GlowShape.WAVEFORM.name
        state.waveformDirection = WaveformMovement.CHAOTIC.name
        val project = stubProject("chaotic-exit-project")
        val manager = GlowOverlayManager(project)
        seedRouteOverlays(manager, project)
        setActiveGlow(manager, "Editor")
        markManagerWarm(manager)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE

        try {
            manager.updateGlow()
            rebuildRouteGraph(manager)
            val editor = readGlassPane(manager, "Editor")
            val commit = readGlassPane(manager, "Commit")
            editor.stopAnimation()
            commit.stopAnimation()
            editor.setFadeAlpha(0.5f)
            commit.setFadeAlpha(0.5f)

            state.waveformDirection = WaveformMovement.CLOCKWISE.name
            manager.updateGlow()

            editor.advanceFade()
            commit.advanceFade()
            assertTrue(editor.readFadeAlpha() > 0.5f)
            assertTrue(commit.readFadeAlpha() < 0.5f)
            assertFalse(editor.hasRouteMode())
            assertFalse(commit.hasRouteMode())
        } finally {
            manager.dispose()
            stopOverlayAnimations(manager)
        }
    }

    @Test
    fun `terminal route failure releases routing and restores pane focus`() {
        state.glowEnabled = true
        state.glowEditor = true
        state.glowGit = true
        state.glowShape = GlowShape.WAVEFORM.name
        state.waveformDirection = WaveformMovement.CHAOTIC.name
        val project = stubProject("chaotic-failure-project")
        val manager = GlowOverlayManager(project)
        seedRouteOverlays(manager, project)
        setActiveGlow(manager, "Editor")
        markManagerWarm(manager)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        val layeredPane = readEditorLayer(manager)
        every { layeredPane.add(any<java.awt.Component>()) } throws RuntimeException("route layer failure")
        val editor = readGlassPane(manager, "Editor")
        val commit = readGlassPane(manager, "Commit")
        editor.setFadeAlpha(0.5f)
        commit.setFadeAlpha(0.5f)

        try {
            manager.updateGlow()

            assertNull(readRouteCoordinator(manager))
            assertEquals(0, routeTimerCount(manager))
            assertFalse(editor.hasRouteMode())
            assertFalse(commit.hasRouteMode())
            editor.advanceFade()
            commit.advanceFade()
            assertTrue(editor.readFadeAlpha() > 0.5f)
            assertTrue(commit.readFadeAlpha() < 0.5f)
        } finally {
            manager.dispose()
            stopOverlayAnimations(manager)
        }
    }

    @Test
    fun `entering chaotic movement fades an unavailable overlay out`() {
        state.glowEnabled = true
        state.glowEditor = true
        state.glowGit = true
        state.glowShape = GlowShape.WAVEFORM.name
        state.waveformDirection = WaveformMovement.CHAOTIC.name
        val project = stubProject("chaotic-entry-project")
        val manager = GlowOverlayManager(project)
        seedRouteOverlays(manager, project)
        setActiveGlow(manager, "Commit")
        markManagerWarm(manager)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        val commitHost = readOverlayHost(manager, "Commit")
        every { commitHost.isShowing } returns false
        val commit = readGlassPane(manager, "Commit")
        commit.setFadeAlpha(1f)

        try {
            manager.updateGlow()
            commit.advanceFade()

            assertTrue(commit.readFadeAlpha() < 1f)
            assertTrue(commit.hasRouteMode())
        } finally {
            manager.dispose()
            stopOverlayAnimations(manager)
        }
    }

    @Test
    fun `failed connector stays excluded after a graph rebuild`() {
        state.glowEnabled = true
        state.glowEditor = true
        state.glowGit = true
        state.glowShape = GlowShape.WAVEFORM.name
        state.waveformDirection = WaveformMovement.CHAOTIC.name
        val project = stubProject("chaotic-bridge-failure-project")
        val manager = GlowOverlayManager(project)
        seedRouteOverlays(manager, project)
        markManagerWarm(manager)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE

        try {
            manager.updateGlow()
            rebuildRouteGraph(manager)
            val connectorId =
                requireNotNull(
                    requireNotNull(readRouteGraph(manager))
                        .connectors
                        .values
                        .flatten()
                        .firstOrNull(),
                ).id

            recordBridgeFailure(manager, connectorId)
            assertTrue(
                requireNotNull(readRouteGraph(manager))
                    .connectors
                    .values
                    .flatten()
                    .none { connector -> connector.id == connectorId },
            )
            rebuildRouteGraph(manager)

            assertTrue(
                requireNotNull(readRouteGraph(manager))
                    .connectors
                    .values
                    .flatten()
                    .none { connector -> connector.id == connectorId },
            )
        } finally {
            manager.dispose()
            stopOverlayAnimations(manager)
        }
    }

    @Test
    fun `route graph refresh is debounced and disposal clears route resources`() {
        state.glowEnabled = true
        state.glowEditor = true
        state.glowGit = true
        state.glowShape = GlowShape.WAVEFORM.name
        state.waveformDirection = WaveformMovement.CHAOTIC.name
        val project = stubProject("chaotic-disposal-project")
        val manager = GlowOverlayManager(project)
        seedRouteOverlays(manager, project)
        markManagerWarm(manager)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE

        manager.updateGlow()
        rebuildRouteGraph(manager)
        scheduleGraphRefresh(manager)
        val layoutTimer = requireNotNull(readLayoutTimer(manager))
        scheduleGraphRefresh(manager)

        assertSame(layoutTimer, readLayoutTimer(manager))
        assertFalse(layoutTimer.isRepeats)
        assertEquals(80, layoutTimer.delay)
        assertEquals(1, readRouteLayers(manager).size)

        manager.dispose()

        assertNull(readRouteCoordinator(manager))
        assertEquals(0, routeTimerCount(manager))
        assertNull(readLayoutTimer(manager))
        assertTrue(readRouteLayers(manager).isEmpty())
        assertTrue(readRouteBridges(manager).isEmpty())
        stopOverlayAnimations(manager)
    }

    @Test
    fun `focus changes do not reroute an active chaotic waveform`() {
        state.glowEnabled = true
        state.glowEditor = true
        state.glowGit = true
        state.glowShape = GlowShape.WAVEFORM.name
        state.waveformDirection = WaveformMovement.CHAOTIC.name
        val project = stubProject("chaotic-focus-project")
        val manager = GlowOverlayManager(project)
        seedRouteOverlays(manager, project)
        markManagerWarm(manager)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE

        try {
            manager.updateGlow()
            rebuildRouteGraph(manager)
            val before = readRouteState(manager)

            invokeMoveGlowFocus(manager, from = "Editor", to = "Commit")

            assertEquals(before.currentSurfaceId, readRouteState(manager).currentSurfaceId)
            assertEquals(before.distanceOnLeg, readRouteState(manager).distanceOnLeg, 0.001f)
        } finally {
            manager.dispose()
            stopOverlayAnimations(manager)
        }
    }

    @Test
    fun `fixed movement retains active pane focus handoff`() {
        state.waveformDirection = WaveformMovement.CLOCKWISE.name
        val manager = GlowOverlayManager(stubProject("fixed-focus-project"))
        val editor = configuredPane(WaveformMovement.CLOCKWISE)
        val commit = configuredPane(WaveformMovement.CLOCKWISE)
        seedOverlaysMapWithMocks(manager, editor, mockk(relaxed = true), mockk(relaxed = true), "Editor")
        seedOverlaysMapWithMocks(manager, commit, mockk(relaxed = true), mockk(relaxed = true), "Commit")
        markManagerWarm(manager)

        try {
            invokeMoveGlowFocus(manager, from = "Editor", to = "Commit")

            assertFalse(readGlassPane(manager, "Editor").hasWaveformTimer())
            assertTrue(readGlassPane(manager, "Commit").hasWaveformTimer())
            assertNull(readRouteCoordinator(manager))
        } finally {
            stopOverlayAnimations(manager)
        }
    }

    @Test
    fun `application focus changes freeze and resume every active waveform`() {
        state.glowShape = GlowShape.WAVEFORM.name
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        every { SwingUtilities.invokeLater(any()) } just Runs
        val project = stubProject("application-focus-project")
        val manager = GlowOverlayManager(project)
        val projectBus = mockk<MessageBus>()
        val projectConnection = mockk<MessageBusConnection>(relaxed = true)
        every { project.messageBus } returns projectBus
        every { projectBus.connect(any<Disposable>()) } returns projectConnection
        val applicationBus = mockk<MessageBus>()
        val applicationConnection = mockk<MessageBusConnection>(relaxed = true)
        every { mockApplication.messageBus } returns applicationBus
        every { applicationBus.connect(manager) } returns applicationConnection
        val activationListener = slot<ApplicationActivationListener>()
        every {
            applicationConnection.subscribe(
                eq(ApplicationActivationListener.TOPIC),
                capture(activationListener),
            )
        } just Runs
        every { mockApplication.getService(KeystrokeHub::class.java) } returns mockk(relaxed = true)
        val pane = mockk<GlowGlassPane>(relaxed = true)
        every { pane.isWaveform } returns true
        seedOverlaysMapWithMocks(manager, pane, mockk(relaxed = true), mockk(relaxed = true), key = "active")
        setActiveGlow(manager)
        val matchingFrame = mockk<IdeFrame>()
        every { matchingFrame.project } returns project

        manager.initialize()
        activationListener.captured.applicationDeactivated(matchingFrame)
        activationListener.captured.applicationActivated(matchingFrame)

        verify(exactly = 1) { pane.deactivateWaveform() }
        verify(exactly = 1) { pane.activateWaveform(powerSaveEnabled = false) }

        val otherFrame = mockk<IdeFrame>()
        every { otherFrame.project } returns stubProject("other-project")
        activationListener.captured.applicationDeactivated(otherFrame)
        activationListener.captured.applicationActivated(otherFrame)
        verify(exactly = 2) { pane.deactivateWaveform() }
        verify(exactly = 2) { pane.activateWaveform(powerSaveEnabled = false) }

        val solidPane = mockk<GlowGlassPane>(relaxed = true)
        every { solidPane.isWaveform } returns false
        seedOverlaysMapWithMocks(manager, solidPane, mockk(relaxed = true), mockk(relaxed = true), key = "active")
        activationListener.captured.applicationDeactivated(matchingFrame)
        activationListener.captured.applicationActivated(matchingFrame)
        verify(exactly = 0) { solidPane.deactivateWaveform() }
        verify(exactly = 0) { solidPane.activateWaveform(any()) }
    }

    @Test
    fun `waveform overlay expands outward while solid bounds stay unchanged`() {
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        every { SwingUtilities.invokeLater(any()) } answers { firstArg<Runnable>().run() }
        val project = stubProject("bounds-project")
        val host = spyk(JPanel(null))
        val rootPane = mockk<javax.swing.JRootPane>(relaxed = true)
        val layeredPane = JLayeredPane()
        every { host.width } returns 120
        every { host.height } returns 80
        every { host.isShowing } returns true
        every { rootPane.layeredPane } returns layeredPane
        every { SwingUtilities.getRootPane(host) } returns rootPane
        every { SwingUtilities.convertPoint(host, 0, 0, layeredPane) } returns Point(50, 40)

        val solidManager = GlowOverlayManager(project)
        attachOverlay(solidManager, "solid", host)
        assertEquals(Rectangle(50, 40, 120, 80), readGlassPane(solidManager, "solid").bounds)

        state.glowShape = GlowShape.WAVEFORM.name
        state.waveformAmplitude = 10
        val waveformManager = GlowOverlayManager(project)
        attachOverlay(waveformManager, "waveform", host)
        val margin = WaveformPainter.marginFor(10, state.getWidthForStyle(GlowStyle.SOFT)).toInt()
        assertEquals(
            Rectangle(50 - margin, 40 - margin, 120 + margin * 2, 80 + margin * 2),
            readGlassPane(waveformManager, "waveform").bounds,
        )
    }

    @Test
    fun `editor attach reports missing tab hierarchy only after settling`() {
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        val scheduled = mutableListOf<Runnable>()
        every { SwingUtilities.invokeLater(any()) } answers { scheduled += firstArg<Runnable>() }
        val project = stubProject("settling-editor-project")
        val editorManager = mockk<FileEditorManager>()
        mockkStatic(FileEditorManager::class)
        every { FileEditorManager.getInstance(project) } returns editorManager
        every { editorManager.selectedEditor } returns mockk()
        val host = SettlingEditorHost().apply { setSize(800, 600) }
        val rootPane = mockk<javax.swing.JRootPane>(relaxed = true)
        val layeredPane = JLayeredPane()
        every { rootPane.layeredPane } returns layeredPane
        every { SwingUtilities.getRootPane(host) } returns rootPane
        every { SwingUtilities.convertPoint(host, 0, 0, layeredPane) } returns Point(0, 0)
        val warnings = mutableListOf<String>()
        val processor =
            object : LoggedErrorProcessor() {
                override fun processWarn(
                    category: String,
                    message: String,
                    throwable: Throwable?,
                ): Boolean {
                    if (category.contains("EditorTabGeometry")) warnings += message
                    return false
                }
            }

        LoggedErrorProcessor.executeWith<RuntimeException>(processor) {
            attachOverlay(GlowOverlayManager(project), "settling-editor", host, isEditorOverlay = true)
            assertTrue(warnings.isEmpty(), "initial fallback must remain silent: $warnings")
            assertEquals(1, scheduled.size)

            scheduled.removeAt(0).run()
            assertTrue(warnings.isEmpty(), "first post-layout correction must remain silent: $warnings")
            assertEquals(1, scheduled.size)

            scheduled.removeAt(0).run()
            assertEquals(1, warnings.size, "settled fallback must retain one actionable warning")
        }
    }

    @Test
    fun `editor settling stops after manager disposal`() {
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        val scheduled = mutableListOf<Runnable>()
        every { SwingUtilities.invokeLater(any()) } answers { scheduled += firstArg<Runnable>() }
        val project = stubProject("disposed-settling-project")
        val editorManager = mockk<FileEditorManager>()
        mockkStatic(FileEditorManager::class)
        every { FileEditorManager.getInstance(project) } returns editorManager
        every { editorManager.selectedEditor } returns mockk()
        val host = SettlingEditorHost().apply { setSize(800, 600) }
        val rootPane = mockk<javax.swing.JRootPane>(relaxed = true)
        val layeredPane = JLayeredPane()
        every { rootPane.layeredPane } returns layeredPane
        every { SwingUtilities.getRootPane(host) } returns rootPane
        every { SwingUtilities.convertPoint(host, 0, 0, layeredPane) } returns Point(0, 0)
        val manager = GlowOverlayManager(project)

        attachOverlay(manager, "disposed-settling-editor", host, isEditorOverlay = true)
        assertEquals(1, scheduled.size)

        scheduled.removeAt(0).run()
        assertEquals(1, scheduled.size)
        manager.dispose()

        scheduled.removeAt(0).run()

        verify(exactly = 2) { SwingUtilities.convertPoint(host, 0, 0, layeredPane) }
    }

    @Test
    fun `waveform directs clipped edges inward and clears them when space returns`() {
        val project = stubProject("clipped-waveform-project")
        val manager = GlowOverlayManager(project)
        val host = mockk<javax.swing.JComponent>(relaxed = true)
        val layeredPane = mockk<JLayeredPane>(relaxed = true)
        val pane =
            GlowGlassPane(
                glowColor = Color(0x5CCFE6),
                glowStyle = GlowStyle.SHARP_NEON,
                glowIntensity = 100,
                glowWidth = 4,
            )
        pane.configureWaveform(GlowShape.WAVEFORM, WaveformConfig(amplitude = 24))
        every { host.isShowing } returns true
        every { host.width } returns 120
        every { host.height } returns 80
        every { SwingUtilities.convertPoint(host, 0, 0, layeredPane) } returns Point(0, 0)
        every { layeredPane.visibleRect } returns Rectangle(0, 0, 120, 80)

        invokeUpdateOverlayBounds(manager, pane, host, layeredPane)

        assertEquals(WaveformEdge.entries.toSet(), pane.waveformInwardEdges)

        every { layeredPane.visibleRect } returns Rectangle(0, 0, 240, 200)
        val singletonCases =
            listOf(
                WaveformEdge.TOP to Point(60, 0),
                WaveformEdge.RIGHT to Point(120, 60),
                WaveformEdge.BOTTOM to Point(60, 120),
                WaveformEdge.LEFT to Point(0, 60),
            )
        for ((edge, point) in singletonCases) {
            every { SwingUtilities.convertPoint(host, 0, 0, layeredPane) } returns point
            invokeUpdateOverlayBounds(manager, pane, host, layeredPane)
            assertEquals(setOf(edge), pane.waveformInwardEdges)
        }

        every { SwingUtilities.convertPoint(host, 0, 0, layeredPane) } returns Point(60, 60)
        invokeUpdateOverlayBounds(manager, pane, host, layeredPane)

        assertTrue(pane.waveformInwardEdges.isEmpty())
    }

    @Test
    fun `existing editor overlay refreshes top spans when editor selection changes`() {
        val project = stubProject("editor-tab-geometry-project")
        val manager = GlowOverlayManager(project)
        val editorManager = mockk<FileEditorManager>()
        val host = mockk<javax.swing.JComponent>(relaxed = true)
        val layeredPane = mockk<JLayeredPane>(relaxed = true)
        val pane =
            GlowGlassPane(
                glowColor = Color(0x5CCFE6),
                glowStyle = GlowStyle.SOFT,
                glowIntensity = 80,
                glowWidth = 4,
                isEditorOverlay = true,
            )
        pane.configureWaveform(GlowShape.WAVEFORM, WaveformConfig())
        mockkStatic(FileEditorManager::class)
        every { FileEditorManager.getInstance(project) } returns editorManager
        every { editorManager.selectedEditor } returns mockk()
        every { host.isShowing } returns true
        every { SwingUtilities.convertPoint(host, 0, 0, layeredPane) } returns Point(10, 20)
        mockkObject(EditorTabGeometry)
        every { EditorTabGeometry.editorOverlayGeometry(host, any()) } returns
            EditorOverlayGeometry(Rectangle(0, 28, 120, 80), listOf(0..72))
        seedOverlaysMapWithMocks(manager, pane, host, layeredPane, key = "Editor")

        invokeAttachEditorOverlayIfNeeded(manager)

        assertEquals(listOf(0..72), pane.waveformTopSpans)
    }

    @Test
    fun `empty editor attaches`() {
        state.glowEditor = true
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        every { SwingUtilities.invokeLater(any()) } answers { firstArg<Runnable>().run() }

        val project = stubProject("empty-editor-project")
        val manager = GlowOverlayManager(project)
        val extendedManager = mockk<FileEditorManagerEx>()
        val editorRoot = mockk<javax.swing.JComponent>()
        val host = spyk(JPanel(null))
        val rootPane = mockk<javax.swing.JRootPane>()
        val layeredPane = JLayeredPane()

        mockkStatic(FileEditorManager::class)
        mockkStatic(FileEditorManagerEx::class)
        mockkObject(ComponentHierarchyUtils)
        every { FileEditorManager.getInstance(project) } returns extendedManager
        every { extendedManager.selectedEditor } returns null
        every { FileEditorManagerEx.getInstanceEx(project) } returns extendedManager
        every { extendedManager.component } returns editorRoot
        every { ComponentHierarchyUtils.findEditorHost(editorRoot) } returns host
        every { host.width } returns 320
        every { host.height } returns 240
        every { host.isShowing } returns true
        every { host.isDisplayable } returns true
        every { rootPane.layeredPane } returns layeredPane
        every { SwingUtilities.getRootPane(host) } returns rootPane
        every { SwingUtilities.convertPoint(host, 0, 0, layeredPane) } returns Point(0, 0)
        val warnings = mutableListOf<String>()
        val processor =
            object : LoggedErrorProcessor() {
                override fun processWarn(
                    category: String,
                    message: String,
                    throwable: Throwable?,
                ): Boolean {
                    if (category.contains("EditorTabGeometry")) warnings += message
                    return false
                }
            }

        LoggedErrorProcessor.executeWith<RuntimeException>(processor) {
            invokeAttachEditorOverlayIfNeeded(manager)
        }

        assertTrue(readOverlaysMap(manager).containsKey("Editor"))
        assertTrue(warnings.isEmpty(), "an empty editor has no tab hierarchy to warn about: $warnings")
    }

    @Test
    fun `selected editor wins`() {
        state.glowEditor = true
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        every { SwingUtilities.invokeLater(any()) } answers { firstArg<Runnable>().run() }

        val project = stubProject("selected-editor-project")
        val manager = GlowOverlayManager(project)
        val extendedManager = mockk<FileEditorManagerEx>()
        val selectedEditor = mockk<FileEditor>()
        val selectedRoot = mockk<javax.swing.JComponent>()
        val mainRoot = mockk<javax.swing.JComponent>()
        val host = spyk(JPanel(null))
        val rootPane = mockk<javax.swing.JRootPane>()
        val layeredPane = JLayeredPane()

        mockkStatic(FileEditorManager::class)
        mockkStatic(FileEditorManagerEx::class)
        mockkObject(ComponentHierarchyUtils)
        every { FileEditorManager.getInstance(project) } returns extendedManager
        every { extendedManager.selectedEditor } returns selectedEditor
        every { selectedEditor.component } returns selectedRoot
        every { FileEditorManagerEx.getInstanceEx(project) } returns extendedManager
        every { extendedManager.component } returns mainRoot
        every { ComponentHierarchyUtils.findEditorHost(selectedRoot) } returns host
        every { ComponentHierarchyUtils.findEditorHost(mainRoot) } returns host
        every { host.width } returns 320
        every { host.height } returns 240
        every { host.isShowing } returns true
        every { host.isDisplayable } returns true
        every { rootPane.layeredPane } returns layeredPane
        every { SwingUtilities.getRootPane(host) } returns rootPane
        every { SwingUtilities.convertPoint(host, 0, 0, layeredPane) } returns Point(0, 0)

        invokeAttachEditorOverlayIfNeeded(manager)

        verify(exactly = 1) { ComponentHierarchyUtils.findEditorHost(selectedRoot) }
        verify(exactly = 0) { ComponentHierarchyUtils.findEditorHost(mainRoot) }
    }

    @Test
    fun `updateGlow continues to paint when AyuVariant isAyuActive is true`() {
        // Sanity: when the user IS on Ayu, the guard does NOT short-circuit and
        // the rest of the method runs. We only assert the guard didn't dispose —
        // overlay attachment requires a live Swing tree which is out of scope
        // for this unit test.
        every { AyuVariant.isAyuActive() } returns true
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE

        val project = stubProject("test-project")
        val manager = GlowOverlayManager(project)
        markManagerWarm(manager)

        // Seed the overlays map with a sentinel so we can prove the guard did
        // NOT dispose. If updateGlow walked the disposal path, the sentinel
        // would be cleared.
        val sentinelKey = PROJECT_ID
        seedOverlaysMap(manager, sentinelKey)

        manager.updateGlow()

        val overlaysAfter = readOverlaysMap(manager)
        assertFalse(
            overlaysAfter.isEmpty() && !overlaysAfter.containsKey(sentinelKey),
            "updateGlow with isAyuActive=true MUST NOT dispose pre-existing overlays",
        )
    }

    @Test
    fun `updateGlow continues to paint when external context is active`() {
        state.externalThemeEnhancementsEnabled = true
        state.externalThemeGlowEnabled = true
        every { AyuVariant.isAyuActive() } returns false
        every { AyuVariant.detect() } returns null

        val project = stubProject("external-theme-project")
        val manager = GlowOverlayManager(project)
        markManagerWarm(manager)
        val glassPane = mockk<GlowGlassPane>(relaxed = true)
        seedOverlaysMapWithMocks(
            manager,
            glassPane,
            host = mockk(relaxed = true),
            layeredPane = mockk(relaxed = true),
        )

        every { AccentResolver.resolve(project, AccentContext.External) } returns "#AABBCC"

        manager.updateGlow()

        assertFalse(
            readOverlaysMap(manager).isEmpty(),
            "updateGlow with external accent context MUST NOT dispose pre-existing overlays",
        )
        verify(exactly = 1) { AccentResolver.resolve(project, AccentContext.External) }
        verify(exactly = 1) { glassPane.glowColor = Color.decode("#AABBCC") }
    }

    @Test
    fun `updateGlow disposes external overlays when external glow inheritance is disabled`() {
        state.externalThemeEnhancementsEnabled = true
        state.externalThemeGlowEnabled = false
        every { AyuVariant.isAyuActive() } returns false
        every { AyuVariant.detect() } returns null

        val project = stubProject("external-theme-project")
        val manager = GlowOverlayManager(project)
        val glassPane = mockk<GlowGlassPane>(relaxed = true)
        seedOverlaysMapWithMocks(
            manager,
            glassPane,
            host = mockk(relaxed = true),
            layeredPane = mockk(relaxed = true),
        )

        manager.updateGlow()

        assertTrue(
            readOverlaysMap(manager).isEmpty(),
            "External Glow permission OFF must dispose overlays instead of painting inherited glow",
        )
        verify(exactly = 0) { AccentResolver.resolve(project, AccentContext.External) }
        verify(exactly = 0) { glassPane.glowColor = any() }
    }

    @Test
    fun `updateGlow pushes per-surface placement onto live overlays`() {
        // Changing placement in Settings must restyle live overlays through the
        // normal updateGlow path: the editor overlay picks the editor placement,
        // every other overlay picks the tool-window placement.
        every { AyuVariant.isAyuActive() } returns true
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        state.glowEditorPlacement = GlowPlacement.SIDE_EDGES.name
        state.glowToolWindowPlacement = GlowPlacement.ISLAND.name

        val project = stubProject("placement-project")
        val manager = GlowOverlayManager(project)
        markManagerWarm(manager)
        val editorPane = mockk<GlowGlassPane>(relaxed = true)
        val toolWindowPane = mockk<GlowGlassPane>(relaxed = true)
        every { editorPane.isEditorOverlay } returns true
        every { toolWindowPane.isEditorOverlay } returns false
        seedOverlaysMapWithMocks(
            manager,
            editorPane,
            host = mockk(relaxed = true),
            layeredPane = mockk(relaxed = true),
            key = "Editor",
        )
        seedOverlaysMapWithMocks(
            manager,
            toolWindowPane,
            host = mockk(relaxed = true),
            layeredPane = mockk(relaxed = true),
        )

        manager.updateGlow()

        verify(exactly = 1) { editorPane.glowPlacement = GlowPlacement.SIDE_EDGES }
        verify(exactly = 1) { toolWindowPane.glowPlacement = GlowPlacement.ISLAND }
        verify(exactly = 0) { editorPane.glowPlacement = GlowPlacement.ISLAND }
        verify(exactly = 0) { toolWindowPane.glowPlacement = GlowPlacement.SIDE_EDGES }
    }

    @Test
    fun `preview placements restyle live overlays and revert re-reads state`() {
        every { AyuVariant.isAyuActive() } returns true
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        state.glowEditorPlacement = GlowPlacement.ISLAND.name
        state.glowToolWindowPlacement = GlowPlacement.SIDE_EDGES.name

        val project = stubProject("preview-project")
        val manager = GlowOverlayManager(project)
        val editorPane = mockk<GlowGlassPane>(relaxed = true)
        val toolWindowPane = mockk<GlowGlassPane>(relaxed = true)
        every { editorPane.isEditorOverlay } returns true
        every { toolWindowPane.isEditorOverlay } returns false
        seedOverlaysMapWithMocks(
            manager,
            editorPane,
            host = mockk(relaxed = true),
            layeredPane = mockk(relaxed = true),
            key = "editor-with-nonstandard-key",
        )
        seedOverlaysMapWithMocks(
            manager,
            toolWindowPane,
            host = mockk(relaxed = true),
            layeredPane = mockk(relaxed = true),
            key = "Editor",
        )

        manager.previewPlacements(GlowPlacement.SIDE_EDGES, GlowPlacement.ISLAND)
        verify(exactly = 1) { editorPane.glowPlacement = GlowPlacement.SIDE_EDGES }
        verify(exactly = 1) { toolWindowPane.glowPlacement = GlowPlacement.ISLAND }

        manager.previewPlacements(null, null)
        verify(exactly = 1) { editorPane.glowPlacement = GlowPlacement.ISLAND }
        verify(exactly = 1) { toolWindowPane.glowPlacement = GlowPlacement.SIDE_EDGES }
    }

    @Test
    fun `updateGlow paints clean last applied accent instead of project resolver`() {
        // Glow follows the app-global chrome color that was actually painted.
        // A background project may resolve to a different override, but its
        // status bar was already repainted with the last clean apply payload.
        every { AyuVariant.isAyuActive() } returns true
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        state.lastAppliedAccentHex = "#5CCFE6"
        state.lastApplyOk = true

        val project = stubProject("background-project")
        val manager = GlowOverlayManager(project)
        markManagerWarm(manager)
        val glassPane = mockk<GlowGlassPane>(relaxed = true)
        seedOverlaysMapWithMocks(
            manager,
            glassPane,
            host = mockk(relaxed = true),
            layeredPane = mockk(relaxed = true),
        )

        every { AccentResolver.resolve(project, AccentContext.Ayu(AyuVariant.MIRAGE)) } returns "#FFCC66"

        manager.updateGlow()

        verify(exactly = 1) { glassPane.glowColor = Color.decode("#5CCFE6") }
        verify(exactly = 0) { glassPane.glowColor = Color.decode("#FFCC66") }
        verify(exactly = 0) { AccentResolver.resolve(project, AccentContext.Ayu(AyuVariant.MIRAGE)) }
    }

    @Test
    fun `attachOverlay seeds clean last applied accent instead of project resolver`() {
        // Late-created overlays must match the already-painted status bar even
        // if this project's resolver would choose a different override. This is
        // the startup/late-attach path that does not receive a fresh topic
        // payload after the overlay exists.
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        every { SwingUtilities.invokeLater(any()) } answers { firstArg<Runnable>().run() }
        state.lastAppliedAccentHex = "#5CCFE6"
        state.lastApplyOk = true

        val project = stubProject("late-overlay-project")
        val manager = GlowOverlayManager(project)
        val host = spyk(JPanel(null))
        val rootPane = mockk<javax.swing.JRootPane>(relaxed = true)
        val layeredPane = JLayeredPane()
        every { host.width } returns 120
        every { host.height } returns 80
        every { host.isShowing } returns true
        every { rootPane.layeredPane } returns layeredPane
        every { SwingUtilities.getRootPane(host) } returns rootPane
        every { SwingUtilities.convertPoint(host, 0, 0, layeredPane) } returns Point(0, 0)
        every { AccentResolver.resolve(project, AccentContext.Ayu(AyuVariant.MIRAGE)) } returns "#FFCC66"

        attachOverlay(manager, LATE_OVERLAY_KEY, host)

        assertEquals(
            Color.decode("#5CCFE6"),
            lateOverlayPane(manager).glowColor,
            "new overlays must seed from the clean app-global applied accent, not the project resolver",
        )
        verify(exactly = 0) { AccentResolver.resolve(project, AccentContext.Ayu(AyuVariant.MIRAGE)) }
    }

    @Test
    fun `attachOverlay skips external overlays when external glow inheritance is disabled`() {
        state.externalThemeEnhancementsEnabled = true
        state.externalThemeGlowEnabled = false
        every { AyuVariant.detect() } returns null
        every { SwingUtilities.invokeLater(any()) } answers { firstArg<Runnable>().run() }

        val project = stubProject("external-late-overlay-project")
        val manager = GlowOverlayManager(project)
        val host = mockk<javax.swing.JComponent>(relaxed = true)
        val rootPane = mockk<javax.swing.JRootPane>(relaxed = true)
        val layeredPane = mockk<JLayeredPane>(relaxed = true)
        every { host.width } returns 120
        every { host.height } returns 80
        every { rootPane.layeredPane } returns layeredPane
        every { SwingUtilities.getRootPane(host) } returns rootPane

        attachOverlay(manager, "ExternalLateOverlay", host)

        assertTrue(
            readOverlaysMap(manager).isEmpty(),
            "External Glow permission OFF must prevent late attach events from recreating overlays",
        )
        verify(exactly = 0) { AccentResolver.resolve(project, AccentContext.External) }
    }

    @Test
    fun `AccentChangedTopic event uses applied accent payload for matching project`() {
        every { AyuVariant.isAyuActive() } returns true
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        every { SwingUtilities.invokeLater(any()) } just Runs

        val project = stubProject("focused-project")
        val messageBus = mockk<MessageBus>()
        val connection = mockk<MessageBusConnection>(relaxed = true)
        val accentListenerSlot = slot<AccentChangeListener>()
        every { project.messageBus } returns messageBus
        every { messageBus.connect(any<Disposable>()) } returns connection
        every {
            connection.subscribe(eq(AccentChangedTopic.TOPIC), capture(accentListenerSlot))
        } just Runs

        val manager = GlowOverlayManager(project)
        val glassPane = mockk<GlowGlassPane>(relaxed = true)
        seedOverlaysMapWithMocks(
            manager,
            glassPane,
            host = mockk(relaxed = true),
            layeredPane = mockk(relaxed = true),
        )

        every { AccentResolver.resolve(project, AyuVariant.MIRAGE) } returns "#FFCC66"

        manager.initialize()

        assertTrue(
            accentListenerSlot.isCaptured,
            "GlowOverlayManager must subscribe to AccentChangedTopic so chrome-only accent refreshes recolor glow",
        )

        accentListenerSlot.captured.accentChanged(
            project,
            AccentHex.unsafeOf("#5CCFE6"),
            AccentResolver.Source.GLOBAL,
        )

        verify(exactly = 1) { glassPane.glowColor = Color.decode("#5CCFE6") }
        verify(exactly = 0) { glassPane.glowColor = Color.decode("#FFCC66") }
    }

    @Test
    fun `AccentChangedTopic event ignores a different project`() {
        every { AyuVariant.isAyuActive() } returns true
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        every { SwingUtilities.invokeLater(any()) } just Runs

        val project = stubProject("focused-project")
        val otherProject = stubProject("other-project")
        val messageBus = mockk<MessageBus>()
        val connection = mockk<MessageBusConnection>(relaxed = true)
        val accentListenerSlot = slot<AccentChangeListener>()
        every { project.messageBus } returns messageBus
        every { messageBus.connect(any<Disposable>()) } returns connection
        every {
            connection.subscribe(eq(AccentChangedTopic.TOPIC), capture(accentListenerSlot))
        } just Runs

        val manager = GlowOverlayManager(project)
        val glassPane = mockk<GlowGlassPane>(relaxed = true)
        seedOverlaysMapWithMocks(
            manager,
            glassPane,
            host = mockk(relaxed = true),
            layeredPane = mockk(relaxed = true),
        )

        manager.initialize()

        assertTrue(
            accentListenerSlot.isCaptured,
            "GlowOverlayManager must subscribe before it can filter project-scoped accent events",
        )

        accentListenerSlot.captured.accentChanged(
            otherProject,
            AccentHex.unsafeOf("#D95757"),
            AccentResolver.Source.PROJECT_OVERRIDE,
        )

        verify(exactly = 0) { glassPane.glowColor = any<Color>() }
    }

    @Test
    fun `AccentChangedTopic event reschedules glow update when fired off EDT`() {
        every { AyuVariant.isAyuActive() } returns true
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        every { SwingUtilities.invokeLater(any()) } just Runs

        val project = stubProject("focused-project")
        val messageBus = mockk<MessageBus>()
        val connection = mockk<MessageBusConnection>(relaxed = true)
        val accentListenerSlot = slot<AccentChangeListener>()
        every { project.messageBus } returns messageBus
        every { messageBus.connect(any<Disposable>()) } returns connection
        every {
            connection.subscribe(eq(AccentChangedTopic.TOPIC), capture(accentListenerSlot))
        } just Runs

        val manager = GlowOverlayManager(project)
        val glassPane = mockk<GlowGlassPane>(relaxed = true)
        seedOverlaysMapWithMocks(
            manager,
            glassPane,
            host = mockk(relaxed = true),
            layeredPane = mockk(relaxed = true),
        )

        every { AccentResolver.resolve(project, AyuVariant.MIRAGE) } returns "#FFCC66"

        manager.initialize()

        assertTrue(
            accentListenerSlot.isCaptured,
            "GlowOverlayManager must subscribe before it can reschedule off-EDT accent events",
        )

        val scheduled = mutableListOf<Runnable>()
        every { SwingUtilities.isEventDispatchThread() } returns false
        every { SwingUtilities.invokeLater(any()) } answers {
            scheduled.add(firstArg<Runnable>())
        }

        accentListenerSlot.captured.accentChanged(
            project,
            AccentHex.unsafeOf("#5CCFE6"),
            AccentResolver.Source.GLOBAL,
        )

        verify(exactly = 0) { glassPane.glowColor = any<Color>() }

        while (scheduled.isNotEmpty()) {
            scheduled.removeFirst().run()
        }

        verify(exactly = 1) { glassPane.glowColor = Color.decode("#5CCFE6") }
        verify(exactly = 0) { glassPane.glowColor = Color.decode("#FFCC66") }
    }

    @Test
    fun `syncGlowForAllProjects continues to second project when first project updateGlow throws`() {
        // Regression lock for the companion-level RuntimeException catch.
        // `syncGlowForAllProjects` iterates every open project; one project
        // whose `updateGlow` throws (e.g. mid-dispose race) MUST NOT block the
        // other projects from being disposed. Pattern B isolation — narrow
        // `RuntimeException` catch, log warning, continue.
        every { AyuVariant.isAyuActive() } returns false
        every { AyuVariant.detect() } returns null

        val project1 = stubProject("project-1")
        val project2 = stubProject("project-2")
        every { mockProjectManager.openProjects } returns arrayOf(project1, project2)

        val manager1 = mockk<GlowOverlayManager>(relaxed = true)
        val manager2 = GlowOverlayManager(project2)
        seedOverlaysMap(manager2, "p2-sentinel")

        // Project 1's updateGlow throws — must not bubble out of the loop.
        every { manager1.updateGlow() } throws RuntimeException("project-1 glow exploded")

        every { project1.getService(GlowOverlayManager::class.java) } returns manager1
        every { project2.getService(GlowOverlayManager::class.java) } returns manager2

        GlowOverlayManager.syncGlowForAllProjects() // MUST NOT throw

        // Project 2's overlays still cleared — proves the loop continued
        // past project 1's failure rather than aborting on the first throw.
        assertTrue(
            readOverlaysMap(manager2).isEmpty(),
            "syncGlowForAllProjects MUST continue to project 2 after project 1 throws " +
                "(isolation lock — Pattern B)",
        )
        verify(exactly = 1) { manager1.updateGlow() }
    }

    @Test
    fun `syncGlowForAllProjects invalidates the cached keystroke license gate`() {
        val hub = mockk<KeystrokeHub>(relaxed = true)
        every { mockApplication.getService(KeystrokeHub::class.java) } returns hub
        every { mockProjectManager.openProjects } returns emptyArray()

        GlowOverlayManager.syncGlowForAllProjects()

        verify(exactly = 1) { hub.invalidateLicenseGate() }
    }

    @Test
    fun `syncGlowForAllProjects disposes every project glow when variant becomes null`() {
        // When the LAF listener detects a non-Ayu LAF, it calls
        // `GlowOverlayManager.syncGlowForAllProjects()` which iterates every
        // open project and triggers per-project disposal via the guard. This
        // test pins the multi-project dispatch — without it, only the focused
        // project's overlay would be disposed.
        every { AyuVariant.isAyuActive() } returns false
        every { AyuVariant.detect() } returns null

        val project1 = stubProject("project-1")
        val project2 = stubProject("project-2")
        every { mockProjectManager.openProjects } returns arrayOf(project1, project2)

        val manager1 = GlowOverlayManager(project1)
        val manager2 = GlowOverlayManager(project2)
        seedOverlaysMap(manager1, "p1-sentinel")
        seedOverlaysMap(manager2, "p2-sentinel")

        // Stub project.getService(GlowOverlayManager::class.java) so the
        // companion's `getInstance(project)` lookup returns our seeded managers
        // instead of trying to spin up a real ProjectComponent.
        every { project1.getService(GlowOverlayManager::class.java) } returns manager1
        every { project2.getService(GlowOverlayManager::class.java) } returns manager2

        GlowOverlayManager.syncGlowForAllProjects()

        assertTrue(
            readOverlaysMap(manager1).isEmpty(),
            "syncGlowForAllProjects MUST dispose overlays for project1 when variant null",
        )
        assertTrue(
            readOverlaysMap(manager2).isEmpty(),
            "syncGlowForAllProjects MUST dispose overlays for project2 when variant null",
        )
    }

    private fun stubProject(name: String): Project {
        val toolWindows = mockk<ToolWindowManager>(relaxed = true)
        val editors = mockk<FileEditorManagerEx>(relaxed = true)
        every { editors.selectedEditor } returns null
        every { editors.component } returns null
        return mockk(relaxed = true) {
            every { isDisposed } returns false
            every { isDefault } returns false
            every { this@mockk.name } returns name
            every { getService(ToolWindowManager::class.java) } returns toolWindows
            every { getService(FileEditorManager::class.java) } returns editors
        }
    }

    private fun attachableHost(): javax.swing.JComponent {
        every { SwingUtilities.invokeLater(any()) } answers { firstArg<Runnable>().run() }
        val host = spyk(JPanel(null))
        val rootPane = mockk<javax.swing.JRootPane>(relaxed = true)
        val layeredPane = JLayeredPane()
        every { host.width } returns 120
        every { host.height } returns 80
        every { host.isShowing } returns true
        every { rootPane.layeredPane } returns layeredPane
        every { SwingUtilities.getRootPane(host) } returns rootPane
        every { SwingUtilities.convertPoint(host, 0, 0, layeredPane) } returns Point(0, 0)
        mockkObject(EditorTabGeometry)
        every { EditorTabGeometry.calculateEditorOverlayBounds(host) } returns Rectangle(0, 0, 120, 80)
        return host
    }

    /**
     * Reads the private `overlays` MutableMap from a [GlowOverlayManager] instance
     * via reflection. The map is the disposal contract's observable surface — an
     * empty map after `updateGlow()` proves the guard fired. Star projection
     * `Map<*, *>` keeps the cast checked-by-Kotlin (no UNCHECKED_CAST warning)
     * since we only need `isEmpty()` and `containsKey(...)` from the read side.
     */
    private fun readOverlaysMap(manager: GlowOverlayManager): Map<*, *> {
        val field = GlowOverlayManager::class.java.getDeclaredField("overlays")
        field.isAccessible = true
        return field.get(manager) as Map<*, *>
    }

    private fun lateOverlayPane(manager: GlowOverlayManager): GlowGlassPane {
        val entry = readOverlaysMap(manager)[LATE_OVERLAY_KEY] ?: error("Overlay '$LATE_OVERLAY_KEY' was not attached")
        val field = entry.javaClass.getDeclaredField("glassPane")
        field.isAccessible = true
        return field.get(entry) as GlowGlassPane
    }

    private fun readGlassPane(
        manager: GlowOverlayManager,
        key: String,
    ): GlowGlassPane {
        val entry = readOverlaysMap(manager)[key] ?: error("Overlay '$key' was not attached")
        val field = entry.javaClass.getDeclaredField("glassPane")
        field.isAccessible = true
        return field.get(entry) as GlowGlassPane
    }

    private fun setActiveGlow(
        manager: GlowOverlayManager,
        overlayId: String = "active",
    ) {
        val field = GlowOverlayManager::class.java.getDeclaredField("activeGlowId")
        field.isAccessible = true
        field.set(manager, overlayId)
    }

    private fun markManagerWarm(manager: GlowOverlayManager) {
        val field = GlowOverlayManager::class.java.getDeclaredField("messageBusConnected")
        field.isAccessible = true
        field.setBoolean(manager, true)
    }

    private fun setAnimator(
        manager: GlowOverlayManager,
        animator: GlowAnimator,
    ) {
        val field = GlowOverlayManager::class.java.getDeclaredField("animator")
        field.isAccessible = true
        field.set(manager, animator)
    }

    private fun readAnimator(manager: GlowOverlayManager): GlowAnimator? {
        val field = GlowOverlayManager::class.java.getDeclaredField("animator")
        field.isAccessible = true
        return field.get(manager) as GlowAnimator?
    }

    private fun setWaveformFailed(pane: GlowGlassPane) {
        pane.failRouteWaveform(IllegalStateException("Fixture waveform failure"))
    }

    private fun invokeUpdateOverlayBounds(
        manager: GlowOverlayManager,
        pane: GlowGlassPane,
        host: javax.swing.JComponent,
        layeredPane: JLayeredPane,
    ) {
        val method =
            GlowOverlayManager::class.java.getDeclaredMethod(
                "updateOverlayBounds",
                GlowGlassPane::class.java,
                javax.swing.JComponent::class.java,
                JLayeredPane::class.java,
                Boolean::class.javaPrimitiveType,
            )
        method.isAccessible = true
        method.invoke(manager, pane, host, layeredPane, true)
    }

    private fun invokeAttachEditorOverlayIfNeeded(manager: GlowOverlayManager) {
        val method = GlowOverlayManager::class.java.getDeclaredMethod("attachEditorOverlayIfNeeded")
        method.isAccessible = true
        method.invoke(manager)
    }

    private fun invokeMoveGlowFocus(
        manager: GlowOverlayManager,
        from: String? = "old",
        to: String? = "new",
    ) {
        val method =
            GlowOverlayManager::class.java.getDeclaredMethod(
                "moveGlowFocus",
                String::class.java,
                String::class.java,
            )
        method.isAccessible = true
        method.invoke(manager, from, to)
    }

    private fun readRouteCoordinator(manager: GlowOverlayManager): RouteCoordinator? {
        val controller = readRouteController(manager)
        val field = controller.javaClass.getDeclaredField("coordinator")
        field.isAccessible = true
        return field.get(controller) as RouteCoordinator?
    }

    private fun readRouteState(manager: GlowOverlayManager): RouteSnapshot =
        requireNotNull(readRouteCoordinator(manager)).snapshot

    private fun readRouteGraph(manager: GlowOverlayManager): RouteGraph? {
        val controller = readRouteController(manager)
        val field = controller.javaClass.getDeclaredField("graph")
        field.isAccessible = true
        return field.get(controller) as RouteGraph?
    }

    private fun readLayoutTimer(manager: GlowOverlayManager): Timer? {
        val controller = readRouteController(manager)
        val field = controller.javaClass.getDeclaredField("layoutTimer")
        field.isAccessible = true
        return field.get(controller) as Timer?
    }

    private fun readRouteLayers(manager: GlowOverlayManager): Map<RouteRootId, WaveformRouteLayer> {
        val controller = readRouteController(manager)
        val field = controller.javaClass.getDeclaredField("layers")
        field.isAccessible = true
        val rawLayers = field.get(controller) as Map<*, *>
        return rawLayers.entries.associate { entry ->
            entry.key as RouteRootId to entry.value as WaveformRouteLayer
        }
    }

    private fun readRouteBridges(manager: GlowOverlayManager): Collection<CrossWindowBridge> {
        val controller = readRouteController(manager)
        val field = controller.javaClass.getDeclaredField("bridges")
        field.isAccessible = true
        return (field.get(controller) as Map<*, *>).values.filterIsInstance<CrossWindowBridge>()
    }

    private fun readActivationListener(manager: GlowOverlayManager): ApplicationActivationListener {
        val field = GlowOverlayManager::class.java.getDeclaredField("activationListener")
        field.isAccessible = true
        return field.get(manager) as ApplicationActivationListener
    }

    private fun stopOverlayAnimations(manager: GlowOverlayManager) {
        readOverlaysMap(manager).values.forEach { entry ->
            val field = entry?.javaClass?.getDeclaredField("glassPane") ?: return@forEach
            field.isAccessible = true
            (field.get(entry) as GlowGlassPane).stopAnimation()
        }
    }

    private fun routeTimerCount(manager: GlowOverlayManager): Int {
        val controller = readRouteController(manager)
        val field = controller.javaClass.getDeclaredField("timer")
        field.isAccessible = true
        val timer = field.get(controller) as Timer?
        return if (timer?.isRunning == true) 1 else 0
    }

    private fun stopRouteTimer(manager: GlowOverlayManager) {
        val controller = readRouteController(manager)
        val field = controller.javaClass.getDeclaredField("timer")
        field.isAccessible = true
        (field.get(controller) as Timer?)?.stop()
    }

    private fun GlowGlassPane.hasWaveformTimer(): Boolean {
        val field = GlowGlassPane::class.java.getDeclaredField("waveformTimer")
        field.isAccessible = true
        return field.get(this) != null
    }

    private fun GlowGlassPane.setFadeAlpha(alpha: Float) {
        val field = GlowGlassPane::class.java.getDeclaredField("fadeAlpha")
        field.isAccessible = true
        field.setFloat(this, alpha)
    }

    private fun GlowGlassPane.readFadeAlpha(): Float {
        val field = GlowGlassPane::class.java.getDeclaredField("fadeAlpha")
        field.isAccessible = true
        return field.getFloat(this)
    }

    private fun GlowGlassPane.advanceFade() {
        val field = GlowGlassPane::class.java.getDeclaredField("fadeTimer")
        field.isAccessible = true
        val timer = requireNotNull(field.get(this) as Timer?)
        timer.actionListeners.forEach { listener ->
            listener.actionPerformed(java.awt.event.ActionEvent(timer, 0, "tick"))
        }
    }

    private fun GlowGlassPane.hasRouteMode(): Boolean {
        val field = GlowGlassPane::class.java.getDeclaredField("isRouteMode")
        field.isAccessible = true
        return field.getBoolean(this)
    }

    private fun rebuildRouteGraph(manager: GlowOverlayManager) {
        val rebuild = {
            val controller = readRouteController(manager)
            val method = controller.javaClass.getDeclaredMethod("rebuildGraph")
            method.isAccessible = true
            method.invoke(controller)
        }
        if (EventQueue.isDispatchThread()) {
            rebuild()
        } else {
            EventQueue.invokeAndWait { rebuild() }
        }
    }

    private fun scheduleGraphRefresh(manager: GlowOverlayManager) {
        val controller = readRouteController(manager)
        val method = controller.javaClass.getDeclaredMethod("scheduleGraphRefresh")
        method.isAccessible = true
        method.invoke(controller)
    }

    private fun recordBridgeFailure(
        manager: GlowOverlayManager,
        connectorId: RouteConnectorId,
    ) {
        val controller = readRouteController(manager)
        val method = controller.javaClass.getDeclaredMethod("recordBridgeFailure", RouteConnectorId::class.java)
        method.isAccessible = true
        method.invoke(controller, connectorId)
    }

    private fun readRouteController(manager: GlowOverlayManager): RouteController {
        val field = GlowOverlayManager::class.java.getDeclaredField("routeController")
        field.isAccessible = true
        return field.get(manager) as RouteController
    }

    private fun readOverlayHost(
        manager: GlowOverlayManager,
        id: String,
    ): javax.swing.JComponent {
        val entry = requireNotNull(readOverlaysMap(manager)[id])
        val field = entry.javaClass.getDeclaredField("host")
        field.isAccessible = true
        return field.get(entry) as javax.swing.JComponent
    }

    private fun readEditorLayer(manager: GlowOverlayManager): JLayeredPane {
        val entry = requireNotNull(readOverlaysMap(manager)["Editor"])
        val field = entry.javaClass.getDeclaredField("layeredPane")
        field.isAccessible = true
        return field.get(entry) as JLayeredPane
    }

    private fun configuredPane(movement: WaveformMovement): GlowGlassPane =
        GlowGlassPane(
            glowColor = Color(255, 204, 102),
            glowStyle = GlowStyle.SOFT,
            glowIntensity = 20,
            glowWidth = 4,
        ).apply {
            setSize(400, 300)
            configureWaveform(
                GlowShape.WAVEFORM,
                WaveformConfig(movement = movement),
            )
        }

    private fun seedRouteOverlays(
        manager: GlowOverlayManager,
        project: Project,
    ): javax.swing.JComponent {
        val layeredPane = mockk<JLayeredPane>(relaxed = true)
        val window = mockk<Window>(relaxed = true)
        val editorHost = mockk<javax.swing.JComponent>(relaxed = true)
        val commitHost = mockk<javax.swing.JComponent>(relaxed = true)
        val toolWindowManager = mockk<ToolWindowManager>(relaxed = true)
        every { window.isActive } returns true
        every { editorHost.isShowing } returns true
        every { editorHost.isDisplayable } returns true
        every { editorHost.width } returns 400
        every { editorHost.height } returns 300
        every { commitHost.isShowing } returns true
        every { commitHost.isDisplayable } returns true
        every { commitHost.width } returns 220
        every { commitHost.height } returns 180
        every { SwingUtilities.getWindowAncestor(editorHost) } returns window
        every { SwingUtilities.getWindowAncestor(commitHost) } returns window
        every { SwingUtilities.convertPoint(editorHost, 0, 0, layeredPane) } returns Point(0, 0)
        every { SwingUtilities.convertPoint(commitHost, 0, 0, layeredPane) } returns Point(408, 40)
        every { project.getService(ToolWindowManager::class.java) } returns toolWindowManager

        val editor = configuredPane(WaveformMovement.CHAOTIC)
        val commit = configuredPane(WaveformMovement.CHAOTIC).apply { setSize(220, 180) }
        every { SwingUtilities.convertPointToScreen(any(), editor) } answers {
            firstArg<Point>().setLocation(0, 0)
        }
        every { SwingUtilities.convertPointToScreen(any(), commit) } answers {
            firstArg<Point>().setLocation(408, 40)
        }
        every { SwingUtilities.convertPointToScreen(any(), layeredPane) } answers {
            firstArg<Point>().setLocation(0, 0)
        }
        seedOverlaysMapWithMocks(manager, editor, editorHost, layeredPane, "Editor")
        seedOverlaysMapWithMocks(manager, commit, commitHost, layeredPane, "Commit")
        return commitHost
    }

    private fun attachOverlay(
        manager: GlowOverlayManager,
        id: String,
        host: javax.swing.JComponent,
        isEditorOverlay: Boolean = false,
    ) {
        manager.prepareOverlay(GlowHost(id, host, isEditorOverlay), loadGlowRuntimeInput(state))?.installLegacy()
    }

    /**
     * Inserts a sentinel entry into the private `overlays` map so the disposal
     * test can prove the map was cleared. The map's value type is the private
     * `OverlayEntry` data class. A `removeAllOverlays()` disposal path runs
     * inside `updateGlow()`'s guard, AND `updateOverlayStyles` iterates the
     * same map on the non-disposal branch — both destructure the value as
     * `OverlayEntry` (`for ((_, entry) in overlays)`), so seeding a
     * non-`OverlayEntry` value triggers a `ClassCastException` at runtime.
     *
     * We construct a real `OverlayEntry` via its synthetic data-class
     * constructor (the class is `private`, so we reach it through
     * `getDeclaredConstructors`) with mockk-relaxed Swing peers. The disposal
     * path's `detachOverlayEntry(entry)` calls `glassPane.stopAnimation()`,
     * `host.removeComponentListener(...)`, `layeredPane.remove(...)`, and
     * `layeredPane.repaint(...)` — all no-ops against relaxed mocks. The
     * non-disposal path's `updateOverlayStyles` only assigns properties on the
     * mocked `glassPane`, also a no-op.
     */
    private fun seedOverlaysMap(
        manager: GlowOverlayManager,
        key: String,
    ) {
        val field = GlowOverlayManager::class.java.getDeclaredField("overlays")
        field.isAccessible = true
        val map = field.get(manager) as Map<*, *>
        // `MutableMap::class.java` resolves to the same JVM `java.util.Map`
        // class but lets Kotlin's IDE tooling treat the lookup as
        // collection-language-canonical rather than a bare java.* reference.
        val putMethod =
            MutableMap::class.java.getDeclaredMethod(
                "put",
                Any::class.java,
                Any::class.java,
            )
        putMethod.invoke(map, key, makeOverlayEntry())
    }

    /**
     * Seed the overlays map with EXPLICIT mocks (rather than the relaxed-mock
     * anonymous trio inside [makeOverlayEntry]) so tests can verify
     * `detachOverlayEntry`'s side-effects against the same mock instances they
     * passed in. The non-disposal-path `updateOverlayStyles` iteration just
     * assigns properties on the glassPane mock, which relaxed-mock no-ops.
     *
     * Seeds under [PROJECT_ID] by default (the disposal-path tests
     * don't vary the key); the placement-push test varies [key] only to seed
     * two entries, while each pane's `isEditorOverlay` flag selects placement.
     */
    private fun seedOverlaysMapWithMocks(
        manager: GlowOverlayManager,
        glassPane: GlowGlassPane,
        host: javax.swing.JComponent,
        layeredPane: JLayeredPane,
        key: String = PROJECT_ID,
    ) {
        val field = GlowOverlayManager::class.java.getDeclaredField("overlays")
        field.isAccessible = true
        val map = field.get(manager) as Map<*, *>
        // `MutableMap::class.java` resolves to the same JVM `java.util.Map`
        // class but lets Kotlin's IDE tooling treat the lookup as
        // collection-language-canonical rather than a bare java.* reference.
        val putMethod =
            MutableMap::class.java.getDeclaredMethod(
                "put",
                Any::class.java,
                Any::class.java,
            )
        putMethod.invoke(map, key, makeOverlayEntryWith(glassPane, host, layeredPane))
    }

    private fun makeOverlayEntryWith(
        glassPane: GlowGlassPane,
        host: javax.swing.JComponent,
        layeredPane: JLayeredPane,
    ): Any {
        val entryClass =
            GlowOverlayManager::class.java.declaredClasses
                .first { it.simpleName == "OverlayEntry" }
        val ctor =
            entryClass.declaredConstructors
                .first { it.parameterCount == OVERLAY_ENTRY_PRIMARY_CTOR_ARITY }
        ctor.isAccessible = true
        return ctor.newInstance(glassPane, host, layeredPane, null, null)
    }

    /**
     * Builds a real `GlowOverlayManager$OverlayEntry` via reflection. The
     * primary data-class constructor takes 5 args (glassPane, host,
     * layeredPane, componentListener, hierarchyBoundsListener). Kotlin also
     * generates a synthetic default-argument constructor with extra
     * (Int $mask, DefaultConstructorMarker) trailing slots — we pin to the
     * exact 5-param ctor so the synthetic one is never picked. All five are
     * mocked so `detachOverlayEntry` / `updateOverlayStyles` see well-typed
     * objects but every call routes to a relaxed-mock no-op.
     */
    private fun makeOverlayEntry(): Any {
        val entryClass =
            GlowOverlayManager::class.java.declaredClasses
                .first { it.simpleName == "OverlayEntry" }
        val ctor =
            entryClass.declaredConstructors
                .first { it.parameterCount == OVERLAY_ENTRY_PRIMARY_CTOR_ARITY }
        ctor.isAccessible = true
        return ctor.newInstance(
            mockk<GlowGlassPane>(relaxed = true),
            mockk<javax.swing.JComponent>(relaxed = true),
            mockk<JLayeredPane>(relaxed = true),
            // Nullable componentListener / hierarchyBoundsListener — null
            // matches the editor-overlay production branch and exercises the
            // `?.let { ... }` guards inside detachOverlayEntry.
            null,
            null,
        )
    }

    private companion object {
        /** Primary data-class ctor of OverlayEntry: 5 declared parameters. */
        private const val OVERLAY_ENTRY_PRIMARY_CTOR_ARITY = 5

        /** Sentinel key for the disposal-path test seed. */
        private const val PROJECT_ID = "Project"

        /** Sentinel key for the late-overlay attach-path test. */
        private const val LATE_OVERLAY_KEY = "LateOverlay"
    }

    private class SettlingEditorHost : javax.swing.JComponent() {
        override fun isShowing(): Boolean = true
    }
}
