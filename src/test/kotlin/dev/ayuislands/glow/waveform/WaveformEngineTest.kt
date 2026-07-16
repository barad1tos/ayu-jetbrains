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
    fun `activating perimeter loop starts one continuous trace`() {
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

        assertEquals(500f, trace(shortFrame).anchorOffset, 0.001f)
        assertEquals(1_000f, trace(longFrame).anchorOffset, 0.001f)
    }

    @Test
    fun `thirty second loop setting makes a thirty second perimeter sweep`() {
        val engine = WaveformEngine(WaveformConfig(loopSeconds = 30f), Random(6))
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
        engine.handle(WaveformEvent.Tick(nowMs = 0L, trackLength = 1_000f))

        val threeSeconds = requireNotNull(engine.handle(WaveformEvent.Tick(3_000L, 1_000f)).frame)

        assertEquals(100f, trace(threeSeconds).anchorOffset, 0.001f)
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
        assertEquals(0.925f, rising.brightness, 0.001f)
        assertEquals(24.667f, trace(rising).anchorOffset, 0.001f)
        assertEquals(1f, peak.energy, 0.001f)
        assertEquals(0.5f, decaying.energy, 0.001f)
        assertEquals(0f, resting.energy, 0.001f)
        assertEquals(0.85f, resting.brightness, 0.001f)
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

        assertEquals(23.333f, trace(clockwiseFrame).anchorOffset, 0.001f)
        assertEquals(976.667f, trace(counterFrame).anchorOffset, 0.001f)
    }

    @Test
    fun `perimeter loop keeps moving without input or idle timeout`() {
        val engine = WaveformEngine(WaveformConfig(), Random(31))
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
        engine.handle(WaveformEvent.Tick(nowMs = 0L, trackLength = 1_000f))

        val update = engine.handle(WaveformEvent.Tick(nowMs = 5_600L, trackLength = 1_000f))

        assertIs<WaveformState.Looping>(engine.state)
        assertEquals(TimerDirective.KEEP, update.timerDirective)
        assertEquals(186.667f, trace(requireNotNull(update.frame)).anchorOffset, 0.001f)
        assertTrue(update.needsRepaint)
    }

    @Test
    fun `trace history changes on its own cycle without waiting for perimeter wrap`() {
        val engine = WaveformEngine(WaveformConfig(loopSeconds = 30f), Random(37))
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
        val initial = requireNotNull(engine.handle(WaveformEvent.Tick(0L, 1_000f)).frame)
        val withinCycle = requireNotNull(engine.handle(WaveformEvent.Tick(1_000L, 1_000f)).frame)
        val nextCycle = requireNotNull(engine.handle(WaveformEvent.Tick(1_200L, 1_000f)).frame)

        val initialSamples = morphologySamples(initial)
        assertEquals(initialSamples, morphologySamples(withinCycle))
        assertTrue(initialSamples != morphologySamples(nextCycle))
        assertTrue(
            trace(nextCycle).anchorOffset < 50f,
            "the 30 s perimeter loop must still be near its start",
        )
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
    fun `degenerate perimeter track keeps phase timer alive without a beat`() {
        val engine = WaveformEngine(WaveformConfig(), Random(67))
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))

        val update = engine.handle(WaveformEvent.Tick(nowMs = 1L, trackLength = 0f))

        assertIs<WaveformState.Looping>(engine.state)
        assertEquals(TimerDirective.KEEP, update.timerDirective)
        assertEquals(null, requireNotNull(update.frame).trace)
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
        assertEquals(0f, running.travelPhase)
        assertEquals(TimerDirective.START, resumed.timerDirective)
        assertTrue(resumed.needsRepaint)
    }

    @Test
    fun `activation enters power save pause without starting animation`() {
        val config = WaveformConfig()
        val engine = WaveformEngine(config)

        val update = engine.handle(WaveformEvent.Activate(powerSaveEnabled = true))

        val paused = assertIs<WaveformState.Suspended>(engine.state)
        assertEquals(config, paused.config)
        assertEquals(TimerDirective.STOP, update.timerDirective)
    }

    @Test
    fun `configuration changes preserve paused state`() {
        val engine = WaveformEngine(WaveformConfig(), Random(81))
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
        engine.handle(WaveformEvent.PowerSaveChanged(enabled = true))
        val updatedConfig = WaveformConfig(amplitude = 24)

        val update = engine.handle(WaveformEvent.Configure(updatedConfig))

        val paused = assertIs<WaveformState.Suspended>(engine.state)
        assertEquals(updatedConfig, paused.config)
        assertEquals(TimerDirective.STOP, update.timerDirective)
        assertTrue(update.needsRepaint)
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
        assertEquals(0.024_667f, running.travelPhase, 0.001f)
        assertEquals(0.5f, requireNotNull(running.energyEnvelope).levelAt(740L), 0.001f)
        assertEquals(updatedConfig, running.config)
        assertEquals(TimerDirective.KEEP, update.timerDirective)
        assertTrue(update.needsRepaint)
    }

    @Test
    fun `refresh activation preserves the configured moving trace`() {
        val engine = WaveformEngine(WaveformConfig(), Random(84))
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
        engine.handle(WaveformEvent.Tick(0L, 1_000f))
        engine.handle(WaveformEvent.Keystroke(100L))
        engine.handle(WaveformEvent.Tick(140L, 1_000f))
        engine.handle(WaveformEvent.Configure(WaveformConfig(amplitude = 24)))
        val configured = assertIs<WaveformState.Looping>(engine.state)
        assertTrue(configured.travelPhase > 0f)
        assertNotNull(configured.energyEnvelope)

        val update = engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))

        assertEquals(configured, engine.state, "refresh activation must not restart the live trace")
        assertEquals(TimerDirective.KEEP, update.timerDirective)
        assertFalse(update.needsRepaint)
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
        val config = WaveformConfig()
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
        val updatedConfig = WaveformConfig(amplitude = 24)

        engine.handle(WaveformEvent.Configure(updatedConfig))
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))

        val running = assertIs<WaveformState.Looping>(engine.state)
        assertEquals(updatedConfig, running.config)
    }

    @Test
    fun `single keystroke leaves trace rate at rest`() {
        val typing = WaveformEngine(WaveformConfig(), Random(11))
        val idle = WaveformEngine(WaveformConfig(), Random(11))
        for (engine in listOf(typing, idle)) {
            engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
            engine.handle(WaveformEvent.Tick(nowMs = 0L, trackLength = 1_000f))
        }

        typing.handle(WaveformEvent.Keystroke(nowMs = 100L))
        val typingFrame = requireNotNull(typing.handle(WaveformEvent.Tick(700L, 1_000f)).frame)
        val idleFrame = requireNotNull(idle.handle(WaveformEvent.Tick(700L, 1_000f)).frame)

        assertEquals(1f, assertIs<WaveformState.Looping>(typing.state).traceRate, 0.001f)
        assertEquals(
            trace(idleFrame).anchorOffset,
            trace(typingFrame).anchorOffset,
            0.001f,
        )
    }

    @Test
    fun `typing burst accelerates the trace without changing perimeter travel`() {
        val typing = WaveformEngine(WaveformConfig(), Random(13))
        val idle = WaveformEngine(WaveformConfig(), Random(13))
        for (engine in listOf(typing, idle)) {
            engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
            engine.handle(WaveformEvent.Tick(nowMs = 0L, trackLength = 10_000f))
        }
        typing.handle(WaveformEvent.Keystroke(nowMs = 0L))

        var previousRate = 1f
        var typingFrame: WaveformFrame? = null
        var idleFrame: WaveformFrame? = null
        for (tick in 1..10) {
            val nowMs = tick * 100L
            typing.handle(WaveformEvent.Keystroke(nowMs))
            typingFrame = typing.handle(WaveformEvent.Tick(nowMs, 10_000f)).frame
            idleFrame = idle.handle(WaveformEvent.Tick(nowMs, 10_000f)).frame

            val rate = assertIs<WaveformState.Looping>(typing.state).traceRate
            assertTrue(rate >= previousRate, "trace rate dropped mid-burst: $previousRate -> $rate")
            assertTrue(rate - previousRate <= 0.121f, "trace rate jumped: $previousRate -> $rate")
            previousRate = rate
        }

        val typingTrace = trace(requireNotNull(typingFrame))
        val idleTrace = trace(requireNotNull(idleFrame))
        assertEquals(2.2f, previousRate, 0.01f)
        assertEquals(idleTrace.anchorOffset, typingTrace.anchorOffset, 0.001f)
        assertTrue(typingTrace.phase != idleTrace.phase, "typing must advance the internal ECG trace")
    }

    @Test
    fun `sustained fast typing keeps one moving trace with varied history`() {
        val engine = WaveformEngine(WaveformConfig(), Random(17))
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
        engine.handle(WaveformEvent.Tick(nowMs = 0L, trackLength = 10_000f))

        var frame: WaveformFrame? = null
        for (tick in 1..80) {
            val nowMs = tick * 50L
            engine.handle(WaveformEvent.Keystroke(nowMs))
            frame = engine.handle(WaveformEvent.Tick(nowMs, 10_000f)).frame
        }

        val trace = trace(requireNotNull(frame))
        assertEquals(4, trace.history.size)
        assertEquals(4, trace.history.toSet().size)
    }

    @Test
    fun `maximum trace density starts with sixteen varied complexes`() {
        val engine =
            WaveformEngine(
                WaveformConfig(traceDensity = MAX_TRACE_DENSITY),
                Random(18),
            )

        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
        val frame = requireNotNull(engine.handle(WaveformEvent.Tick(0L, 1_000f)).frame)
        val history = trace(frame).history

        assertEquals(16, history.size)
        assertEquals(16, history.map(::morphologySamples).toSet().size)
    }

    @Test
    fun `raising trace density fills the moving trace immediately`() {
        val engine = WaveformEngine(WaveformConfig(), Random(20))
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
        engine.handle(WaveformEvent.Tick(0L, 1_000f))

        engine.handle(
            WaveformEvent.Configure(
                WaveformConfig(traceDensity = MAX_TRACE_DENSITY),
            ),
        )
        val frame = requireNotNull(engine.handle(WaveformEvent.Tick(1L, 1_000f)).frame)

        assertEquals(16, trace(frame).history.size)
    }

    @Test
    fun `idle eases the trace rate back while keeping one moving window`() {
        val engine = WaveformEngine(WaveformConfig(), Random(19))
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
        engine.handle(WaveformEvent.Tick(nowMs = 0L, trackLength = 10_000f))
        for (tick in 1..80) {
            val nowMs = tick * 50L
            engine.handle(WaveformEvent.Keystroke(nowMs))
            engine.handle(WaveformEvent.Tick(nowMs, 10_000f))
        }
        assertTrue(assertIs<WaveformState.Looping>(engine.state).traceRate > 2f)

        var frame: WaveformFrame? = null
        for (tick in 81..280) {
            frame = engine.handle(WaveformEvent.Tick(tick * 50L, 10_000f)).frame
        }

        assertEquals(1f, assertIs<WaveformState.Looping>(engine.state).traceRate, 0.001f)
        assertNotNull(requireNotNull(frame).trace)
    }

    private fun morphologySamples(frame: WaveformFrame): List<Float> {
        val morphology = trace(frame).history.first()
        return morphologySamples(morphology)
    }

    private fun trace(frame: WaveformFrame): FrameTrace = requireNotNull(frame.trace)

    private fun morphologySamples(morphology: BeatMorphology): List<Float> =
        (0..100).map { sample -> morphology.valueAt(sample / 100f) }
}
