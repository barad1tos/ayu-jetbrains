package dev.ayuislands.glow.waveform

import com.intellij.util.concurrency.annotations.RequiresEdt
import kotlin.random.Random

internal enum class TimerDirective {
    KEEP,
    START,
    STOP,
}

internal sealed interface WaveformEvent {
    data class Activate(
        val powerSaveEnabled: Boolean,
    ) : WaveformEvent

    data object Deactivate : WaveformEvent

    data class Configure(
        val config: WaveformConfig,
    ) : WaveformEvent

    data class Keystroke(
        val nowMs: Long,
    ) : WaveformEvent

    data class Tick(
        val nowMs: Long,
        val trackLength: Float,
    ) : WaveformEvent

    data class PowerSaveChanged(
        val enabled: Boolean,
    ) : WaveformEvent

    data object RenderFailed : WaveformEvent
}

internal data class ScheduledBeat(
    val startMs: Long,
    val morphology: BeatMorphology,
)

internal data class FrameBeat(
    val centerDistance: Float,
    val morphology: BeatMorphology,
    val opacity: Float,
)

internal data class WaveformFrame(
    val nowMs: Long,
    val config: WaveformConfig,
    val beats: List<FrameBeat> = emptyList(),
    val staticBoost: Float = 0f,
    val brightness: Float = 1f,
)

internal sealed interface WaveformState {
    val config: WaveformConfig?

    data class Inactive(
        override val config: WaveformConfig,
    ) : WaveformState

    data class MonitorWaiting(
        override val config: WaveformConfig,
    ) : WaveformState

    data class MonitorRunning(
        override val config: WaveformConfig,
        val beats: List<ScheduledBeat>,
        val lastInputMs: Long,
    ) : WaveformState

    data class StaticWaiting(
        override val config: WaveformConfig,
    ) : WaveformState

    data class StaticDecaying(
        override val config: WaveformConfig,
        val boost: Float,
        val lastTickMs: Long,
    ) : WaveformState

    data class PowerSavePaused(
        override val config: WaveformConfig,
    ) : WaveformState

    data object Failed : WaveformState {
        override val config: WaveformConfig? = null
    }
}

internal data class WaveformUpdate(
    val timerDirective: TimerDirective = TimerDirective.KEEP,
    val needsRepaint: Boolean = false,
    val fallbackToSolid: Boolean = false,
    val frame: WaveformFrame? = null,
)

/*
 * Inactive -> MonitorWaiting | StaticWaiting on Activate.
 * MonitorWaiting -> MonitorRunning on Keystroke; MonitorRunning ->
 * MonitorWaiting after queue drain + idle timeout.
 * StaticWaiting -> StaticDecaying on Keystroke; StaticDecaying ->
 * StaticWaiting after envelope decay.
 * Every active state -> PowerSavePaused on PowerSaveOn, then back to the
 * configured waiting state on PowerSaveOff.
 * Every non-failed state -> Inactive on Deactivate and -> Failed on
 * RenderFailed. Failed is terminal. Other cells are explicit ignores.
 */
internal class WaveformEngine(
    initialConfig: WaveformConfig,
    private val random: Random = Random.Default,
) {
    internal var state: WaveformState = WaveformState.Inactive(initialConfig)
        private set

    @RequiresEdt
    fun handle(event: WaveformEvent): WaveformUpdate {
        val transition =
            when (event) {
                is WaveformEvent.Activate -> activate(event)
                WaveformEvent.Deactivate -> deactivate()
                is WaveformEvent.Configure -> configure(event.config)
                is WaveformEvent.Keystroke -> keystroke(event)
                is WaveformEvent.Tick -> tick(event)
                is WaveformEvent.PowerSaveChanged -> changePowerSave(event.enabled)
                WaveformEvent.RenderFailed -> fail()
            }
        state = transition.state
        return transition.update
    }

    private fun activate(event: WaveformEvent.Activate): Transition =
        when (val current = state) {
            is WaveformState.Inactive -> {
                val next =
                    if (event.powerSaveEnabled) {
                        WaveformState.PowerSavePaused(current.config)
                    } else {
                        waitingState(current.config)
                    }
                Transition(next, WaveformUpdate(TimerDirective.STOP, needsRepaint = true))
            }
            is WaveformState.MonitorWaiting -> ignore(current)
            is WaveformState.MonitorRunning -> ignore(current)
            is WaveformState.StaticWaiting -> ignore(current)
            is WaveformState.StaticDecaying -> ignore(current)
            is WaveformState.PowerSavePaused -> ignore(current)
            WaveformState.Failed -> failedTerminal()
        }

    private fun deactivate(): Transition =
        when (val current = state) {
            is WaveformState.Inactive -> ignore(current)
            is WaveformState.MonitorWaiting -> inactive(current.config)
            is WaveformState.MonitorRunning -> inactive(current.config)
            is WaveformState.StaticWaiting -> inactive(current.config)
            is WaveformState.StaticDecaying -> inactive(current.config)
            is WaveformState.PowerSavePaused -> inactive(current.config)
            WaveformState.Failed -> failedTerminal()
        }

    private fun configure(config: WaveformConfig): Transition =
        when (val current = state) {
            is WaveformState.Inactive -> Transition(current.copy(config = config), WaveformUpdate())
            is WaveformState.MonitorWaiting -> reconfigure(current, config)
            is WaveformState.MonitorRunning -> reconfigure(current, config)
            is WaveformState.StaticWaiting -> reconfigure(current, config)
            is WaveformState.StaticDecaying -> reconfigure(current, config)
            is WaveformState.PowerSavePaused ->
                Transition(current.copy(config = config), WaveformUpdate(needsRepaint = true))
            WaveformState.Failed -> failedTerminal()
        }

    private fun keystroke(event: WaveformEvent.Keystroke): Transition =
        when (val current = state) {
            is WaveformState.Inactive -> ignore(current)
            is WaveformState.MonitorWaiting -> {
                val beat = ScheduledBeat(event.nowMs, BeatMorphology.random(random))
                val next = WaveformState.MonitorRunning(current.config, listOf(beat), event.nowMs)
                Transition(next, WaveformUpdate(TimerDirective.START, needsRepaint = true))
            }
            is WaveformState.MonitorRunning -> scheduleMonitorBeat(current, event.nowMs)
            is WaveformState.StaticWaiting -> {
                val next = WaveformState.StaticDecaying(current.config, STATIC_BOOST_INCREMENT, event.nowMs)
                Transition(next, WaveformUpdate(TimerDirective.START, needsRepaint = true))
            }
            is WaveformState.StaticDecaying -> pumpStatic(current, event.nowMs)
            is WaveformState.PowerSavePaused -> ignore(current)
            WaveformState.Failed -> failedTerminal()
        }

    private fun tick(event: WaveformEvent.Tick): Transition =
        when (val current = state) {
            is WaveformState.Inactive -> ignore(current)
            is WaveformState.MonitorWaiting -> ignore(current)
            is WaveformState.MonitorRunning -> tickMonitor(current, event)
            is WaveformState.StaticWaiting -> ignore(current)
            is WaveformState.StaticDecaying -> tickStatic(current, event)
            is WaveformState.PowerSavePaused -> ignore(current)
            WaveformState.Failed -> failedTerminal()
        }

    private fun changePowerSave(enabled: Boolean): Transition =
        when (val current = state) {
            is WaveformState.Inactive -> ignore(current)
            is WaveformState.MonitorWaiting -> pauseIfEnabled(current.config, enabled, current)
            is WaveformState.MonitorRunning -> pauseIfEnabled(current.config, enabled, current)
            is WaveformState.StaticWaiting -> pauseIfEnabled(current.config, enabled, current)
            is WaveformState.StaticDecaying -> pauseIfEnabled(current.config, enabled, current)
            is WaveformState.PowerSavePaused -> {
                if (enabled) {
                    ignore(current)
                } else {
                    Transition(
                        waitingState(current.config),
                        WaveformUpdate(TimerDirective.STOP, needsRepaint = true),
                    )
                }
            }
            WaveformState.Failed -> failedTerminal()
        }

    private fun fail(): Transition =
        when (state) {
            is WaveformState.Inactive,
            is WaveformState.MonitorWaiting,
            is WaveformState.MonitorRunning,
            is WaveformState.StaticWaiting,
            is WaveformState.StaticDecaying,
            is WaveformState.PowerSavePaused,
            ->
                Transition(
                    WaveformState.Failed,
                    WaveformUpdate(
                        timerDirective = TimerDirective.STOP,
                        needsRepaint = true,
                        fallbackToSolid = true,
                    ),
                )
            WaveformState.Failed -> failedTerminal()
        }

    private fun scheduleMonitorBeat(
        current: WaveformState.MonitorRunning,
        nowMs: Long,
    ): Transition {
        if (current.beats.size >= MAX_QUEUED_BEATS) {
            return Transition(
                current.copy(lastInputMs = nowMs),
                WaveformUpdate(needsRepaint = true),
            )
        }
        val lastStartMs = current.beats.lastOrNull()?.startMs
        val startMs = lastStartMs?.let { maxOf(nowMs, it + MIN_RR_MS) } ?: nowMs
        val beat = ScheduledBeat(startMs, BeatMorphology.random(random))
        val beats = current.beats + beat
        val next = current.copy(beats = beats, lastInputMs = nowMs)
        return Transition(next, WaveformUpdate(needsRepaint = true))
    }

    private fun pumpStatic(
        current: WaveformState.StaticDecaying,
        nowMs: Long,
    ): Transition {
        val boost = (current.boost + STATIC_BOOST_INCREMENT).coerceAtMost(1f)
        val next = current.copy(boost = boost, lastTickMs = nowMs)
        return Transition(next, WaveformUpdate(needsRepaint = true))
    }

    private fun tickStatic(
        current: WaveformState.StaticDecaying,
        event: WaveformEvent.Tick,
    ): Transition {
        val elapsedMs = (event.nowMs - current.lastTickMs).coerceAtLeast(0L)
        val boost = (current.boost - elapsedMs / STATIC_ENVELOPE_MS).coerceAtLeast(0f)
        val frame =
            WaveformFrame(
                nowMs = event.nowMs,
                config = current.config,
                staticBoost = boost,
                brightness = STATIC_BASE_BRIGHTNESS + boost * STATIC_BRIGHTNESS_RANGE,
            )
        if (boost == 0f) {
            return Transition(
                WaveformState.StaticWaiting(current.config),
                WaveformUpdate(TimerDirective.STOP, needsRepaint = true, frame = frame),
            )
        }
        return Transition(
            current.copy(boost = boost, lastTickMs = event.nowMs),
            WaveformUpdate(needsRepaint = true, frame = frame),
        )
    }

    private fun tickMonitor(
        current: WaveformState.MonitorRunning,
        event: WaveformEvent.Tick,
    ): Transition {
        if (event.trackLength <= 0f) {
            return Transition(
                WaveformState.MonitorWaiting(current.config),
                WaveformUpdate(TimerDirective.STOP, needsRepaint = true),
            )
        }

        val alive =
            current.beats.filter { beat ->
                beat.startMs > event.nowMs ||
                    traveledDistance(event.nowMs, beat.startMs) < event.trackLength
            }
        val frameBeats =
            alive.mapNotNull { beat ->
                if (beat.startMs > event.nowMs) return@mapNotNull null
                val traveled = traveledDistance(event.nowMs, beat.startMs)
                FrameBeat(
                    centerDistance = wrap(traveled * current.config.direction.travelSign, event.trackLength),
                    morphology = beat.morphology,
                    opacity = ((1f - traveled / event.trackLength) * FADE_MULTIPLIER).coerceIn(0f, 1f),
                )
            }
        val frame = WaveformFrame(event.nowMs, current.config, beats = frameBeats)
        if (event.nowMs - current.lastInputMs >= IDLE_TIMEOUT_MS) {
            val waiting = WaveformState.MonitorWaiting(current.config)
            return Transition(
                waiting,
                WaveformUpdate(
                    TimerDirective.STOP,
                    needsRepaint = true,
                    frame = frame.copy(beats = emptyList()),
                ),
            )
        }
        return Transition(
            current.copy(beats = alive),
            WaveformUpdate(needsRepaint = current.beats.isNotEmpty(), frame = frame),
        )
    }

    private fun traveledDistance(
        nowMs: Long,
        startMs: Long,
    ): Float = (nowMs - startMs) / MILLIS_PER_SECOND * BEAT_SPEED

    private fun wrap(
        distance: Float,
        length: Float,
    ): Float = ((distance % length) + length) % length

    private fun pauseIfEnabled(
        config: WaveformConfig,
        enabled: Boolean,
        current: WaveformState,
    ): Transition =
        if (enabled) {
            Transition(
                WaveformState.PowerSavePaused(config),
                WaveformUpdate(TimerDirective.STOP, needsRepaint = true),
            )
        } else {
            ignore(current)
        }

    private fun waitingState(config: WaveformConfig): WaveformState =
        when (config.motion) {
            WaveformMotion.MONITOR -> WaveformState.MonitorWaiting(config)
            WaveformMotion.STATIC_PULSE -> WaveformState.StaticWaiting(config)
        }

    private fun inactive(config: WaveformConfig): Transition =
        Transition(
            WaveformState.Inactive(config),
            WaveformUpdate(TimerDirective.STOP, needsRepaint = true),
        )

    private fun reconfigure(
        current: WaveformState,
        config: WaveformConfig,
    ): Transition =
        if (config == current.config) {
            ignore(current)
        } else {
            Transition(
                waitingState(config),
                WaveformUpdate(TimerDirective.STOP, needsRepaint = true),
            )
        }

    private fun failedTerminal(): Transition =
        Transition(
            WaveformState.Failed,
            WaveformUpdate(fallbackToSolid = true),
        )

    private fun ignore(current: WaveformState): Transition = Transition(current, WaveformUpdate())

    private data class Transition(
        val state: WaveformState,
        val update: WaveformUpdate,
    )

    private companion object {
        const val MIN_RR_MS = 190L
        const val MAX_QUEUED_BEATS = 24
        const val IDLE_TIMEOUT_MS = 4_000L
        const val MILLIS_PER_SECOND = 1_000f
        const val BEAT_SPEED = 170f
        const val FADE_MULTIPLIER = 4f
        const val STATIC_BOOST_INCREMENT = 0.6f
        const val STATIC_ENVELOPE_MS = 1_500f
        const val STATIC_BRIGHTNESS_RANGE = 0.45f
    }
}
