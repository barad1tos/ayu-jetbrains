package dev.ayuislands.accent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import javax.swing.SwingUtilities

/**
 * Thin executor for an [AccentApplyPlan] step list. Owns exactly two concerns:
 * dispatching the whole plan onto a single EDT turn, and isolating step
 * failures per [AccentApplyFailurePolicy]. All step semantics live in the
 * worker lambdas the caller binds.
 *
 * Dispatch contract: synchronous when already on the EDT (load-bearing for
 * callers that must observe the apply before returning, e.g. the settings
 * panels behind `applyForFocusedProject`), otherwise one
 * `invokeLater(nonModal)` hop. The EDT check uses [SwingUtilities] rather than
 * `Application.isDispatchThread` so dispatch still works when the Application
 * is not yet (or no longer) available.
 *
 * Cancellation ([com.intellij.openapi.progress.ProcessCanceledException],
 * coroutine cancellation) always rethrows — only genuine step failures are
 * captured into [AccentApplyStepFailure]s.
 */
internal object AccentApplyPlanRunner {
    private val log = logger<AccentApplyPlanRunner>()

    fun run(
        plan: List<AccentApplyStep>,
        policy: AccentApplyFailurePolicy,
        executeStep: (AccentApplyStep) -> Unit,
        onComplete: (List<AccentApplyStepFailure>) -> Unit = {},
    ) {
        val work = Runnable { onComplete(runSteps(plan, policy, executeStep)) }
        if (SwingUtilities.isEventDispatchThread()) {
            work.run()
        } else {
            invokeLaterSafe(work)
        }
    }

    private fun runSteps(
        plan: List<AccentApplyStep>,
        policy: AccentApplyFailurePolicy,
        executeStep: (AccentApplyStep) -> Unit,
    ): List<AccentApplyStepFailure> {
        val failures = mutableListOf<AccentApplyStepFailure>()
        for (step in plan) {
            runCatchingPreservingCancellation { executeStep(step) }
                .exceptionOrNull()
                ?.let { failures += AccentApplyStepFailure(step, it) }
            if (failures.isNotEmpty() && policy == AccentApplyFailurePolicy.AbortOnFirstFailure) break
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
