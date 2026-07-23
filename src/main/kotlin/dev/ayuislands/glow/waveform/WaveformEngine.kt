package dev.ayuislands.glow.waveform

import com.intellij.util.concurrency.annotations.RequiresEdt
import kotlin.math.floor
import kotlin.math.min
import kotlin.random.Random

private const val INITIAL_TRACE_PHASE = 0.055f

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

internal data class FrameTrace(
    /** Signed perimeter offset from the track's signal anchor. */
    val anchorOffset: Float,
    /** Current morphology followed by older complexes that still occupy the fading trace. */
    val history: List<BeatMorphology>,
    val phase: Float = INITIAL_TRACE_PHASE,
)

internal data class WaveformFrame(
    val config: WaveformConfig,
    val direction: TravelDirection,
    val trace: FrameTrace? = null,
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
        val direction: TravelDirection,
        val travelPhase: Float = 0f,
        val tracePhase: Float = INITIAL_TRACE_PHASE,
        val lastTickMs: Long? = null,
        val history: List<BeatMorphology>,
        val energyEnvelope: EnergyEnvelope? = null,
        val cadence: TypingCadence = TypingCadence(),
        val traceRate: Float = 1f,
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
 * Inactive -> Looping | Suspended on Activate.
 * Looping remains active across Tick and Keystroke. It enters Suspended when
 * animation becomes unavailable and resumes from its anchor when animation returns.
 * Every non-failed state -> Inactive on Deactivate and -> Failed on
 * RenderFailed. Failed is terminal. Other cells are explicit ignores.
 */
internal class WaveformEngine(
    initialConfig: WaveformConfig,
    private val random: Random = Random.Default,
    private val chaoticDirection: TravelDirection? = null,
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
            is WaveformState.Suspended -> ignore(current)
            WaveformState.Failed -> failedTerminal()
        }

    private fun changePowerSave(enabled: Boolean): Transition =
        when (val current = state) {
            is WaveformState.Inactive -> ignore(current)
            is WaveformState.Looping -> if (enabled) suspended(current.config) else ignore(current)
            is WaveformState.Suspended -> if (enabled) ignore(current) else active(current.config)
            WaveformState.Failed -> failedTerminal()
        }

    private fun configureLooping(
        current: WaveformState.Looping,
        config: WaveformConfig,
    ): Transition =
        if (config == current.config) {
            ignore(current)
        } else {
            val currentFixedDirection = current.config.movement.fixedDirection
            val configuredFixedDirection = config.movement.fixedDirection
            val direction =
                if (
                    currentFixedDirection != null &&
                    configuredFixedDirection != null &&
                    currentFixedDirection != configuredFixedDirection
                ) {
                    configuredFixedDirection
                } else {
                    current.direction
                }
            Transition(
                current.copy(
                    config = config,
                    direction = direction,
                    history = fitHistory(current.history, config.traceComplexCount),
                ),
                WaveformUpdate(needsRepaint = true),
            )
        }

    private fun tickLooping(
        current: WaveformState.Looping,
        event: WaveformEvent.Tick,
    ): Transition {
        val elapsedMs = current.lastTickMs?.let { (event.nowMs - it).coerceAtLeast(0L) } ?: 0L
        val traceRate = slewedTraceRate(current, event.nowMs, elapsedMs)
        val travelPhase = wrap(current.travelPhase + elapsedMs / loopDurationMs(current.config), 1f)
        val trace = advanceTrace(current, elapsedMs, traceRate)
        val energy = current.energyEnvelope?.levelAt(event.nowMs) ?: 0f
        val envelope = current.energyEnvelope?.takeIf { event.nowMs < it.endMs }
        val frameTrace = movingTrace(event.trackLength, current.direction, travelPhase, trace)
        val frame = activeFrame(current.config, current.direction, energy, trace.history.first(), frameTrace)
        return Transition(
            current.copy(
                travelPhase = travelPhase,
                tracePhase = trace.phase,
                lastTickMs = event.nowMs,
                history = trace.history,
                energyEnvelope = envelope,
                traceRate = traceRate,
            ),
            WaveformUpdate(needsRepaint = event.trackLength > 0f, frame = frame),
        )
    }

    private fun slewedTraceRate(
        current: WaveformState.Looping,
        nowMs: Long,
        elapsedMs: Long,
    ): Float {
        val target = current.cadence.targetRate(nowMs)
        val maxDelta = TRACE_SLEW_RATE * elapsedMs / MILLIS_PER_SECOND
        return current.traceRate + (target - current.traceRate).coerceIn(-maxDelta, maxDelta)
    }

    private fun advanceTrace(
        current: WaveformState.Looping,
        elapsedMs: Long,
        traceRate: Float,
    ): TraceAdvance {
        val unwrappedPhase = current.tracePhase + elapsedMs * traceRate / TRACE_PERIOD_MS
        val completedCycles = floor(unwrappedPhase).toInt().coerceAtLeast(0)
        if (completedCycles == 0) return TraceAdvance(unwrappedPhase, current.history)

        val complexCount = current.config.traceComplexCount
        val generated = List(min(completedCycles, complexCount)) { BeatMorphology.random(random) }
        val history = (generated.asReversed() + current.history).take(complexCount)
        return TraceAdvance(wrap(unwrappedPhase, 1f), history)
    }

    private data class TraceAdvance(
        val phase: Float,
        val history: List<BeatMorphology>,
    )

    private fun active(config: WaveformConfig): Transition = looping(config, BeatMorphology.random(random))

    private fun looping(
        config: WaveformConfig,
        morphology: BeatMorphology,
    ): Transition {
        val state = loopingState(config, morphology)
        return Transition(
            state,
            WaveformUpdate(
                TimerDirective.START,
                needsRepaint = true,
                frame = restingFrame(config, state.direction, morphology),
            ),
        )
    }

    private fun loopingState(
        config: WaveformConfig,
        morphology: BeatMorphology,
        energyEnvelope: EnergyEnvelope? = null,
    ): WaveformState.Looping =
        WaveformState.Looping(
            config = config,
            direction = directionFor(config),
            history = initialHistory(config, morphology),
            energyEnvelope = energyEnvelope,
        )

    private fun directionFor(config: WaveformConfig): TravelDirection =
        config.movement.fixedDirection
            ?: chaoticDirection
            ?: if (random.nextBoolean()) {
                TravelDirection.CLOCKWISE
            } else {
                TravelDirection.COUNTER_CLOCKWISE
            }

    private fun initialHistory(
        config: WaveformConfig,
        morphology: BeatMorphology,
    ): List<BeatMorphology> = listOf(morphology) + List(config.traceComplexCount - 1) { BeatMorphology.random(random) }

    private fun fitHistory(
        history: List<BeatMorphology>,
        complexCount: Int,
    ): List<BeatMorphology> =
        if (history.size >= complexCount) {
            history.take(complexCount)
        } else {
            history + List(complexCount - history.size) { BeatMorphology.random(random) }
        }

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
        const val TRACE_PERIOD_MS = 1_200f
        const val TRACE_SLEW_RATE = 1.2f

        fun loopDurationMs(config: WaveformConfig): Float =
            config.loopSeconds.normalizedLoopSeconds() * MILLIS_PER_SECOND

        fun movingTrace(
            trackLength: Float,
            direction: TravelDirection,
            travelPhase: Float,
            trace: TraceAdvance,
        ): FrameTrace? {
            if (trackLength <= 0f) return null
            return FrameTrace(
                anchorOffset = wrap(travelPhase * direction.travelSign, 1f) * trackLength,
                phase = trace.phase,
                history = trace.history,
            )
        }

        fun activeFrame(
            config: WaveformConfig,
            direction: TravelDirection,
            energy: Float,
            morphology: BeatMorphology,
            trace: FrameTrace? = null,
        ): WaveformFrame =
            WaveformFrame(
                config = config,
                direction = direction,
                trace = trace,
                brightness = config.brightnessAt(energy),
                energy = energy,
                morphology = morphology,
            )

        fun restingFrame(
            config: WaveformConfig,
            direction: TravelDirection = config.movement.fixedDirection ?: TravelDirection.CLOCKWISE,
            morphology: BeatMorphology = BeatMorphology.standard(),
        ): WaveformFrame =
            WaveformFrame(
                config = config,
                direction = direction,
                brightness = config.brightnessAt(0f),
                morphology = morphology,
            )

        fun wrap(
            distance: Float,
            length: Float,
        ): Float = ((distance % length) + length) % length
    }
}
