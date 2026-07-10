package dev.ayuislands.glow.waveform

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WaveformEngineTest {
    @Test
    fun `activating monitor enters waiting state without starting timer`() {
        val engine = WaveformEngine(WaveformConfig())

        val update = engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))

        assertIs<WaveformState.MonitorWaiting>(engine.state)
        assertEquals(TimerDirective.STOP, update.timerDirective)
        assertTrue(update.needsRepaint)
    }

    @Test
    fun `monitor keystroke creates a beat and starts the frame timer`() {
        val engine = WaveformEngine(WaveformConfig(), Random(7))
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))

        val update = engine.handle(WaveformEvent.Keystroke(nowMs = 1_000L))

        val running = assertIs<WaveformState.MonitorRunning>(engine.state)
        assertEquals(1, running.beats.size)
        assertEquals(1_000L, running.beats.single().startMs)
        assertEquals(TimerDirective.START, update.timerDirective)
        assertTrue(update.needsRepaint)
    }

    @Test
    fun `rapid monitor keystrokes schedule beats at the minimum RR interval`() {
        val engine = WaveformEngine(WaveformConfig(), Random(11))
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
        engine.handle(WaveformEvent.Keystroke(nowMs = 1_000L))

        val update = engine.handle(WaveformEvent.Keystroke(nowMs = 1_050L))

        val running = assertIs<WaveformState.MonitorRunning>(engine.state)
        assertEquals(listOf(1_000L, 1_190L), running.beats.map { it.startMs })
        assertEquals(1_050L, running.lastInputMs)
        assertEquals(TimerDirective.KEEP, update.timerDirective)
        assertTrue(update.needsRepaint)
    }

    @Test
    fun `monitor direction reverses travel around the closed perimeter`() {
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
            engine.handle(WaveformEvent.Keystroke(nowMs = 0L))
        }

        val clockwiseFrame =
            clockwise.handle(WaveformEvent.Tick(nowMs = 1_000L, trackLength = 1_000f)).frame
                ?: error("monitor tick must produce a frame")
        val counterFrame =
            counterClockwise.handle(WaveformEvent.Tick(nowMs = 1_000L, trackLength = 1_000f)).frame
                ?: error("monitor tick must produce a frame")

        assertEquals(170f, clockwiseFrame.beats.single().centerDistance, 0.001f)
        assertEquals(830f, counterFrame.beats.single().centerDistance, 0.001f)
        assertEquals(1f, clockwiseFrame.beats.single().opacity)

        val fadingFrame =
            clockwise.handle(WaveformEvent.Tick(nowMs = 5_000L, trackLength = 1_000f)).frame
                ?: error("monitor tick must produce a fading frame")
        assertEquals(0.6f, fadingFrame.beats.single().opacity, 0.001f)
    }

    @Test
    fun `monitor freezes only after beats drain and idle timeout expires`() {
        val engine = WaveformEngine(WaveformConfig(), Random(31))
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
        engine.handle(WaveformEvent.Keystroke(nowMs = 0L))

        val beforeTimeout = engine.handle(WaveformEvent.Tick(nowMs = 3_999L, trackLength = 100f))
        assertIs<WaveformState.MonitorRunning>(engine.state)
        assertEquals(TimerDirective.KEEP, beforeTimeout.timerDirective)
        assertTrue(beforeTimeout.needsRepaint)

        val unchanged = engine.handle(WaveformEvent.Tick(nowMs = 3_999L, trackLength = 100f))
        assertFalse(unchanged.needsRepaint)

        val frozen = engine.handle(WaveformEvent.Tick(nowMs = 4_000L, trackLength = 100f))
        assertIs<WaveformState.MonitorWaiting>(engine.state)
        assertEquals(TimerDirective.STOP, frozen.timerDirective)
        assertTrue(frozen.needsRepaint)
    }

    @Test
    fun `static keystroke starts a bounded pulse envelope`() {
        val engine =
            WaveformEngine(
                WaveformConfig(motion = WaveformMotion.STATIC_PULSE),
                Random(41),
            )
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
        assertIs<WaveformState.StaticWaiting>(engine.state)

        val started = engine.handle(WaveformEvent.Keystroke(nowMs = 1_000L))
        val firstPulse = assertIs<WaveformState.StaticDecaying>(engine.state)
        assertEquals(0.6f, firstPulse.boost, 0.001f)
        assertEquals(TimerDirective.START, started.timerDirective)

        val pumped = engine.handle(WaveformEvent.Keystroke(nowMs = 1_010L))
        val secondPulse = assertIs<WaveformState.StaticDecaying>(engine.state)
        assertEquals(1f, secondPulse.boost)
        assertEquals(TimerDirective.KEEP, pumped.timerDirective)
    }

    @Test
    fun `static pulse decays monotonically and stops after the envelope`() {
        val engine = WaveformEngine(WaveformConfig(motion = WaveformMotion.STATIC_PULSE), Random(51))
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
        engine.handle(WaveformEvent.Keystroke(nowMs = 0L))
        engine.handle(WaveformEvent.Keystroke(nowMs = 0L))

        val halfway = engine.handle(WaveformEvent.Tick(nowMs = 750L, trackLength = 1_000f))
        val halfwayState = assertIs<WaveformState.StaticDecaying>(engine.state)
        assertEquals(0.5f, halfwayState.boost, 0.001f)
        assertEquals(0.5f, halfway.frame?.staticBoost ?: -1f, 0.001f)
        assertEquals(0.775f, halfway.frame?.brightness ?: -1f, 0.001f)

        val finished = engine.handle(WaveformEvent.Tick(nowMs = 1_500L, trackLength = 1_000f))
        assertIs<WaveformState.StaticWaiting>(engine.state)
        assertEquals(TimerDirective.STOP, finished.timerDirective)
        assertEquals(0f, finished.frame?.staticBoost ?: -1f)
        assertEquals(0.55f, finished.frame?.brightness ?: -1f, 0.001f)
    }

    @Test
    fun `monitor queue is bounded during a burst`() {
        val engine = WaveformEngine(WaveformConfig(), Random(61))
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))

        repeat(40) { index ->
            engine.handle(WaveformEvent.Keystroke(nowMs = index.toLong()))
        }

        val running = assertIs<WaveformState.MonitorRunning>(engine.state)
        assertEquals(24, running.beats.size)
        assertEquals(0L, running.beats.first().startMs)
        assertEquals(4_370L, running.beats.last().startMs)
        assertEquals(39L, running.lastInputMs)
    }

    @Test
    fun `keystroke after the monitor queue drains starts a new beat`() {
        val engine = WaveformEngine(WaveformConfig(), Random(65))
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
        engine.handle(WaveformEvent.Keystroke(nowMs = 0L))
        engine.handle(WaveformEvent.Tick(nowMs = 1_000L, trackLength = 100f))

        engine.handle(WaveformEvent.Keystroke(nowMs = 1_500L))

        val running = assertIs<WaveformState.MonitorRunning>(engine.state)
        assertEquals(listOf(1_500L), running.beats.map { it.startMs })
    }

    @Test
    fun `degenerate monitor track clears beats and stops the frame timer`() {
        val engine = WaveformEngine(WaveformConfig(), Random(67))
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
        engine.handle(WaveformEvent.Keystroke(nowMs = 0L))

        val update = engine.handle(WaveformEvent.Tick(nowMs = 1L, trackLength = 0f))

        assertIs<WaveformState.MonitorWaiting>(engine.state)
        assertEquals(TimerDirective.STOP, update.timerDirective)
        assertTrue(update.needsRepaint)
    }

    @Test
    fun `power save pauses animation and ignores keystrokes until resumed`() {
        val engine = WaveformEngine(WaveformConfig(), Random(71))
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
        engine.handle(WaveformEvent.Keystroke(nowMs = 0L))

        val paused = engine.handle(WaveformEvent.PowerSaveChanged(enabled = true))
        assertIs<WaveformState.PowerSavePaused>(engine.state)
        assertEquals(TimerDirective.STOP, paused.timerDirective)
        assertTrue(paused.needsRepaint)

        val ignored = engine.handle(WaveformEvent.Keystroke(nowMs = 1_000L))
        assertIs<WaveformState.PowerSavePaused>(engine.state)
        assertEquals(TimerDirective.KEEP, ignored.timerDirective)
        assertFalse(ignored.needsRepaint)

        val resumed = engine.handle(WaveformEvent.PowerSaveChanged(enabled = false))
        assertIs<WaveformState.MonitorWaiting>(engine.state)
        assertEquals(TimerDirective.STOP, resumed.timerDirective)
        assertTrue(resumed.needsRepaint)
    }

    @Test
    fun `activation enters power save pause without starting animation`() {
        val config = WaveformConfig(motion = WaveformMotion.STATIC_PULSE)
        val engine = WaveformEngine(config)

        val update = engine.handle(WaveformEvent.Activate(powerSaveEnabled = true))

        val paused = assertIs<WaveformState.PowerSavePaused>(engine.state)
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
        val waiting = assertIs<WaveformState.StaticWaiting>(engine.state)
        assertEquals(staticConfig, waiting.config)
        assertEquals(TimerDirective.STOP, reconfigured.timerDirective)
        assertTrue(reconfigured.needsRepaint)

        engine.handle(WaveformEvent.PowerSaveChanged(enabled = true))
        val monitorConfig = WaveformConfig(motion = WaveformMotion.MONITOR)
        engine.handle(WaveformEvent.Configure(monitorConfig))
        val paused = assertIs<WaveformState.PowerSavePaused>(engine.state)
        assertEquals(monitorConfig, paused.config)
    }

    @Test
    fun `identical configuration preserves running animation`() {
        val config = WaveformConfig()
        val engine = WaveformEngine(config, Random(83))
        engine.handle(WaveformEvent.Activate(powerSaveEnabled = false))
        engine.handle(WaveformEvent.Keystroke(nowMs = 0L))

        val update = engine.handle(WaveformEvent.Configure(config))

        val running = assertIs<WaveformState.MonitorRunning>(engine.state)
        assertEquals(1, running.beats.size)
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

        val waiting = assertIs<WaveformState.StaticWaiting>(engine.state)
        assertEquals(staticConfig, waiting.config)
    }
}
