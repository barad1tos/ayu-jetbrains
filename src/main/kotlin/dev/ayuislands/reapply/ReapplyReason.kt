package dev.ayuislands.reapply

import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AyuVariant

/**
 * Why a [theme reapplication][ThemeReapplication] is happening. Selects the
 * ordered [ReapplyStep] sequence via [ThemeReapplication.planFor].
 */
sealed interface ReapplyReason {
    /** IDE LAF changed. [context] = Ayu(variant) | External | null (switched away from Ayu). */
    data class ThemeSwitched(
        val context: AccentContext?,
    ) : ReapplyReason

    /** License downgrade: re-apply the free-default accent [freeHex] and reset premium surfaces. */
    data class LicenseRevert(
        val freeHex: String,
    ) : ReapplyReason

    /** Accent-rotation scheduler fired for [variant]. */
    data class RotationTick(
        val variant: AyuVariant,
    ) : ReapplyReason
}

/** One atomic re-application unit. Each step self-gates on its own toggle and no-ops when off. */
enum class ReapplyStep {
    BindScheme,
    ApplyResolvedAccent,
    ApplyExplicitHex,
    Font,
    Notify,
    Glow,
    Syntax,
    RevertAccent,
    RevertFont,
    VcsRevert,
}

/** A step that threw while running, paired with its cause. */
data class StepFailure(
    val step: ReapplyStep,
    val error: Throwable,
)

/** Outcome of running a plan: the steps that failed (empty = clean). Steps isolate, never abort. */
data class ReapplyResult(
    val failures: List<StepFailure>,
) {
    val isClean: Boolean get() = failures.isEmpty()

    fun failed(step: ReapplyStep): Boolean = failures.any { it.step == step }
}
