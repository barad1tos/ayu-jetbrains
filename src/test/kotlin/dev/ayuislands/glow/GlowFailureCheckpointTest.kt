package dev.ayuislands.glow

import com.intellij.testFramework.junit5.RunInEdt
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.glow.waveform.WaveformEngine
import dev.ayuislands.glow.waveform.WaveformEvent
import dev.ayuislands.glow.waveform.WaveformMovement
import io.mockk.every
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.Color
import javax.swing.JLayeredPane
import javax.swing.JPanel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunInEdt
class GlowFailureCheckpointTest {
    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `appearance owner records engine creation before clearing a failed baseline`() {
        val pane = newPane()
        pane.failRouteWaveform(IllegalStateException("Failed before applying appearance"))
        val before = assertNotNull(pane.waveformFailure)
        val original = pane.captureAppearance()
        val failures = GlowFailureCheckpoint(pane) { true }
        val appearance =
            GlowPaneCheckpoint(
                pane,
                original.copy(waveform = original.waveform.copy(amplitude = original.waveform.amplitude + 1)),
            ) { true }
        try {
            pane.stopAnimation()
            appearance.apply(failures::recordEngine)
            assertNull(pane.waveformFailure)
            assertSame(before, failures.record.cleared)

            failures.prepareRestore()
            appearance.restore()
            failures.restore()

            assertEquals(original, pane.captureAppearance())
            assertSame(before, pane.waveformFailure)
        } finally {
            pane.stopAnimation()
        }
    }

    @Test
    fun `own local failure restores only after capture-only recovery preparation`() {
        val pane = newPane()
        val checkpoint = GlowFailureCheckpoint(pane) { true }
        val engines = mutableListOf<WaveformEngine>()
        try {
            pane.stopAnimation()
            pane.configureWaveform(GlowShape.WAVEFORM, pane.captureAppearance().waveform) { created ->
                checkpoint.recordEngine(created)
                engines.add(created)
            }
            engines.single().handle(WaveformEvent.RenderFailed)
            pane.activateWaveform(powerSaveEnabled = true)
            val failure = assertNotNull(pane.waveformFailure)

            assertFailsWith<IllegalStateException> { checkpoint.restore() }
            checkpoint.prepareRestore()

            val prepared = checkpoint.record
            assertSame(failure, pane.waveformFailure)
            assertSame(failure, assertNotNull(prepared.restoration).observed)
            assertTrue(checkpoint.hasPending)
            checkpoint.restore()

            assertNull(pane.waveformFailure)
            assertEquals(GlowFieldState.RESTORED, checkpoint.record.state)
            assertFalse(checkpoint.hasPending)
            assertEquals(GlowFieldState.WRITING, prepared.state)
        } finally {
            pane.stopAnimation()
        }
    }

    @Test
    fun `recorded route failure is owned but equal looking replacement route is foreign`() {
        val pane = newPane()
        val checkpoint = GlowFailureCheckpoint(pane) { true }
        val route = routeFor(pane)
        try {
            checkpoint.recordRoute(route)
            pane.failRouteWaveform(IllegalStateException("Owned route failure"), route)
            checkpoint.prepareRestore()
            checkpoint.restore()
            assertNull(pane.waveformFailure)

            val next = GlowFailureCheckpoint(pane) { true }
            next.recordRoute(route)
            pane.failRouteWaveform(IllegalStateException("Foreign route failure"), routeFor(pane))
            val foreign = assertNotNull(pane.waveformFailure)
            next.prepareRestore()
            next.restore()
            assertSame(foreign, pane.waveformFailure)
            assertEquals(GlowFieldState.FOREIGN, next.record.state)

            pane.failRouteWaveform(IllegalStateException("Later owned-looking failure"), route)
            val later = pane.waveformFailure
            next.prepareRestore()
            next.restore()
            assertSame(later, pane.waveformFailure)
            assertEquals(GlowFieldState.FOREIGN, next.record.state)
        } finally {
            pane.stopAnimation()
        }
    }

    @Test
    fun `owned healing restores the original failure after appearance rollback`() {
        val pane = newPane()
        pane.failRouteWaveform(IllegalStateException("Original failure"))
        val before = assertNotNull(pane.waveformFailure)
        val checkpoint = GlowFailureCheckpoint(pane) { true }
        val original = pane.captureAppearance()
        try {
            pane.stopAnimation()
            pane.configureWaveform(
                GlowShape.WAVEFORM,
                original.waveform.copy(amplitude = original.waveform.amplitude + 1),
                onEngineCreated = checkpoint::recordEngine,
            )
            assertNull(pane.waveformFailure)
            assertSame(before, checkpoint.record.cleared)
            checkpoint.prepareRestore()
            pane.configureWaveform(original.shape, original.waveform, preserveFailure = true)

            checkpoint.restore()

            assertSame(before, pane.waveformFailure)
            assertEquals(original, pane.captureAppearance())
            assertFalse(pane.isWaveform)
        } finally {
            pane.stopAnimation()
        }
    }

    @Test
    fun `foreign configuration prevents an old failure being reinstated`() {
        val pane = newPane()
        pane.failRouteWaveform(IllegalStateException("Original failure"))
        val checkpoint = GlowFailureCheckpoint(pane) { true }
        val foreignConfig = WaveformConfig(amplitude = 19)
        try {
            pane.stopAnimation()
            pane.configureWaveform(GlowShape.WAVEFORM, foreignConfig, onEngineCreated = checkpoint::recordEngine)
            checkpoint.prepareRestore()
            checkpoint.restore()

            assertNull(pane.waveformFailure)
            assertEquals(foreignConfig, pane.captureAppearance().waveform)
            assertEquals(GlowFieldState.FOREIGN, checkpoint.record.state)
        } finally {
            pane.stopAnimation()
        }
    }

    @Test
    fun `a foreign failure between capture and engine creation cannot be silently healed`() {
        val pane = newPane()
        val checkpoint = GlowFailureCheckpoint(pane) { true }
        pane.failRouteWaveform(IllegalStateException("Failure before acknowledged write"))
        val foreign = assertNotNull(pane.waveformFailure)
        try {
            assertFailsWith<IllegalStateException> {
                pane.configureWaveform(
                    GlowShape.WAVEFORM,
                    WaveformConfig(amplitude = 19),
                    onEngineCreated = checkpoint::recordEngine,
                )
            }
            assertSame(foreign, pane.waveformFailure)
        } finally {
            pane.stopAnimation()
        }
    }

    @Test
    fun `a replacement after recovery preparation survives the acknowledged restore`() {
        val pane = newPane()
        val checkpoint = GlowFailureCheckpoint(pane) { true }
        val route = routeFor(pane)
        try {
            checkpoint.recordRoute(route)
            pane.failRouteWaveform(IllegalStateException("Owned failure"), route)
            checkpoint.prepareRestore()
            pane.failRouteWaveform(IllegalStateException("Later failure"))
            val foreign = assertNotNull(pane.waveformFailure)

            checkpoint.restore()

            assertSame(foreign, pane.waveformFailure)
            assertEquals(GlowFieldState.FOREIGN, checkpoint.record.state)
        } finally {
            pane.stopAnimation()
        }
    }

    @Test
    fun `newer receipt unavailable target and acceptance retain their separate boundaries`() {
        val pane = newPane()
        var current = true
        val checkpoint = GlowFailureCheckpoint(pane) { current }
        val route = routeFor(pane)
        try {
            checkpoint.recordRoute(route)
            pane.failRouteWaveform(IllegalStateException("Owned failure"), route)
            val failure = pane.waveformFailure
            checkpoint.prepareRestore()
            assertFailsWith<IllegalStateException> { checkpoint.restore(isBlocked = true) }
            current = false
            assertFailsWith<IllegalStateException> { checkpoint.restore() }
            assertSame(failure, pane.waveformFailure)
            assertTrue(checkpoint.hasPending)
            current = true
            pane.restoreWaveformFailure(failure, null)
            assertFailsWith<IllegalStateException> { checkpoint.restore(isBlocked = true) }
            checkpoint.restore()
            assertNull(pane.waveformFailure)

            val accepted = GlowFailureCheckpoint(pane) { true }
            accepted.recordRoute(route)
            accepted.accept()
            accepted.accept()
            assertFalse(accepted.hasPending)
            assertFailsWith<IllegalStateException> { accepted.restore() }
            assertFailsWith<IllegalStateException> { accepted.recordRoute(route) }
        } finally {
            pane.stopAnimation()
        }
    }

    @Test
    fun `mutation then throw settles on retry without replaying the native restore`() {
        val pane = spyk(newPane())
        val checkpoint = GlowFailureCheckpoint(pane) { true }
        val route = routeFor(pane)
        try {
            checkpoint.recordRoute(route)
            pane.failRouteWaveform(IllegalStateException("Owned failure"), route)
            val failure = assertNotNull(pane.waveformFailure)
            checkpoint.prepareRestore()
            every { pane.repaint() } throws IllegalStateException("After health restoration")

            assertFailsWith<IllegalStateException> { checkpoint.restore() }
            assertNull(pane.waveformFailure)
            assertTrue(checkpoint.hasPending)
            checkpoint.restore()

            assertFalse(checkpoint.hasPending)
            verify(exactly = 1) { pane.restoreWaveformFailure(failure, null) }
        } finally {
            pane.stopAnimation()
        }
    }

    private fun newPane(): GlowGlassPane =
        GlowGlassPane(Color.CYAN, GlowStyle.SOFT, 50, 8).apply {
            configureWaveform(GlowShape.WAVEFORM, WaveformConfig(movement = WaveformMovement.CHAOTIC))
        }

    private fun routeFor(pane: GlowGlassPane): RoutePlayback =
        RoutePlayback(pane.captureAppearance().waveform, listOf(RouteOverlay("Editor", pane, JPanel(), JLayeredPane())))
}
