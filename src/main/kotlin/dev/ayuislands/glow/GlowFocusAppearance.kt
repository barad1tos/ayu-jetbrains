package dev.ayuislands.glow

import java.awt.Color
import javax.swing.border.Border

internal enum class GlowFocusField { APPEARANCE, BORDER }

internal data class GlowFocusAppearance(
    val color: Color,
    val style: GlowStyle,
    val intensity: Int,
    val enabled: Boolean,
)

internal data class GlowFocusOwnership(
    val installed: Border,
    val original: Border?,
)

internal data class GlowFocusPeer(
    val listener: GlowFocusListener,
    val before: GlowFocusAppearance,
    val planned: GlowFocusAppearance,
    val ownership: GlowFocusOwnership?,
)

internal data class GlowFocusBorderPlan(
    val writer: GlowFocusListener,
    val observed: Border?,
    val baseline: Border?,
    val canWrite: Boolean,
)

/** Exact references are runtime evidence, never a serialized replacement for native objects. */
internal data class GlowFocusRecord(
    val before: GlowFocusAppearance,
    val planned: GlowFocusAppearance,
    val beforeBorder: Border?,
    val plannedBorder: Border?,
    val baselineBorder: Border?,
    val phase: GlowCheckpointPhase,
    val fields: Map<GlowFocusField, GlowFieldState>,
    val restoration: GlowBorderRestore?,
)

/** Captured restoration evidence; a null desired border represents removal. */
internal data class GlowBorderRestore(
    val observed: Border?,
    val border: Border?,
    val isFocused: Boolean,
    val outcome: GlowFieldState,
)

/** A new listener's initial border is allocated before its membership is written. */
internal data class GlowInitialBorderRecord(
    val before: Border?,
    val planned: Border?,
    val isFocused: Boolean,
    val state: GlowFieldState,
)
