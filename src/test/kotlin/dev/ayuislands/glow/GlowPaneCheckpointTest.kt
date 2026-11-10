package dev.ayuislands.glow

import com.intellij.testFramework.junit5.RunInEdt
import dev.ayuislands.glow.waveform.WaveformBaseline
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.glow.waveform.WaveformEngine
import dev.ayuislands.glow.waveform.WaveformEvent
import dev.ayuislands.glow.waveform.WaveformMovement
import io.mockk.every
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import java.awt.Color
import java.awt.event.ComponentAdapter
import java.awt.event.HierarchyBoundsAdapter
import javax.swing.JLayeredPane
import javax.swing.JPanel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunInEdt
class GlowPaneCheckpointTest {
    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `restoration records the fresh engine before activating the restored waveform`() {
        val before = originalAppearance().copy(shape = GlowShape.WAVEFORM)
        val pane = newPane(before)
        val checkpoint = GlowPaneCheckpoint(pane, before.copy(shape = GlowShape.SOLID)) { true }
        val engines = mutableListOf<WaveformEngine>()
        try {
            checkpoint.apply()
            pane.stopAnimation()

            checkpoint.restore(onEngineCreated = engines::add)

            val restoredEngine = engines.single()
            assertEquals(before, pane.captureAppearance())
            assertFalse(checkpoint.hasPending)
            restoredEngine.handle(WaveformEvent.RenderFailed)
            pane.activateWaveform(powerSaveEnabled = true)
            val failure = assertNotNull(pane.waveformFailure)
            assertSame(restoredEngine, (failure.origin as GlowFailureOrigin.Local).engine)

            checkpoint.restore(onEngineCreated = engines::add)
            assertEquals(1, engines.size)
            assertSame(failure, pane.waveformFailure)
        } finally {
            pane.stopAnimation()
        }
    }

    @Test
    fun `failed restoration recording retains progress without replaying settled appearance`() {
        val before = originalAppearance().copy(shape = GlowShape.WAVEFORM)
        val pane = newPane(before)
        val checkpoint =
            GlowPaneCheckpoint(pane, before.copy(shape = GlowShape.SOLID, width = before.width + 1)) { true }
        val engines = mutableListOf<WaveformEngine>()
        val failure = IllegalStateException("Recovery engine acknowledgement failed")
        try {
            checkpoint.apply()
            pane.stopAnimation()

            val thrown =
                assertFailsWith<IllegalStateException> {
                    checkpoint.restore { engine ->
                        engines.add(engine)
                        throw failure
                    }
                }

            assertSame(failure, thrown)
            assertEquals(1, engines.size)
            assertEquals(before, pane.captureAppearance())
            assertTrue(checkpoint.hasPending)
            checkpoint.restore { error("Settled appearance must not create another engine") }
            assertFalse(checkpoint.hasPending)
            assertEquals(before, pane.captureAppearance())
        } finally {
            pane.stopAnimation()
        }
    }

    @Test
    fun `appearance rollback preserves an independent waveform failure`() {
        val before = originalAppearance().copy(shape = GlowShape.WAVEFORM)
        val pane = newPane(before)
        val planned = before.copy(waveform = before.waveform.copy(amplitude = before.waveform.amplitude + 1))
        val checkpoint = GlowPaneCheckpoint(pane, planned) { true }
        try {
            checkpoint.apply()
            pane.failRouteWaveform(IllegalStateException("Independent renderer failure"))
            val failure = assertNotNull(pane.waveformFailure)

            checkpoint.restore()

            assertEquals(before, pane.captureAppearance())
            assertSame(failure, pane.waveformFailure)
            assertFalse(pane.isWaveform)
            checkpoint.restore()
            assertSame(failure, pane.waveformFailure)
        } finally {
            pane.stopAnimation()
        }
    }

    @Test
    fun `native records retain immutable progress and exact color identity`() {
        val pane = newPane(originalAppearance())
        val target = targetAppearance()
        val checkpoint = GlowPaneCheckpoint(pane, target) { true }
        try {
            val prepared = checkpoint.record
            assertSame(pane.glowColor, prepared.before.color)
            assertSame(target.color, prepared.planned.color)
            assertTrue(prepared.fields.values.all { it == GlowFieldState.PREPARED })
            assertFalse(GlowPaneField.VISIBILITY in prepared.fields)
            assertFailsWith<UnsupportedOperationException> {
                (prepared.fields as MutableMap)[GlowPaneField.COLOR] = GlowFieldState.FOREIGN
            }

            checkpoint.apply()
            val applied = checkpoint.record
            assertEquals(GlowCheckpointPhase.APPLIED, applied.phase)
            assertTrue(applied.fields.values.all { it == GlowFieldState.WRITING })
            pane.glowColor = Color(target.color.rgb, true)
            checkpoint.restore()

            assertEquals(GlowFieldState.FOREIGN, checkpoint.record.fields[GlowPaneField.COLOR])
            assertEquals(GlowFieldState.RESTORED, checkpoint.record.fields[GlowPaneField.WIDTH])
            assertEquals(GlowCheckpointPhase.RESTORED, checkpoint.record.phase)
            assertEquals(GlowCheckpointPhase.PREPARED, prepared.phase)
            assertTrue(prepared.fields.values.all { it == GlowFieldState.PREPARED })
            assertTrue(applied.fields.values.all { it == GlowFieldState.WRITING })
        } finally {
            pane.stopAnimation()
        }
    }

    @Test
    fun `staged visibility preserves hierarchy order listeners and appearance`() {
        val pane = newPane(originalAppearance())
        val layer = JLayeredPane()
        layer.add(JPanel())
        layer.add(pane)
        layer.add(JPanel())
        pane.addComponentListener(object : ComponentAdapter() {})
        pane.addHierarchyBoundsListener(object : HierarchyBoundsAdapter() {})
        val order = layer.components.toList()
        val componentListeners = pane.componentListeners.toList()
        val hierarchyListeners = pane.hierarchyBoundsListeners.toList()
        val before = pane.captureAppearance()
        val checkpoint = GlowPaneCheckpoint(pane, before.copy(isVisible = false)) { pane.parent === layer }
        try {
            checkpoint.apply()

            assertFalse(pane.isVisible)
            assertEquals(before.copy(isVisible = false), pane.captureAppearance())
            assertEquals(order, layer.components.toList())
            assertEquals(componentListeners, pane.componentListeners.toList())
            assertEquals(hierarchyListeners, pane.hierarchyBoundsListeners.toList())

            checkpoint.restore()

            assertEquals(before, pane.captureAppearance())
            assertEquals(order, layer.components.toList())
            assertEquals(componentListeners, pane.componentListeners.toList())
            assertEquals(hierarchyListeners, pane.hierarchyBoundsListeners.toList())
        } finally {
            pane.stopAnimation()
        }
    }

    @Test
    fun `visibility restoration retains a mutation that throws and avoids retrying a settled setter`() {
        val pane = spyk(newPane(originalAppearance()))
        val before = pane.captureAppearance()
        val checkpoint = GlowPaneCheckpoint(pane, before.copy(isVisible = false)) { true }
        every { pane.isVisible = false } answers {
            callOriginal()
            error("After hiding pane")
        }
        every { pane.isVisible = true } answers {
            callOriginal()
            error("After restoring visibility")
        }
        try {
            assertFailsWith<IllegalStateException> { checkpoint.apply() }
            assertFalse(pane.isVisible)
            assertTrue(checkpoint.hasPending)

            assertFailsWith<IllegalStateException> { checkpoint.restore() }
            assertTrue(pane.isVisible)
            assertTrue(checkpoint.hasPending)

            checkpoint.restore()
            assertFalse(checkpoint.hasPending)
            assertEquals(before, pane.captureAppearance())
            verify(exactly = 1) { pane.isVisible = true }
        } finally {
            pane.stopAnimation()
        }
    }

    @Test
    fun `capture retains installed settings including waveform while the solid shape is selected`() {
        val before = originalAppearance().copy(isVisible = false)
        val pane = newPane(before)
        try {
            assertEquals(before, pane.captureAppearance())
            assertSame(before.color, pane.captureAppearance().color)
            assertSame(before.waveform, pane.captureAppearance().waveform)
        } finally {
            pane.stopAnimation()
        }
    }

    @Test
    fun `apply and restore use actual pane values without changing its role or animation progress`() {
        val before = originalAppearance()
        val pane = newPane(before)
        val target = targetAppearance()
        pane.isEditorOverlay = true
        pane.animationAlpha = 0.37f
        val checkpoint = GlowPaneCheckpoint(pane, target) { true }
        try {
            assertEquals(before, pane.captureAppearance())
            assertEquals(before, checkpoint.before)
            checkpoint.apply()
            assertEquals(target, pane.captureAppearance())
            assertTrue(checkpoint.hasPending)
            checkpoint.restore()
            assertEquals(before, pane.captureAppearance())
            assertSame(before.color, pane.glowColor)
            assertFalse(checkpoint.hasPending)
            assertTrue(pane.isEditorOverlay)
            assertEquals(0.37f, pane.animationAlpha)
        } finally {
            pane.stopAnimation()
        }
    }

    @Test
    fun `rollback retains foreign fields including equal colored objects and individual waveform choices`() {
        val before = originalAppearance()
        val pane = newPane(before)
        val target = targetAppearance()
        val checkpoint = GlowPaneCheckpoint(pane, target) { true }
        try {
            checkpoint.apply()
            val foreignColor = Color(target.color.rgb, true)
            pane.glowColor = foreignColor
            pane.glowWidth = 23
            pane.isVisible = false
            pane.configureWaveform(target.shape, pane.captureAppearance().waveform.copy(loopSeconds = 13f))

            checkpoint.restore()

            assertSame(foreignColor, pane.glowColor)
            assertEquals(
                before.copy(
                    color = foreignColor,
                    width = 23,
                    waveform = before.waveform.copy(loopSeconds = 13f),
                    isVisible = false,
                ),
                pane.captureAppearance(),
            )
            assertFalse(checkpoint.hasPending)
            checkpoint.restore()
            assertSame(foreignColor, pane.glowColor)
        } finally {
            pane.stopAnimation()
        }
    }

    @Test
    fun `apply mutation then throw restores only attempted fields`() {
        val before = originalAppearance()
        val pane = spyk(newPane(before))
        val checkpoint = GlowPaneCheckpoint(pane, targetAppearance()) { true }
        every { pane.configureWaveform(GlowShape.WAVEFORM, any(), onEngineCreated = any()) } answers {
            callOriginal()
            error("after waveform mutation")
        }
        try {
            assertFailsWith<IllegalStateException> { checkpoint.apply() }
            assertTrue(pane.usesWaveformBounds)
            assertTrue(checkpoint.hasPending)

            checkpoint.restore()

            assertEquals(before, pane.captureAppearance())
            assertFalse(checkpoint.hasPending)
        } finally {
            pane.stopAnimation()
        }
    }

    @Test
    fun `rollback keeps failed receipts and settles an already restored field without replay`() {
        val before = originalAppearance()
        val pane = spyk(newPane(before))
        val checkpoint = GlowPaneCheckpoint(pane, targetAppearance()) { true }
        try {
            checkpoint.apply()
            every {
                pane.configureWaveform(GlowShape.SOLID, any(), preserveFailure = true, onEngineCreated = any())
            } answers {
                callOriginal()
                error("after waveform restoration")
            }

            assertFailsWith<IllegalStateException> { checkpoint.restore() }
            assertEquals(before, pane.captureAppearance())
            assertTrue(checkpoint.hasPending)
            checkpoint.restore()

            assertFalse(checkpoint.hasPending)
            verify(exactly = 1) {
                pane.configureWaveform(GlowShape.SOLID, any(), preserveFailure = true, onEngineCreated = any())
            }
        } finally {
            pane.stopAnimation()
        }
    }

    @Test
    fun `unavailable target remains pending and accepted changes cannot be rolled back`() {
        val before = originalAppearance()
        val pane = newPane(before)
        val target = targetAppearance()
        var current = true
        val checkpoint = GlowPaneCheckpoint(pane, target) { current }
        try {
            checkpoint.apply()
            current = false
            assertFailsWith<IllegalStateException> { checkpoint.restore() }
            assertEquals(target, pane.captureAppearance())
            assertTrue(checkpoint.hasPending)
            current = true
            checkpoint.restore()
            assertEquals(before, pane.captureAppearance())

            val accepted = GlowPaneCheckpoint(pane, target) { true }
            accepted.apply()
            accepted.accept()
            assertFalse(accepted.hasPending)
            assertFailsWith<IllegalStateException> { accepted.restore() }
            assertEquals(target, pane.captureAppearance())
        } finally {
            pane.stopAnimation()
        }
    }

    @Test
    fun `apply detects an earlier field changed by the final setter`() {
        val before = originalAppearance()
        val pane = spyk(newPane(before))
        val target = targetAppearance()
        val foreignColor = Color(0xFF8899)
        val checkpoint = GlowPaneCheckpoint(pane, target) { true }
        every { pane.configureWaveform(target.shape, target.waveform, onEngineCreated = any()) } answers {
            callOriginal()
            pane.glowColor = foreignColor
        }
        try {
            assertFailsWith<IllegalStateException> { checkpoint.apply() }

            checkpoint.restore()

            assertEquals(before.copy(color = foreignColor), pane.captureAppearance())
            assertFalse(checkpoint.hasPending)
        } finally {
            pane.stopAnimation()
        }
    }

    @Test
    fun `newer receipt blocks restoration even when its value equals the baseline`() {
        val before = originalAppearance()
        val pane = newPane(before)
        val checkpoint = GlowPaneCheckpoint(pane, targetAppearance()) { true }
        try {
            checkpoint.apply()
            pane.glowWidth = before.width

            assertFailsWith<IllegalStateException> { checkpoint.restore(setOf(GlowPaneField.WIDTH)) }

            assertEquals(before, pane.captureAppearance())
            assertTrue(checkpoint.hasPending)
            checkpoint.restore()
            assertFalse(checkpoint.hasPending)
        } finally {
            pane.stopAnimation()
        }
    }

    @Test
    fun `newer visibility receipt retains rollback ownership even at the baseline`() {
        val pane = spyk(newPane(originalAppearance()))
        val before = pane.captureAppearance()
        val checkpoint = GlowPaneCheckpoint(pane, before.copy(isVisible = false)) { true }
        try {
            checkpoint.apply()
            pane.isVisible = true

            assertFailsWith<IllegalStateException> { checkpoint.restore(setOf(GlowPaneField.VISIBILITY)) }
            assertTrue(checkpoint.hasPending)
            assertEquals(before, pane.captureAppearance())

            checkpoint.restore()
            assertFalse(checkpoint.hasPending)
            assertEquals(before, pane.captureAppearance())
            verify(exactly = 1) { pane.isVisible = true }
        } finally {
            pane.stopAnimation()
        }
    }

    private fun targetAppearance(): GlowPaneAppearance =
        GlowPaneAppearance(
            Color(0xFFCC66),
            GlowStyle.SHARP_NEON,
            83,
            14,
            GlowPlacement.ISLAND,
            GlowShape.WAVEFORM,
            WaveformConfig(
                movement = WaveformMovement.CLOCKWISE,
                baseline = WaveformBaseline.CENTERED,
                amplitude = 25,
                intensity = 91,
                loopSeconds = 19.5f,
                traceDensity = 2,
                traceLength = 301,
            ),
            isVisible = true,
        )

    private fun originalAppearance(): GlowPaneAppearance =
        GlowPaneAppearance(
            Color(0x5CCFE6),
            GlowStyle.SOFT,
            61,
            9,
            GlowPlacement.SIDE_EDGES,
            GlowShape.SOLID,
            WaveformConfig(
                movement = WaveformMovement.COUNTER_CLOCKWISE,
                baseline = WaveformBaseline.OUTSIDE,
                amplitude = 17,
                intensity = 73,
                loopSeconds = 8.5f,
                traceDensity = 3,
                traceLength = 277,
            ),
            isVisible = true,
        )

    private fun newPane(appearance: GlowPaneAppearance): GlowGlassPane =
        GlowGlassPane(
            appearance.color,
            appearance.style,
            appearance.intensity,
            appearance.width,
            glowPlacement = appearance.placement,
        ).apply {
            configureWaveform(appearance.shape, appearance.waveform)
            isVisible = appearance.isVisible
        }
}
