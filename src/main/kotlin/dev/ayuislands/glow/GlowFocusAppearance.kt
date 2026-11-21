package dev.ayuislands.glow

import java.awt.Color
import javax.swing.border.Border

internal enum class GlowFocusField { APPEARANCE, BORDER }

internal data class GlowFocusAppearance(
    val color: Color,
    val style: GlowStyle,
    val intensity: Int,
    val enabled: Boolean = true,
) {
    fun matches(other: GlowFocusAppearance): Boolean =
        sameGlowColor(color, other.color) &&
            style == other.style &&
            intensity == other.intensity &&
            enabled == other.enabled
}

/** Retained native references are evidence for this listener, not serialized substitutes. */
internal data class GlowFocusRecord(
    val before: GlowFocusAppearance,
    val planned: GlowFocusAppearance,
    val beforeBorder: Border?,
    val plannedBorder: Border?,
    val baselineBorder: Border?,
    val phase: GlowCheckpointPhase,
    val fields: Map<GlowFocusField, GlowFieldState>,
    val restoration: GlowFocusRestore?,
)

internal data class GlowFocusRestore(
    val observed: Border?,
    val desired: Border?,
    val isFocused: Boolean,
    val outcome: GlowFieldState,
)

internal fun planFocusRestore(
    record: GlowFocusRecord,
    observed: Border?,
    isFocused: Boolean,
    lastAttempt: GlowFocusRestore?,
): GlowFocusRestore {
    val desired = if (isFocused && record.before.enabled) record.beforeBorder else record.baselineBorder
    val wasAttempted = lastAttempt != null && observed === lastAttempt.desired
    val isOwned = observed === record.plannedBorder || observed === record.beforeBorder || wasAttempted
    val outcome =
        when {
            observed === desired -> GlowFieldState.RESTORED
            isOwned -> GlowFieldState.WRITING
            else -> GlowFieldState.FOREIGN
        }
    return GlowFocusRestore(observed, desired, isFocused, outcome)
}
