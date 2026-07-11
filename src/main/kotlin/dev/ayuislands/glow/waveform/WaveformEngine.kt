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

internal data class FrameBeat(
    /** Signed perimeter offset from the track's signal anchor. */
    val centerDistance: Float,
    val morphology: BeatMorphology,
    /** 0..1 window-alpha multiplier easing beats in at spawn and out at expiry. */
    val fade: Float = 1f,
)

/** A beat that has been passed by a newer one and keeps running until it expires. */
internal data class TrailingBeat(
    val phase: Float,
    val morphology: BeatMorphology,
)

internal data class WaveformFrame(
    val config: WaveformConfig,
    val beats: List<FrameBeat> = emptyList(),
    val brightness: Float = 1f,
    val energy: Float = 0f,
    val morphology: BeatMorphology = BeatMorphology.standard(),
)

internal data class EnergyEnvelope(
    val startLevel: Float,
    val startMs: Long,
    val peakMs: Long,
    val endMs: Long,
) {
    fun levelAt(nowMs: Long): Float =
        when {
            nowMs <= startMs -> {
                startLevel
            }

            nowMs < peakMs -> {
                val progress = (nowMs - startMs).toFloat() / (peakMs - startMs).coerceAtLeast(1L)
                startLevel + (1f - startLevel) * progress
            }

            nowMs < endMs -> {
                1f - (nowMs - peakMs).toFloat() / (endMs - peakMs).coerceAtLeast(1L)
            }

            else -> {
                0f
            }
        }.coerceIn(0f, 1f)

    fun refreshed(nowMs: Long): EnergyEnvelope {
        val currentLevel = levelAt(nowMs)
        val nextPeakMs = if (nowMs < peakMs) peakMs else nowMs + ENERGY_RISE_MS
        return EnergyEnvelope(currentLevel, nowMs, nextPeakMs, nextPeakMs + ENERGY_DECAY_MS)
    }

    companion object {
        const val ENERGY_RISE_MS = 80L
        const val ENERGY_DECAY_MS = 600L

        fun start(nowMs: Long): EnergyEnvelope =
            EnergyEnvelope(
                startLevel = 0f,
                startMs = nowMs,
                peakMs = nowMs + ENERGY_RISE_MS,
                endMs = nowMs + ENERGY_RISE_MS + ENERGY_DECAY_MS,
            )
    }
}

internal sealed interface WaveformState {
    val config: WaveformConfig?

    data class Inactive(
        override val config: WaveformConfig,
    ) : WaveformState

    data class Looping(
        override val config: WaveformConfig,
        val phase: Float = 0f,
        val lastTickMs: Long? = null,
        val morphology: BeatMorphology = BeatMorphology.standard(),
        val energyEnvelope: EnergyEnvelope? = null,
        val cadence: TypingCadence = TypingCadence(),
        val speedMultiplier: Float = 1f,
        val trailingBeats: List<TrailingBeat> = emptyList(),
    ) : WaveformState

    data class StaticResting(
        override val config: WaveformConfig,
        val morphology: BeatMorphology = BeatMorphology.standard(),
    ) : WaveformState

    data class StaticResponding(
        override val config: WaveformConfig,
        val energyEnvelope: EnergyEnvelope,
        val morphology: BeatMorphology,
    ) : WaveformState

    data class Suspended(
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
 * Inactive -> Looping | StaticResting | Suspended on Activate.
 * Looping remains active across Tick and Keystroke; StaticResting enters
 * StaticResponding on Keystroke and returns after the energy envelope.
 * Every active state enters Suspended when motion becomes unavailable and
 * resumes the configured mode from its anchor when motion becomes available.
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
                is WaveformEvent.Keystroke -> keystroke(event.nowMs)
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
                if (event.powerSaveEnabled) {
                    suspended(current.config)
                } else {
                    active(current.config)
                }
            }

            is WaveformState.Looping,
            is WaveformState.StaticResting,
            is WaveformState.StaticResponding,
            is WaveformState.Suspended,
            -> {
                ignore(current)
            }

            WaveformState.Failed -> {
                failedTerminal()
            }
        }

    private fun deactivate(): Transition =
        when (val current = state) {
            is WaveformState.Inactive -> ignore(current)
            is WaveformState.Looping -> inactive(current.config)
            is WaveformState.StaticResting -> inactive(current.config)
            is WaveformState.StaticResponding -> inactive(current.config)
            is WaveformState.Suspended -> inactive(current.config)
            WaveformState.Failed -> failedTerminal()
        }

    private fun configure(config: WaveformConfig): Transition =
        when (val current = state) {
            is WaveformState.Inactive -> {
                if (config == current.config) ignore(current) else inactive(config)
            }

            is WaveformState.Looping -> {
                configureLooping(current, config)
            }

            is WaveformState.StaticResting -> {
                configureStaticResting(current, config)
            }

            is WaveformState.StaticResponding -> {
                configureStaticResponding(current, config)
            }

            is WaveformState.Suspended -> {
                if (config == current.config) ignore(current) else suspended(config)
            }

            WaveformState.Failed -> {
                failedTerminal()
            }
        }

    private fun keystroke(nowMs: Long): Transition =
        when (val current = state) {
            is WaveformState.Inactive -> {
                ignore(current)
            }

            is WaveformState.Looping -> {
                Transition(
                    current.copy(
                        energyEnvelope = current.energyEnvelope?.refreshed(nowMs) ?: EnergyEnvelope.start(nowMs),
                        cadence = current.cadence.keystroke(nowMs),
                    ),
                    WaveformUpdate(needsRepaint = true),
                )
            }

            is WaveformState.StaticResting -> {
                Transition(
                    WaveformState.StaticResponding(
                        config = current.config,
                        energyEnvelope = EnergyEnvelope.start(nowMs),
                        morphology = BeatMorphology.random(random),
                    ),
                    WaveformUpdate(TimerDirective.START, needsRepaint = true),
                )
            }

            is WaveformState.StaticResponding -> {
                Transition(
                    current.copy(energyEnvelope = current.energyEnvelope.refreshed(nowMs)),
                    WaveformUpdate(needsRepaint = true),
                )
            }

            is WaveformState.Suspended -> {
                ignore(current)
            }

            WaveformState.Failed -> {
                failedTerminal()
            }
        }

    private fun tick(event: WaveformEvent.Tick): Transition =
        when (val current = state) {
            is WaveformState.Inactive -> ignore(current)
            is WaveformState.Looping -> tickLooping(current, event)
            is WaveformState.StaticResting -> ignore(current)
            is WaveformState.StaticResponding -> tickStatic(current, event)
            is WaveformState.Suspended -> ignore(current)
            WaveformState.Failed -> failedTerminal()
        }

    private fun changePowerSave(enabled: Boolean): Transition =
        when (val current = state) {
            is WaveformState.Inactive -> ignore(current)
            is WaveformState.Looping -> if (enabled) suspended(current.config) else ignore(current)
            is WaveformState.StaticResting -> if (enabled) suspended(current.config) else ignore(current)
            is WaveformState.StaticResponding -> if (enabled) suspended(current.config) else ignore(current)
            is WaveformState.Suspended -> if (enabled) ignore(current) else active(current.config)
            WaveformState.Failed -> failedTerminal()
        }

    private fun configureLooping(
        current: WaveformState.Looping,
        config: WaveformConfig,
    ): Transition =
        when {
            config == current.config -> {
                ignore(current)
            }

            config.motion == WaveformMotion.MONITOR -> {
                Transition(current.copy(config = config), WaveformUpdate(needsRepaint = true))
            }

            else -> {
                Transition(
                    WaveformState.StaticResting(config, current.morphology),
                    WaveformUpdate(
                        TimerDirective.STOP,
                        needsRepaint = true,
                        frame = restingFrame(config, current.morphology),
                    ),
                )
            }
        }

    private fun configureStaticResting(
        current: WaveformState.StaticResting,
        config: WaveformConfig,
    ): Transition =
        when {
            config == current.config -> {
                ignore(current)
            }

            config.motion == WaveformMotion.STATIC_PULSE -> {
                Transition(current.copy(config = config), WaveformUpdate(needsRepaint = true))
            }

            else -> {
                looping(config, current.morphology)
            }
        }

    private fun configureStaticResponding(
        current: WaveformState.StaticResponding,
        config: WaveformConfig,
    ): Transition =
        when {
            config == current.config -> {
                ignore(current)
            }

            config.motion == WaveformMotion.STATIC_PULSE -> {
                Transition(current.copy(config = config), WaveformUpdate(needsRepaint = true))
            }

            else -> {
                Transition(
                    WaveformState.Looping(
                        config = config,
                        morphology = current.morphology,
                        energyEnvelope = current.energyEnvelope,
                    ),
                    WaveformUpdate(TimerDirective.START, needsRepaint = true),
                )
            }
        }

    private fun tickLooping(
        current: WaveformState.Looping,
        event: WaveformEvent.Tick,
    ): Transition {
        val elapsedMs = current.lastTickMs?.let { (event.nowMs - it).coerceAtLeast(0L) } ?: 0L
        val speed = slewedSpeed(current, event.nowMs, elapsedMs)
        val unwrappedPhase = current.phase + elapsedMs * speed / loopDurationMs(current.config)
        val advance = advanceBeats(current, unwrappedPhase, speed)
        val energy = current.energyEnvelope?.levelAt(event.nowMs) ?: 0f
        val envelope = current.energyEnvelope?.takeIf { event.nowMs < it.endMs }
        val beats = movingBeats(event.trackLength, current.config.direction, beatSpacing(speed), advance)
        val frame = activeFrame(current.config, energy, advance.morphology, beats)
        return Transition(
            current.copy(
                phase = advance.phase,
                lastTickMs = event.nowMs,
                morphology = advance.morphology,
                energyEnvelope = envelope,
                speedMultiplier = speed,
                trailingBeats = advance.trailingBeats,
            ),
            WaveformUpdate(needsRepaint = event.trackLength > 0f, frame = frame),
        )
    }

    private fun slewedSpeed(
        current: WaveformState.Looping,
        nowMs: Long,
        elapsedMs: Long,
    ): Float {
        val target = current.cadence.targetSpeed(nowMs)
        val maxDelta = SPEED_SLEW_PER_SECOND * elapsedMs / MILLIS_PER_SECOND
        return current.speedMultiplier + (target - current.speedMultiplier).coerceIn(-maxDelta, maxDelta)
    }

    private fun advanceBeats(
        current: WaveformState.Looping,
        unwrappedPhase: Float,
        speed: Float,
    ): BeatAdvance {
        val travelled = unwrappedPhase - current.phase
        val trailing =
            current.trailingBeats
                .map { beat -> beat.copy(phase = beat.phase + travelled) }
                .toMutableList()
        var leadPhase = unwrappedPhase
        var morphology = current.morphology
        val spacing = beatSpacing(speed)
        if (spacing >= 1f) {
            if (unwrappedPhase >= 1f) morphology = BeatMorphology.random(random)
            leadPhase = wrap(unwrappedPhase, 1f)
        } else {
            while (leadPhase >= spacing) {
                trailing += TrailingBeat(leadPhase, morphology)
                leadPhase -= spacing
                morphology = BeatMorphology.random(random)
            }
        }
        return BeatAdvance(
            phase = leadPhase,
            morphology = morphology,
            trailingBeats = trailing.filter { it.phase < TRAIL_EXPIRE_PHASE }.takeLast(MAX_TRAILING_BEATS),
        )
    }

    private data class BeatAdvance(
        val phase: Float,
        val morphology: BeatMorphology,
        val trailingBeats: List<TrailingBeat>,
    )

    private fun tickStatic(
        current: WaveformState.StaticResponding,
        event: WaveformEvent.Tick,
    ): Transition {
        val energy = current.energyEnvelope.levelAt(event.nowMs)
        val frame = activeFrame(current.config, energy, current.morphology)
        if (event.nowMs >= current.energyEnvelope.endMs) {
            return Transition(
                WaveformState.StaticResting(current.config, current.morphology),
                WaveformUpdate(TimerDirective.STOP, needsRepaint = true, frame = frame),
            )
        }
        return Transition(
            current,
            WaveformUpdate(needsRepaint = event.trackLength > 0f, frame = frame),
        )
    }

    private fun active(config: WaveformConfig): Transition =
        when (config.motion) {
            WaveformMotion.MONITOR -> {
                looping(config, BeatMorphology.random(random))
            }

            WaveformMotion.STATIC_PULSE -> {
                val morphology = BeatMorphology.random(random)
                Transition(
                    WaveformState.StaticResting(config, morphology),
                    WaveformUpdate(TimerDirective.STOP, needsRepaint = true, frame = restingFrame(config, morphology)),
                )
            }
        }

    private fun looping(
        config: WaveformConfig,
        morphology: BeatMorphology,
    ): Transition =
        Transition(
            WaveformState.Looping(config = config, morphology = morphology),
            WaveformUpdate(TimerDirective.START, needsRepaint = true, frame = restingFrame(config, morphology)),
        )

    private fun suspended(config: WaveformConfig): Transition =
        Transition(
            WaveformState.Suspended(config),
            WaveformUpdate(TimerDirective.STOP, needsRepaint = true, frame = restingFrame(config)),
        )

    private fun inactive(config: WaveformConfig): Transition =
        Transition(
            WaveformState.Inactive(config),
            WaveformUpdate(TimerDirective.STOP, needsRepaint = true, frame = restingFrame(config)),
        )

    private fun fail(): Transition =
        when (state) {
            is WaveformState.Inactive,
            is WaveformState.Looping,
            is WaveformState.StaticResting,
            is WaveformState.StaticResponding,
            is WaveformState.Suspended,
            -> {
                Transition(
                    WaveformState.Failed,
                    WaveformUpdate(
                        timerDirective = TimerDirective.STOP,
                        needsRepaint = true,
                        fallbackToSolid = true,
                    ),
                )
            }

            WaveformState.Failed -> {
                failedTerminal()
            }
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
        const val MILLIS_PER_SECOND = 1_000f
        const val ACTIVE_BRIGHTNESS_RANGE = 1f - IDLE_WAVEFORM_BRIGHTNESS
        const val SPEED_SLEW_PER_SECOND = 1.2f
        const val BEAT_DENSITY_GAIN = 1f
        const val TRAIL_EXPIRE_PHASE = 1f
        const val TRAIL_FADE_OUT_PHASE = 0.12f
        const val LEAD_IGNITE_PHASE = 0.05f
        const val MAX_TRAILING_BEATS = 4

        fun loopDurationMs(config: WaveformConfig): Float =
            config.loopSeconds.normalizedLoopSeconds() * MILLIS_PER_SECOND

        fun beatSpacing(speed: Float): Float = 1f / (1f + (speed - 1f) * BEAT_DENSITY_GAIN)

        fun movingBeats(
            trackLength: Float,
            direction: WaveformDirection,
            spacing: Float,
            advance: BeatAdvance,
        ): List<FrameBeat> {
            if (trackLength <= 0f) return emptyList()
            val lead =
                FrameBeat(
                    centerDistance = wrap(advance.phase * direction.travelSign, 1f) * trackLength,
                    morphology = advance.morphology,
                    fade = if (spacing < 1f) smoothStep(advance.phase / LEAD_IGNITE_PHASE) else 1f,
                )
            val trailing =
                advance.trailingBeats.map { beat ->
                    FrameBeat(
                        centerDistance = wrap(beat.phase * direction.travelSign, 1f) * trackLength,
                        morphology = beat.morphology,
                        fade = smoothStep((TRAIL_EXPIRE_PHASE - beat.phase) / TRAIL_FADE_OUT_PHASE),
                    )
                }
            return listOf(lead) + trailing
        }

        fun activeFrame(
            config: WaveformConfig,
            energy: Float,
            morphology: BeatMorphology,
            beats: List<FrameBeat> = emptyList(),
        ): WaveformFrame =
            WaveformFrame(
                config = config,
                beats = beats,
                brightness = IDLE_WAVEFORM_BRIGHTNESS + energy * ACTIVE_BRIGHTNESS_RANGE,
                energy = energy,
                morphology = morphology,
            )

        fun restingFrame(
            config: WaveformConfig,
            morphology: BeatMorphology = BeatMorphology.standard(),
        ): WaveformFrame =
            WaveformFrame(
                config = config,
                brightness = IDLE_WAVEFORM_BRIGHTNESS,
                morphology = morphology,
            )

        fun wrap(
            distance: Float,
            length: Float,
        ): Float = ((distance % length) + length) % length
    }
}
