package dev.ayuislands.accent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import javax.swing.SwingUtilities

/**
 * Thin executor for an [AccentApplyPlan]. Owns exactly two concerns:
 * dispatching the whole plan onto a single EDT turn, and isolating step
 * failures per the plan's [AccentApplyFailurePolicy]. All step semantics live
 * in the worker lambdas the caller binds.
 *
 * Dispatch contract: synchronous when already on the EDT (load-bearing for
 * callers that must observe the apply before returning, e.g. the settings
 * panels behind `applyForFocusedProject`), otherwise one
 * `invokeLater(nonModal)` hop. The EDT check uses [SwingUtilities] rather than
 * `Application.isDispatchThread` so dispatch still works when the Application
 * is not yet (or no longer) available.
 *
 * Cancellation ([com.intellij.openapi.progress.ProcessCanceledException],
 * coroutine cancellation) and [VirtualMachineError] (OOM, stack overflow)
 * always rethrow — only genuine step failures are captured into
 * [AccentApplyStepFailure]s. Note [onComplete] is NOT invoked when a rethrow
 * interrupts the plan mid-run: it reports completed runs (clean or torn), not
 * cancelled ones, so cleanup must not live in it.
 */
internal object AccentApplyPlanRunner {
    private val log = logger<AccentApplyPlanRunner>()

    fun run(
        plan: AccentApplyPlan,
        executeStep: (AccentApplyStep) -> Unit,
        onComplete: (List<AccentApplyStepFailure>) -> Unit,
    ) {
        val work = Runnable { onComplete(runSteps(plan, executeStep)) }
        if (SwingUtilities.isEventDispatchThread()) {
            work.run()
        } else {
            invokeLaterSafe(work)
        }
    }

    private fun runSteps(
        plan: AccentApplyPlan,
        executeStep: (AccentApplyStep) -> Unit,
    ): List<AccentApplyStepFailure> {
        val failures = mutableListOf<AccentApplyStepFailure>()
        for (step in plan.steps) {
            runCatchingPreservingCancellation { executeStep(step) }
                .exceptionOrNull()
                ?.let { error ->
                    // A JVM-level error (heap exhaustion, stack overflow) must not
                    // degrade into a per-step WARN — the process is compromised and
                    // the old pre-plan code let it propagate. LinkageError stays
                    // contained: plugin-unload races produce NoClassDefFoundError
                    // in integration steps by design (ThemeReapplication precedent).
                    if (error is VirtualMachineError) throw error
                    failures += AccentApplyStepFailure(step, error)
                }
            if (failures.isNotEmpty() && plan.policy == AccentApplyFailurePolicy.AbortOnFirstFailure) break
        }
        return failures
    }

    private fun invokeLaterSafe(work: Runnable) {
        val app = ApplicationManager.getApplication()
        if (app != null) {
            app.invokeLater(work, ModalityState.nonModal())
        } else {
            log.warn(
                "Application not available, " +
                    "falling back to SwingUtilities",
            )
            SwingUtilities.invokeLater(work)
        }
    }
}
