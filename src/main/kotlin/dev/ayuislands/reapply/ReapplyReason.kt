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
    VcsApply,
    VcsRevert,
}

/** A step that threw while running, paired with its cause. */
data class StepFailure(
    val step: ReapplyStep,
    val error: Throwable,
)

/** Completed request outcome; a superseded request is distinct from an executed clean plan. */
data class ReapplyResult(
    val failures: List<StepFailure>,
    val status: ReapplyStatus = ReapplyStatus.EXECUTED,
) {
    val isClean: Boolean get() = status == ReapplyStatus.EXECUTED && failures.isEmpty()

    fun failed(step: ReapplyStep): Boolean = failures.any { it.step == step }
}

/** A stale downgrade request may finish without executing a replacement plan. */
enum class ReapplyStatus { EXECUTED, SUPERSEDED }
