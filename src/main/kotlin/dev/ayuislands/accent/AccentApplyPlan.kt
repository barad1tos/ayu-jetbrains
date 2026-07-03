package dev.ayuislands.accent

/**
 * One atomic unit of the accent apply/revert pipeline. Pure identifier —
 * [AccentApplicator] binds each step to its side-effecting worker at run time,
 * so the ordering invariant lives in [AccentApplyPlan] as data instead of in
 * the shape of an opaque Runnable.
 */
internal enum class AccentApplyStep {
    ApplyAlwaysOnUiKeys,
    ApplyElements,
    SyncIndentRainbow,
    SyncCodeGlanceProViewport,
    ApplyAlwaysOnEditorKeys,
    ApplyTabUnderline,
    NotifyComponentTrees,
    RepaintWindows,
    MarkApplyClean,
    PublishAccentChanged,
    ClearUiAndExtensions,
    RevertAlwaysOnEditorKeys,
    RevertIndentRainbow,
    RevertCodeGlanceProViewport,
}

/**
 * The single source of the accent apply/revert step ordering.
 *
 * Ordering locks encoded here (formerly defended by a source-regex test):
 *  - Integrations run IndentRainbow before CodeGlancePro before the
 *    component-tree notify, and the revert plan unwinds them in the same
 *    relative order, so app-scoped integration caches never drift between
 *    the two paths.
 *  - [AccentApplyStep.MarkApplyClean] immediately precedes
 *    [AccentApplyStep.PublishAccentChanged]: subscribers observe
 *    `lastApplyOk == true`, and a throwing subscriber cannot re-tear the flag.
 *  - The revert plan has no [AccentApplyStep.RepaintWindows]: revert runs
 *    inside `lookAndFeelChanged` before the new theme finishes loading, and a
 *    forced repaint NPEs in `HeaderToolbarButtonLook`.
 */
internal object AccentApplyPlan {
    /**
     * Ordered steps for a full accent apply under [context]. Pure — reads no
     * settings and touches no platform; Ayu-only surfaces (element EPs, tab
     * underline) and the IndentRainbow sync gate on the context shape.
     */
    fun applyPlanFor(context: AccentContext?): List<AccentApplyStep> =
        buildList {
            add(AccentApplyStep.ApplyAlwaysOnUiKeys)
            if (context?.variant != null) add(AccentApplyStep.ApplyElements)
            if (context != null) add(AccentApplyStep.SyncIndentRainbow)
            add(AccentApplyStep.SyncCodeGlanceProViewport)
            add(AccentApplyStep.ApplyAlwaysOnEditorKeys)
            if (context?.variant != null) add(AccentApplyStep.ApplyTabUnderline)
            add(AccentApplyStep.NotifyComponentTrees)
            add(AccentApplyStep.RepaintWindows)
            add(AccentApplyStep.MarkApplyClean)
            add(AccentApplyStep.PublishAccentChanged)
        }

    /**
     * Ordered steps for a full accent revert. Mirrors the apply plan's
     * integration order (IndentRainbow → CodeGlancePro → notify); deliberately
     * omits repaint, the clean-flag write, and the accent-changed publish.
     */
    fun revertPlan(): List<AccentApplyStep> =
        listOf(
            AccentApplyStep.ClearUiAndExtensions,
            AccentApplyStep.RevertAlwaysOnEditorKeys,
            AccentApplyStep.RevertIndentRainbow,
            AccentApplyStep.RevertCodeGlanceProViewport,
            AccentApplyStep.NotifyComponentTrees,
        )
}

/** A step that threw while running, paired with its cause. */
internal data class AccentApplyStepFailure(
    val step: AccentApplyStep,
    val error: Throwable,
)

/**
 * Typed outcome of one accent apply pass — the in-process replacement for
 * reading the persisted `lastApplyOk` two-phase flag mid-flight. The persisted
 * flag itself remains the cross-restart torn-state marker.
 */
internal sealed interface AccentApplyOutcome {
    /** Every step of the plan completed. */
    data class Applied(
        val accentHex: AccentHex,
    ) : AccentApplyOutcome

    /** A step threw; later steps were skipped and the clean flag stayed false. */
    data class Torn(
        val accentHex: AccentHex,
        val failures: List<AccentApplyStepFailure>,
    ) : AccentApplyOutcome

    companion object {
        fun of(
            accentHex: AccentHex,
            failures: List<AccentApplyStepFailure>,
        ): AccentApplyOutcome = if (failures.isEmpty()) Applied(accentHex) else Torn(accentHex, failures)
    }
}

/**
 * How the runner reacts to a throwing step. Apply aborts — later steps must
 * not build on a torn base, and the skipped [AccentApplyStep.MarkApplyClean]
 * leaves the persisted flag false exactly as the pre-plan code did. Revert
 * continues — each surface unwinds independently, so one failing integration
 * must not strand the others tinted.
 */
internal enum class AccentApplyFailurePolicy {
    AbortOnFirstFailure,
    ContinuePerStep,
}
