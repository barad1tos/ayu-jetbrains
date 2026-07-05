package dev.ayuislands.accent

/**
 * One atomic unit of the accent apply/revert pipeline. Pure identifier —
 * [AccentApplicator] binds each step to its side-effecting worker at run time,
 * so the ordering invariant lives in [applyPlanFor]/[revertPlan] as data
 * instead of in the shape of an opaque Runnable.
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
 * An ordered step sequence bound to the failure policy it must run under.
 * Bundling the two makes the pairing structural: an apply plan cannot be run
 * with the revert policy (which would strand a torn base half-painted) and a
 * revert plan cannot abort early (which would strand surfaces tinted).
 */
internal data class AccentApplyPlan(
    val steps: List<AccentApplyStep>,
    val policy: AccentApplyFailurePolicy,
)

/**
 * Ordered steps for a full accent apply under [context], paired with
 * [AccentApplyFailurePolicy.AbortOnFirstFailure] — later steps must not build
 * on a torn base, and the skipped [AccentApplyStep.MarkApplyClean] leaves the
 * persisted flag false (unless the sole failure is the final
 * [AccentApplyStep.PublishAccentChanged], which runs after the flag write).
 *
 * Pure — reads no settings and touches no platform; Ayu-only surfaces
 * (element EPs, tab underline) and the IndentRainbow sync gate on the context
 * shape. This is the ONLY gating site: the worker map in [AccentApplicator]
 * binds every step unconditionally.
 *
 * Ordering locks encoded here (formerly defended by a source-regex test):
 *  - Integrations run IndentRainbow before CodeGlancePro before the
 *    component-tree notify, and the revert plan unwinds them in the same
 *    relative order, so app-scoped integration caches never drift between
 *    the two paths.
 *  - [AccentApplyStep.MarkApplyClean] immediately precedes
 *    [AccentApplyStep.PublishAccentChanged]: subscribers observe
 *    `lastApplyOk == true`, and a throwing subscriber cannot re-tear the flag.
 */
internal fun applyPlanFor(context: AccentContext?): AccentApplyPlan =
    AccentApplyPlan(
        steps =
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
            },
        policy = AccentApplyFailurePolicy.AbortOnFirstFailure,
    )

/**
 * Ordered steps for a full accent revert, paired with
 * [AccentApplyFailurePolicy.ContinuePerStep] — each surface unwinds
 * independently, so one failing integration must not strand the others
 * tinted. Mirrors the apply plan's integration order (IndentRainbow →
 * CodeGlancePro → notify); deliberately omits repaint (revert runs inside
 * `lookAndFeelChanged` before the new theme finishes loading, and a forced
 * repaint NPEs in `HeaderToolbarButtonLook`), the clean-flag write, and the
 * accent-changed publish.
 */
internal fun revertPlan(): AccentApplyPlan =
    AccentApplyPlan(
        steps =
            listOf(
                AccentApplyStep.ClearUiAndExtensions,
                AccentApplyStep.RevertAlwaysOnEditorKeys,
                AccentApplyStep.RevertIndentRainbow,
                AccentApplyStep.RevertCodeGlanceProViewport,
                AccentApplyStep.NotifyComponentTrees,
            ),
        policy = AccentApplyFailurePolicy.ContinuePerStep,
    )

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

    /**
     * A step threw; later steps (if any) were skipped. The persisted clean
     * flag is false unless the sole failure was the final
     * [AccentApplyStep.PublishAccentChanged], which runs after
     * [AccentApplyStep.MarkApplyClean] already flipped it true.
     */
    data class Torn(
        val accentHex: AccentHex,
        val failures: List<AccentApplyStepFailure>,
    ) : AccentApplyOutcome {
        init {
            require(failures.isNotEmpty()) { "Torn outcome requires at least one step failure" }
        }
    }

    companion object {
        fun of(
            accentHex: AccentHex,
            failures: List<AccentApplyStepFailure>,
        ): AccentApplyOutcome = if (failures.isEmpty()) Applied(accentHex) else Torn(accentHex, failures)
    }
}

/**
 * How the runner reacts to a throwing step. The legal pairing is structural:
 * [applyPlanFor] always bundles [AbortOnFirstFailure], [revertPlan] always
 * bundles [ContinuePerStep] — see their KDocs for the rationale.
 */
internal enum class AccentApplyFailurePolicy {
    AbortOnFirstFailure,
    ContinuePerStep,
}
