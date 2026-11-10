package dev.ayuislands.glow

import com.intellij.testFramework.junit5.RunInEdt
import dev.ayuislands.glow.waveform.WaveformConfig
import dev.ayuislands.glow.waveform.WaveformEngine
import dev.ayuislands.glow.waveform.WaveformEvent
import io.mockk.every
import io.mockk.spyk
import io.mockk.unmockkAll
import java.awt.Color
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunInEdt
class GlowWaveformRecoveryTest {
    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `NaN payload only changes preserve a failed waveform without creating a retry engine`() {
        val pane = newPane()
        val before = WaveformConfig(loopSeconds = Float.fromBits(0x7fc00001))
        val after = before.copy(loopSeconds = Float.fromBits(0x7fc00002))
        try {
            pane.configureWaveform(GlowShape.WAVEFORM, before)
            pane.failRouteWaveform(IllegalStateException("Existing failure"))
            val failure = assertNotNull(pane.waveformFailure)
            pane.configureWaveform(GlowShape.WAVEFORM, after) { error("No retry for equal native configuration") }
            assertSame(failure, pane.waveformFailure)
            val actual = pane.captureAppearance().waveform
            assertEquals(after.loopSeconds.toRawBits(), actual.loopSeconds.toRawBits())
        } finally {
            pane.stopAnimation()
        }
    }

    @Test
    fun `initial retry and restarted engines are recorded before repaint can throw`() {
        for (operation in listOf("initial", "retry", "restart")) {
            val pane = spyk(newPane())
            val config = WaveformConfig()
            if (operation != "initial") pane.configureWaveform(GlowShape.WAVEFORM, config)
            if (operation == "retry") pane.failRouteWaveform(IllegalStateException("Baseline failure"))
            if (operation == "restart") pane.stopAnimation()
            val engines = mutableListOf<WaveformEngine>()
            val cause = IllegalStateException("After engine publication")
            var recordedAtRepaint: WaveformEngine? = null
            every { pane.repaint() } answers {
                recordedAtRepaint = engines.lastOrNull()
                throw cause
            }
            try {
                assertSame(
                    cause,
                    assertFailsWith<IllegalStateException> {
                        if (operation == "restart") {
                            pane.activateWaveform(powerSaveEnabled = true, onEngineCreated = engines::add)
                        } else {
                            pane.configureWaveform(
                                GlowShape.WAVEFORM,
                                config.copy(amplitude = config.amplitude + 1),
                                onEngineCreated = engines::add,
                            )
                        }
                    },
                )
                assertEquals(1, engines.size, operation)
                assertSame(engines.single(), recordedAtRepaint, operation)
            } finally {
                pane.stopAnimation()
                unmockkAll()
            }
        }
    }

    @Test
    fun `recording failure never publishes an unrecorded engine`() {
        val pane = newPane()
        val cause = IllegalStateException("Cannot retain engine")
        var rejected: WaveformEngine? = null
        val restarted = mutableListOf<WaveformEngine>()
        try {
            assertSame(
                cause,
                assertFailsWith<IllegalStateException> {
                    pane.configureWaveform(GlowShape.WAVEFORM, WaveformConfig()) { created ->
                        rejected = created
                        throw cause
                    }
                },
            )
            pane.activateWaveform(powerSaveEnabled = true, onEngineCreated = restarted::add)

            assertNotNull(rejected)
            assertEquals(1, restarted.size)
            assertNotSame(rejected, restarted.single())
        } finally {
            pane.stopAnimation()
        }
    }

    @Test
    fun `local failure belongs to the exact engine recorded by its caller`() {
        val pane = newPane()
        val engines = mutableListOf<WaveformEngine>()
        try {
            pane.configureWaveform(GlowShape.WAVEFORM, WaveformConfig(), onEngineCreated = engines::add)
            assertEquals(1, engines.size)
            engines.single().handle(WaveformEvent.RenderFailed)
            pane.activateWaveform(powerSaveEnabled = false)

            val failure = assertNotNull(pane.waveformFailure)
            assertSame(engines.single(), assertIs<GlowFailureOrigin.Local>(failure.origin).engine)
        } finally {
            pane.stopAnimation()
        }
    }

    @Test
    fun `legacy route failure never impersonates a local engine failure`() {
        val pane = newPane()
        try {
            pane.configureWaveform(GlowShape.WAVEFORM, WaveformConfig())
            pane.failRouteWaveform(IllegalStateException("Unattributed route failure"))

            val origin = assertIs<GlowFailureOrigin.Route>(assertNotNull(pane.waveformFailure).origin)
            assertNull(origin.playback)
        } finally {
            pane.stopAnimation()
        }
    }

    @Test
    fun `guarded health restoration restarts healthy playback and reinstates failed baseline`() {
        val pane = newPane()
        val config = WaveformConfig()
        try {
            pane.configureWaveform(GlowShape.WAVEFORM, config)
            pane.failRouteWaveform(IllegalStateException("Candidate failure"))
            val candidateFailure = assertNotNull(pane.waveformFailure)

            pane.restoreWaveformFailure(candidateFailure, null)
            pane.activateWaveform(powerSaveEnabled = true)

            assertNull(pane.waveformFailure)
            assertTrue(pane.isWaveform)
            pane.failRouteWaveform(IllegalStateException("Failed baseline"))
            val before = assertNotNull(pane.waveformFailure)
            pane.configureWaveform(GlowShape.WAVEFORM, config.copy(amplitude = config.amplitude + 1))
            assertNull(pane.waveformFailure)
            pane.configureWaveform(GlowShape.WAVEFORM, config, preserveFailure = true)

            pane.restoreWaveformFailure(null, before)
            pane.activateWaveform(powerSaveEnabled = false)

            assertSame(before, pane.waveformFailure)
            assertFalse(pane.isWaveform)
            assertEquals(config, pane.captureAppearance().waveform)
        } finally {
            pane.stopAnimation()
        }
    }

    @Test
    fun `guarded health restoration rejects a different failure occurrence`() {
        val pane = newPane()
        try {
            pane.configureWaveform(GlowShape.WAVEFORM, WaveformConfig())
            pane.failRouteWaveform(IllegalStateException("Candidate failure"))
            val expected = assertNotNull(pane.waveformFailure)
            pane.failRouteWaveform(IllegalStateException("Later failure"))
            val later = assertNotNull(pane.waveformFailure)

            assertFailsWith<IllegalStateException> { pane.restoreWaveformFailure(expected, null) }

            assertSame(later, pane.waveformFailure)
        } finally {
            pane.stopAnimation()
        }
    }

    private fun newPane(): GlowGlassPane = GlowGlassPane(Color.CYAN, GlowStyle.SOFT, 50, 8)
}
