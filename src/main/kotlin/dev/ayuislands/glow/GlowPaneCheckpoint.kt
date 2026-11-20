package dev.ayuislands.glow

import kotlinx.coroutines.CancellationException
import java.util.Collections
import javax.swing.SwingUtilities

/**
 * Owns selected settings of one existing pane. The caller owns playback, attachment, and bounds
 * for the whole operation and must reconcile them after the outer Apply settles.
 */
internal class GlowPaneCheckpoint(
    private val pane: GlowGlassPane,
    val planned: GlowPaneAppearance,
    selected: Set<GlowPaneField> = GlowPaneField.entries.toSet(),
    private val isCurrent: () -> Boolean,
) {
    init {
        check(SwingUtilities.isEventDispatchThread()) { "Glow checkpoints must run on EDT" }
        check(isCurrent()) { "Glow pane is unavailable" }
    }

    val before: GlowPaneAppearance = pane.captureAppearance()
    val record: GlowPaneRecord
        get() = GlowPaneRecord(before, planned, phase, Collections.unmodifiableMap(LinkedHashMap(fields)))
    private var phase = GlowCheckpointPhase.PREPARED
    private val fields =
        GlowPaneField.entries
            .filter { it in selected && !GlowPaneFields.matches(it, before, planned) }
            .associateWith { GlowFieldState.PREPARED }
            .toMutableMap()

    val hasPending: Boolean
        get() = phase != GlowCheckpointPhase.ACCEPTED && GlowFieldState.WRITING in fields.values

    fun apply() {
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
            write(field, planned)
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

    fun restore(blockedBy: Set<GlowPaneField> = emptySet()) {
        checkEdt()
        check(phase != GlowCheckpointPhase.ACCEPTED) { "Accepted Glow pane changes cannot be restored" }
        if (phase == GlowCheckpointPhase.RESTORED) return
        phase = GlowCheckpointPhase.RESTORING
        val failures = mutableListOf<RuntimeException>()
        for (field in fields.keys.toList().asReversed()) {
            try {
                restoreField(field, blockedBy)
            } catch (cancelled: CancellationException) {
                throw cancelled
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
    ) {
        when (fields.getValue(field)) {
            GlowFieldState.PREPARED -> {
                fields[field] = GlowFieldState.RESTORED
            }

            GlowFieldState.RESTORED, GlowFieldState.FOREIGN -> {
                return
            }

            GlowFieldState.WRITING -> {
                check(field !in blockedBy) { "A newer Glow pane receipt owns $field" }
                checkCurrent()
                val current = pane.captureAppearance()
                when {
                    GlowPaneFields.matches(field, current, before) -> {
                        fields[field] = GlowFieldState.RESTORED
                    }

                    !GlowPaneFields.matches(field, current, planned) -> {
                        fields[field] = GlowFieldState.FOREIGN
                    }

                    else -> {
                        write(field, before)
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
    ) {
        val merged = GlowPaneFields.replace(field, pane.captureAppearance(), values)
        when (field) {
            GlowPaneField.COLOR -> {
                pane.glowColor = merged.color
            }

            GlowPaneField.STYLE -> {
                pane.glowStyle = merged.style
            }

            GlowPaneField.INTENSITY -> {
                pane.glowIntensity = merged.intensity
            }

            GlowPaneField.WIDTH -> {
                pane.glowWidth = merged.width
            }

            GlowPaneField.PLACEMENT -> {
                pane.glowPlacement = merged.placement
            }

            GlowPaneField.VISIBILITY -> {
                pane.isVisible = merged.isVisible
            }

            else -> {
                pane.configureWaveform(
                    merged.shape,
                    merged.waveform,
                    preserveFailure = true,
                )
            }
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
