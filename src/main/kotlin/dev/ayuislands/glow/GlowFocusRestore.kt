package dev.ayuislands.glow

import javax.swing.border.Border

/** Classifies exact retained references without reading or changing a Swing component. */
internal fun prepareFocusRestore(
    record: GlowFocusRecord,
    observed: Border?,
    isFocused: Boolean,
    lastAttempt: GlowBorderRestore?,
): GlowBorderRestore {
    val desired = if (isFocused && record.before.enabled) record.beforeBorder else record.baselineBorder
    val wasAttempted = lastAttempt != null && observed === lastAttempt.border
    val outcome =
        when {
            observed === record.plannedBorder || wasAttempted ->
                if (observed === desired) GlowFieldState.RESTORED else GlowFieldState.WRITING
            observed === desired || observed === record.beforeBorder -> GlowFieldState.RESTORED
            else -> GlowFieldState.FOREIGN
        }
    return GlowBorderRestore(observed, desired, isFocused, outcome)
}
