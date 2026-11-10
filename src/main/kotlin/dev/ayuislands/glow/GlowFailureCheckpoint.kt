package dev.ayuislands.glow

import dev.ayuislands.glow.waveform.WaveformEngine
import javax.swing.SwingUtilities

/** Retains failure ownership separately from appearance fields and transient animation progress. */
internal class GlowFailureCheckpoint(
    private val pane: GlowGlassPane,
    private val isCurrent: () -> Boolean,
) {
    init {
        checkCurrent()
    }

    private val before = pane.waveformFailure
    private val appearance = pane.captureAppearance()
    private val engines = mutableListOf<WaveformEngine>()
    private val routes = mutableListOf<RoutePlayback>()
    private var phase = GlowCheckpointPhase.PREPARED
    private var state = GlowFieldState.PREPARED
    private var cleared: GlowWaveformFailure? = null
    private var restoration: GlowFailureRestore? = null

    val record: GlowFailureRecord
        get() = GlowFailureRecord(before, phase, state, cleared, restoration)

    val hasPending: Boolean
        get() = phase != GlowCheckpointPhase.ACCEPTED && state == GlowFieldState.WRITING

    /** Called explicitly before the pane publishes a new engine, never as an ambient observer. */
    fun recordEngine(engine: WaveformEngine) {
        checkRecording()
        engines.add(engine)
        if (pane.waveformFailure === before) cleared = before
        phase = GlowCheckpointPhase.APPLYING
        state = GlowFieldState.WRITING
    }

    /** The route request exists before configuration and identifies any subsequent route failure. */
    fun recordRoute(playback: RoutePlayback) {
        checkRecording()
        check(playback.targets.any { it.pane === pane }) { "Glow route does not own this pane" }
        routes.add(playback)
        phase = GlowCheckpointPhase.APPLYING
        state = GlowFieldState.WRITING
    }

    fun prepareRestore() {
        checkRestoring()
        if (phase == GlowCheckpointPhase.RESTORED) return
        checkCurrent()
        val current = pane.waveformFailure
        val outcome =
            if (current === before || owns(current)) GlowFieldState.RESTORED else GlowFieldState.FOREIGN
        restoration = GlowFailureRestore(current, outcome)
    }

    fun restore(isBlocked: Boolean = false) {
        checkRestoring()
        if (phase == GlowCheckpointPhase.RESTORED) return
        check(!isBlocked) { "A newer Glow receipt owns waveform health" }
        checkCurrent()
        val prepared = checkNotNull(restoration) { "Glow failure restoration has not been prepared" }
        phase = GlowCheckpointPhase.RESTORING
        val current = pane.waveformFailure
        when {
            prepared.outcome == GlowFieldState.FOREIGN -> state = GlowFieldState.FOREIGN
            current === before -> state = GlowFieldState.RESTORED
            current !== prepared.observed -> state = GlowFieldState.FOREIGN
            else -> {
                val actual = pane.captureAppearance()
                if (actual.shape != appearance.shape || actual.waveform != appearance.waveform) {
                    state = GlowFieldState.FOREIGN
                } else {
                    pane.restoreWaveformFailure(current, before)
                    checkCurrent()
                    state = if (pane.waveformFailure === before) GlowFieldState.RESTORED else GlowFieldState.FOREIGN
                }
            }
        }
        phase = GlowCheckpointPhase.RESTORED
    }

    fun accept() {
        checkEdt()
        check(phase != GlowCheckpointPhase.RESTORING && phase != GlowCheckpointPhase.RESTORED) {
            "Restoring Glow failures cannot be accepted"
        }
        phase = GlowCheckpointPhase.ACCEPTED
    }

    private fun owns(failure: GlowWaveformFailure?): Boolean =
        when (val origin = failure?.origin) {
            null -> before != null && cleared === before
            is GlowFailureOrigin.Local -> engines.any { it === origin.engine }
            is GlowFailureOrigin.Route -> routes.any { it === origin.playback }
        }

    private fun checkRecording() {
        checkCurrent()
        check(phase == GlowCheckpointPhase.PREPARED || phase == GlowCheckpointPhase.APPLYING) {
            "Glow failure ownership can no longer be recorded"
        }
        val current = pane.waveformFailure
        check(current === before || owns(current)) { "A foreign Glow failure cannot be cleared" }
    }

    private fun checkRestoring() {
        checkEdt()
        check(phase != GlowCheckpointPhase.ACCEPTED) { "Accepted Glow failures cannot be restored" }
    }

    private fun checkCurrent() {
        checkEdt()
        check(isCurrent()) { "Glow pane is unavailable" }
    }

    private fun checkEdt() {
        check(SwingUtilities.isEventDispatchThread()) { "Glow failure checkpoints must run on EDT" }
    }
}
