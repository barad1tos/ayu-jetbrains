package dev.ayuislands.glow

import dev.ayuislands.glow.waveform.WaveformEngine
import javax.swing.SwingUtilities

/** Owns one admitted pane change until the outer operation accepts or restores it. */
internal class GlowPaneCheckpoint(
    private val pane: GlowGlassPane,
    val planned: GlowPaneAppearance,
    private val isCurrent: () -> Boolean,
) {
    init {
        check(SwingUtilities.isEventDispatchThread()) { "Glow checkpoints must run on EDT" }
        check(isCurrent()) { "Glow pane is unavailable" }
    }

    val before: GlowPaneAppearance = pane.captureAppearance()
    val record: GlowPaneRecord
        get() = GlowPaneRecord(before, planned, phase, java.util.Map.copyOf(fields))
    private var phase = GlowCheckpointPhase.PREPARED
    private val fields =
        GlowPaneField.entries
            .filterNot { GlowPaneFields.matches(it, before, planned) }
            .associateWith { GlowFieldState.PREPARED }
            .toMutableMap()

    val hasPending: Boolean
        get() = phase != GlowCheckpointPhase.ACCEPTED && GlowFieldState.WRITING in fields.values

    fun apply(onEngineCreated: (WaveformEngine) -> Unit = {}) {
        checkEdt()
        check(phase == GlowCheckpointPhase.PREPARED) { "Glow pane change was already started" }
        phase = GlowCheckpointPhase.APPLYING
        for (field in fields.keys) {
            checkCurrent()
            if (!GlowPaneFields.matches(field, pane.captureAppearance(), before)) {
                fields[field] = GlowFieldState.FOREIGN
                error("Glow pane changed before apply: $field")
            }
            fields[field] = GlowFieldState.WRITING
            write(field, planned, onEngineCreated)
            checkCurrent()
            check(
                GlowPaneFields.matches(field, pane.captureAppearance(), planned),
            ) { "Glow pane write did not settle: $field" }
        }
        checkCurrent()
        val applied = pane.captureAppearance()
        check(fields.keys.all { GlowPaneFields.matches(it, applied, planned) }) {
            "Glow pane changed during apply"
        }
        phase = GlowCheckpointPhase.APPLIED
    }

    fun restore(
        blockedBy: Set<GlowPaneField> = emptySet(),
        onEngineCreated: (WaveformEngine) -> Unit = {},
    ) {
        checkEdt()
        check(phase != GlowCheckpointPhase.ACCEPTED) { "Accepted Glow pane changes cannot be restored" }
        if (phase == GlowCheckpointPhase.RESTORED) return
        phase = GlowCheckpointPhase.RESTORING
        val failures = mutableListOf<RuntimeException>()
        for (field in fields.keys.toList().asReversed()) {
            try {
                restoreField(field, blockedBy, onEngineCreated)
            } catch (exception: RuntimeException) {
                failures.add(exception)
            }
        }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).filterNot { it === first }.forEach(first::addSuppressed)
            throw first
        }
        phase = GlowCheckpointPhase.RESTORED
    }

    fun accept() {
        checkEdt()
        check(phase == GlowCheckpointPhase.APPLIED || phase == GlowCheckpointPhase.ACCEPTED) {
            "Glow pane apply has not completed"
        }
        phase = GlowCheckpointPhase.ACCEPTED
    }

    private fun restoreField(
        field: GlowPaneField,
        blockedBy: Set<GlowPaneField>,
        onEngineCreated: (WaveformEngine) -> Unit,
    ) {
        when (fields.getValue(field)) {
            GlowFieldState.PREPARED -> fields[field] = GlowFieldState.RESTORED
            GlowFieldState.RESTORED, GlowFieldState.FOREIGN -> Unit
            GlowFieldState.WRITING -> {
                check(field !in blockedBy) { "A newer Glow pane receipt owns $field" }
                checkCurrent()
                val current = pane.captureAppearance()
                when {
                    GlowPaneFields.matches(field, current, before) -> fields[field] = GlowFieldState.RESTORED
                    !GlowPaneFields.matches(field, current, planned) -> fields[field] = GlowFieldState.FOREIGN
                    else -> {
                        write(field, before, onEngineCreated)
                        checkCurrent()
                        val restored = pane.captureAppearance()
                        fields[field] =
                            when {
                                GlowPaneFields.matches(field, restored, before) -> GlowFieldState.RESTORED
                                !GlowPaneFields.matches(field, restored, planned) -> GlowFieldState.FOREIGN
                                else -> error("Glow pane restoration did not settle: $field")
                            }
                    }
                }
            }
        }
    }

    private fun write(
        field: GlowPaneField,
        values: GlowPaneAppearance,
        onEngineCreated: (WaveformEngine) -> Unit,
    ) {
        val merged = GlowPaneFields.replace(field, pane.captureAppearance(), values)
        when (field) {
            GlowPaneField.COLOR -> pane.glowColor = merged.color
            GlowPaneField.STYLE -> pane.glowStyle = merged.style
            GlowPaneField.INTENSITY -> pane.glowIntensity = merged.intensity
            GlowPaneField.WIDTH -> pane.glowWidth = merged.width
            GlowPaneField.PLACEMENT -> pane.glowPlacement = merged.placement
            GlowPaneField.VISIBILITY -> pane.isVisible = merged.isVisible
            else ->
                pane.configureWaveform(
                    merged.shape,
                    merged.waveform,
                    preserveFailure = phase == GlowCheckpointPhase.RESTORING,
                    onEngineCreated = onEngineCreated,
                )
        }
        pane.repaint()
    }

    private fun checkCurrent() {
        check(isCurrent()) { "Glow pane is unavailable" }
    }

    private fun checkEdt() {
        check(SwingUtilities.isEventDispatchThread()) { "Glow checkpoints must run on EDT" }
    }
}
