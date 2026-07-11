package dev.ayuislands.glow.waveform

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WaveformEngineTest {
    @Test
    fun `activating perimeter loop starts continuous motion without queued beats`() {
        val engine = WaveformEngine(WaveformConfig())

        val update = engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))

        assertIs<WaveformState.Looping>(engine.state)
        assertEquals(TimerDirective.START, update.timerDirective)
        assertTrue(update.needsRepaint)
    }

    @Test
    fun `perimeter loop period is independent of track length`() {
        val shortTrack = WaveformEngine(WaveformConfig(loopSeconds = 2.8f), Random(5))
        val longTrack = WaveformEngine(WaveformConfig(loopSeconds = 2.8f), Random(5))
        for (engine in listOf(shortTrack, longTrack)) {
            engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
            engine.handle(WaveformEvent.Tick(nowMs = 0L, trackLength = 1_000f))
        }

        val shortFrame = requireNotNull(shortTrack.handle(WaveformEvent.Tick(1_400L, 1_000f)).frame)
        val longFrame = requireNotNull(longTrack.handle(WaveformEvent.Tick(1_400L, 2_000f)).frame)

        assertEquals(500f, shortFrame.beats.single().centerDistance, 0.001f)
        assertEquals(1_000f, longFrame.beats.single().centerDistance, 0.001f)
    }

    @Test
    fun `perimeter keystroke raises energy without restarting phase`() {
        val engine = WaveformEngine(WaveformConfig(), Random(7))
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
        engine.handle(WaveformEvent.Tick(nowMs = 0L, trackLength = 1_000f))
        engine.handle(WaveformEvent.Tick(nowMs = 700L, trackLength = 1_000f))

        val input = engine.handle(WaveformEvent.Keystroke(nowMs = 700L))
        val rising = requireNotNull(engine.handle(WaveformEvent.Tick(740L, 1_000f)).frame)
        val peak = requireNotNull(engine.handle(WaveformEvent.Tick(780L, 1_000f)).frame)
        val decaying = requireNotNull(engine.handle(WaveformEvent.Tick(1_080L, 1_000f)).frame)
        val resting = requireNotNull(engine.handle(WaveformEvent.Tick(1_380L, 1_000f)).frame)

        assertEquals(TimerDirective.KEEP, input.timerDirective)
        assertEquals(0.5f, rising.energy, 0.001f)
        assertEquals(0.675f, rising.brightness, 0.001f)
        assertEquals(264.286f, rising.beats.single().centerDistance, 0.001f)
        assertEquals(1f, peak.energy, 0.001f)
        assertEquals(0.5f, decaying.energy, 0.001f)
        assertEquals(0f, resting.energy, 0.001f)
        assertEquals(0.35f, resting.brightness, 0.001f)
    }

    @Test
    fun `repeated perimeter input restores energy without creating a queue`() {
        val engine = WaveformEngine(WaveformConfig(), Random(11))
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
        engine.handle(WaveformEvent.Tick(nowMs = 0L, trackLength = 1_000f))
        engine.handle(WaveformEvent.Keystroke(nowMs = 0L))
        engine.handle(WaveformEvent.Tick(nowMs = 80L, trackLength = 1_000f))
        val decaying = requireNotNull(engine.handle(WaveformEvent.Tick(500L, 1_000f)).frame)

        engine.handle(WaveformEvent.Keystroke(nowMs = 500L))
        val risingAgain = requireNotNull(engine.handle(WaveformEvent.Tick(540L, 1_000f)).frame)
        val peakAgain = requireNotNull(engine.handle(WaveformEvent.Tick(580L, 1_000f)).frame)
        val resting = requireNotNull(engine.handle(WaveformEvent.Tick(1_180L, 1_000f)).frame)

        assertIs<WaveformState.Looping>(engine.state)
        assertEquals(0.3f, decaying.energy, 0.001f)
        assertEquals(0.65f, risingAgain.energy, 0.001f)
        assertEquals(1f, peakAgain.energy, 0.001f)
        assertEquals(0f, resting.energy, 0.001f)
    }

    @Test
    fun `perimeter direction reverses continuous travel around the closed track`() {
        val clockwise =
            WaveformEngine(
                WaveformConfig(direction = WaveformDirection.CLOCKWISE),
                Random(21),
            )
        val counterClockwise =
            WaveformEngine(
                WaveformConfig(direction = WaveformDirection.COUNTER_CLOCKWISE),
                Random(21),
            )
        for (engine in listOf(clockwise, counterClockwise)) {
            engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
            engine.handle(WaveformEvent.Tick(nowMs = 0L, trackLength = 1_000f))
        }

        val clockwiseFrame =
            clockwise.handle(WaveformEvent.Tick(nowMs = 700L, trackLength = 1_000f)).frame
                ?: error("perimeter tick must produce a frame")
        val counterFrame =
            counterClockwise.handle(WaveformEvent.Tick(nowMs = 700L, trackLength = 1_000f)).frame
                ?: error("perimeter tick must produce a frame")

        assertEquals(250f, clockwiseFrame.beats.single().centerDistance, 0.001f)
        assertEquals(750f, counterFrame.beats.single().centerDistance, 0.001f)
    }

    @Test
    fun `perimeter loop keeps moving without input or idle timeout`() {
        val engine = WaveformEngine(WaveformConfig(), Random(31))
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
        engine.handle(WaveformEvent.Tick(nowMs = 0L, trackLength = 1_000f))

        val update = engine.handle(WaveformEvent.Tick(nowMs = 5_600L, trackLength = 1_000f))

        assertIs<WaveformState.Looping>(engine.state)
        assertEquals(TimerDirective.KEEP, update.timerDirective)
        assertEquals(0f, requireNotNull(update.frame).beats.single().centerDistance, 0.001f)
        assertTrue(update.needsRepaint)
    }

    @Test
    fun `perimeter morphology stays stable within a loop and changes at wrap`() {
        val engine = WaveformEngine(WaveformConfig(loopSeconds = 2.8f), Random(37))
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
        val initial = requireNotNull(engine.handle(WaveformEvent.Tick(0L, 1_000f)).frame)
        val withinLoop = requireNotNull(engine.handle(WaveformEvent.Tick(2_000L, 1_000f)).frame)
        val nextLoop = requireNotNull(engine.handle(WaveformEvent.Tick(2_800L, 1_000f)).frame)

        val initialSamples = morphologySamples(initial)
        assertEquals(initialSamples, morphologySamples(withinLoop))
        assertTrue(initialSamples != morphologySamples(nextLoop))
    }

    @Test
    fun `static keystroke starts the shared typing energy envelope`() {
        val engine =
            WaveformEngine(
                WaveformConfig(motion = WaveformMotion.STATIC_PULSE),
                Random(41),
            )
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
        assertIs<WaveformState.StaticResting>(engine.state)

        val started = engine.handle(WaveformEvent.Keystroke(nowMs = 1_000L))
        assertIs<WaveformState.StaticResponding>(engine.state)
        assertEquals(TimerDirective.START, started.timerDirective)
        val rising = requireNotNull(engine.handle(WaveformEvent.Tick(1_040L, 1_000f)).frame)
        val sessionMorphology = morphologySamples(rising.morphology)

        val pumped = engine.handle(WaveformEvent.Keystroke(nowMs = 1_040L))
        val peak = requireNotNull(engine.handle(WaveformEvent.Tick(1_080L, 1_000f)).frame)

        assertEquals(0.5f, rising.energy, 0.001f)
        assertEquals(1f, peak.energy, 0.001f)
        assertEquals(sessionMorphology, morphologySamples(peak.morphology))
        assertEquals(TimerDirective.KEEP, pumped.timerDirective)
    }

    @Test
    fun `static pulse decays to resting energy and stops its timer`() {
        val engine = WaveformEngine(WaveformConfig(motion = WaveformMotion.STATIC_PULSE), Random(51))
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
        engine.handle(WaveformEvent.Keystroke(nowMs = 0L))
        val peak = engine.handle(WaveformEvent.Tick(nowMs = 80L, trackLength = 1_000f))

        val halfway = engine.handle(WaveformEvent.Tick(nowMs = 380L, trackLength = 1_000f))
        val halfwayState = assertIs<WaveformState.StaticResponding>(engine.state)
        assertEquals(0.5f, halfwayState.energyEnvelope.levelAt(380L), 0.001f)
        assertEquals(1f, peak.frame?.energy ?: -1f, 0.001f)
        assertEquals(0.5f, halfway.frame?.energy ?: -1f, 0.001f)
        assertEquals(0.675f, halfway.frame?.brightness ?: -1f, 0.001f)

        val finished = engine.handle(WaveformEvent.Tick(nowMs = 680L, trackLength = 1_000f))
        assertIs<WaveformState.StaticResting>(engine.state)
        assertEquals(TimerDirective.STOP, finished.timerDirective)
        assertEquals(0f, finished.frame?.energy ?: -1f)
        assertEquals(0.35f, finished.frame?.brightness ?: -1f, 0.001f)
    }

    @Test
    fun `perimeter keeps a fresh energy envelope when input and tick share a millisecond`() {
        val engine = WaveformEngine(WaveformConfig(), Random(53))
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
        engine.handle(WaveformEvent.Tick(nowMs = 1_000L, trackLength = 1_000f))
        engine.handle(WaveformEvent.Keystroke(nowMs = 1_000L))

        engine.handle(WaveformEvent.Tick(nowMs = 1_000L, trackLength = 1_000f))
        val looping = assertIs<WaveformState.Looping>(engine.state)
        val rising = requireNotNull(engine.handle(WaveformEvent.Tick(1_040L, 1_000f)).frame)

        assertNotNull(looping.energyEnvelope)
        assertEquals(0.5f, rising.energy, 0.001f)
    }

    @Test
    fun `static keeps responding when input and tick share a millisecond`() {
        val engine = WaveformEngine(WaveformConfig(motion = WaveformMotion.STATIC_PULSE), Random(59))
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
        engine.handle(WaveformEvent.Keystroke(nowMs = 1_000L))

        val immediate = engine.handle(WaveformEvent.Tick(nowMs = 1_000L, trackLength = 1_000f))
        val rising = requireNotNull(engine.handle(WaveformEvent.Tick(1_040L, 1_000f)).frame)

        assertIs<WaveformState.StaticResponding>(engine.state)
        assertEquals(TimerDirective.KEEP, immediate.timerDirective)
        assertEquals(0.5f, rising.energy, 0.001f)
    }

    @Test
    fun `degenerate perimeter track keeps phase timer alive without a beat`() {
        val engine = WaveformEngine(WaveformConfig(), Random(67))
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))

        val update = engine.handle(WaveformEvent.Tick(nowMs = 1L, trackLength = 0f))

        assertIs<WaveformState.Looping>(engine.state)
        assertEquals(TimerDirective.KEEP, update.timerDirective)
        assertTrue(requireNotNull(update.frame).beats.isEmpty())
        assertFalse(update.needsRepaint)
    }

    @Test
    fun `power save pauses animation and ignores keystrokes until resumed`() {
        val engine = WaveformEngine(WaveformConfig(), Random(71))
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
        engine.handle(WaveformEvent.Keystroke(nowMs = 0L))

        val paused = engine.handle(WaveformEvent.PowerSaveChanged(enabled = true))
        assertIs<WaveformState.Suspended>(engine.state)
        assertEquals(TimerDirective.STOP, paused.timerDirective)
        assertTrue(paused.needsRepaint)

        val ignored = engine.handle(WaveformEvent.Keystroke(nowMs = 1_000L))
        assertIs<WaveformState.Suspended>(engine.state)
        assertEquals(TimerDirective.KEEP, ignored.timerDirective)
        assertFalse(ignored.needsRepaint)

        val resumed = engine.handle(WaveformEvent.PowerSaveChanged(enabled = false))
        val running = assertIs<WaveformState.Looping>(engine.state)
        assertEquals(0f, running.phase)
        assertEquals(TimerDirective.START, resumed.timerDirective)
        assertTrue(resumed.needsRepaint)
    }

    @Test
    fun `activation enters power save pause without starting animation`() {
        val config = WaveformConfig(motion = WaveformMotion.STATIC_PULSE)
        val engine = WaveformEngine(config)

        val update = engine.handle(WaveformEvent.Activate(powerSaveEnabled = true))

        val paused = assertIs<WaveformState.Suspended>(engine.state)
        assertEquals(config, paused.config)
        assertEquals(TimerDirective.STOP, update.timerDirective)
    }

    @Test
    fun `configuration changes clear transient animation but preserve paused state`() {
        val engine = WaveformEngine(WaveformConfig(), Random(81))
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
        engine.handle(WaveformEvent.Keystroke(nowMs = 0L))
        val staticConfig = WaveformConfig(motion = WaveformMotion.STATIC_PULSE)

        val reconfigured = engine.handle(WaveformEvent.Configure(staticConfig))
        val waiting = assertIs<WaveformState.StaticResting>(engine.state)
        assertEquals(staticConfig, waiting.config)
        assertEquals(TimerDirective.STOP, reconfigured.timerDirective)
        assertTrue(reconfigured.needsRepaint)

        engine.handle(WaveformEvent.PowerSaveChanged(enabled = true))
        val monitorConfig = WaveformConfig(motion = WaveformMotion.MONITOR)
        engine.handle(WaveformEvent.Configure(monitorConfig))
        val paused = assertIs<WaveformState.Suspended>(engine.state)
        assertEquals(monitorConfig, paused.config)
    }

    @Test
    fun `perimeter configuration preserves phase and typing energy`() {
        val engine = WaveformEngine(WaveformConfig(), Random(82))
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
        engine.handle(WaveformEvent.Tick(0L, 1_000f))
        engine.handle(WaveformEvent.Tick(700L, 1_000f))
        engine.handle(WaveformEvent.Keystroke(700L))
        engine.handle(WaveformEvent.Tick(740L, 1_000f))
        val updatedConfig =
            WaveformConfig(
                direction = WaveformDirection.COUNTER_CLOCKWISE,
                amplitude = 24,
                loopSeconds = 6f,
            )

        val update = engine.handle(WaveformEvent.Configure(updatedConfig))

        val running = assertIs<WaveformState.Looping>(engine.state)
        assertEquals(0.264_286f, running.phase, 0.001f)
        assertEquals(0.5f, requireNotNull(running.energyEnvelope).levelAt(740L), 0.001f)
        assertEquals(updatedConfig, running.config)
        assertEquals(TimerDirective.KEEP, update.timerDirective)
        assertTrue(update.needsRepaint)
    }

    @Test
    fun `identical configuration preserves running animation`() {
        val config = WaveformConfig()
        val engine = WaveformEngine(config, Random(83))
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
        engine.handle(WaveformEvent.Keystroke(nowMs = 0L))

        val update = engine.handle(WaveformEvent.Configure(config))

        assertIs<WaveformState.Looping>(engine.state)
        assertEquals(TimerDirective.KEEP, update.timerDirective)
        assertFalse(update.needsRepaint)
    }

    @Test
    fun `deactivation clears transient state and returns to inactive`() {
        val config = WaveformConfig(motion = WaveformMotion.STATIC_PULSE)
        val engine = WaveformEngine(config)
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
        engine.handle(WaveformEvent.Keystroke(nowMs = 0L))

        val update = engine.handle(WaveformEvent.Deactivate)

        val inactive = assertIs<WaveformState.Inactive>(engine.state)
        assertEquals(config, inactive.config)
        assertEquals(TimerDirective.STOP, update.timerDirective)
        assertTrue(update.needsRepaint)
    }

    @Test
    fun `render failure is terminal and requests solid fallback`() {
        val engine = WaveformEngine(WaveformConfig())
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))

        val failed = engine.handle(WaveformEvent.RenderFailed)
        assertEquals(WaveformState.Failed, engine.state)
        assertEquals(TimerDirective.STOP, failed.timerDirective)
        assertTrue(failed.fallbackToSolid)
        assertTrue(failed.needsRepaint)

        val terminal = engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
        assertEquals(WaveformState.Failed, engine.state)
        assertTrue(terminal.fallbackToSolid)
        assertFalse(terminal.needsRepaint)
    }

    @Test
    fun `inactive configuration is retained for the next activation`() {
        val engine = WaveformEngine(WaveformConfig())
        val staticConfig = WaveformConfig(motion = WaveformMotion.STATIC_PULSE)

        engine.handle(WaveformEvent.Configure(staticConfig))
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))

        val waiting = assertIs<WaveformState.StaticResting>(engine.state)
        assertEquals(staticConfig, waiting.config)
    }

    private fun morphologySamples(frame: WaveformFrame): List<Float> {
        val morphology = frame.beats.single().morphology
        return morphologySamples(morphology)
    }

    private fun morphologySamples(morphology: BeatMorphology): List<Float> =
        (0..100).map { sample -> morphology.valueAt(sample / 100f) }
}
