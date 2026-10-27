
package dev.ayuislands.accent

internal enum class LifecycleTiming { IMMEDIATE, DEFERRED }

/** Serializes lifecycle requests without running them inside an active apply or restoration. */
internal class AccentLifecycleQueue(
    private val post: (() -> Unit) -> Unit,
) {
    private enum class State { IDLE, RUNNING, WAITING, SCHEDULED }

    private var state = State.IDLE
    private val pending = ArrayDeque<() -> Unit>()

    fun request(action: (LifecycleTiming) -> Unit) {
        val scope = AccentApplyScope.active
        if (state == State.IDLE && scope == null) {
            execute { action(LifecycleTiming.IMMEDIATE) }
            return
        }
        pending.addLast { action(LifecycleTiming.DEFERRED) }
        scope?.requestAbort()
        if (state == State.IDLE) state = State.WAITING
    }

    fun afterApply() {
        if (state == State.WAITING && AccentApplyScope.active == null) schedule()
    }

    private fun execute(action: () -> Unit) {
        state = State.RUNNING
        try {
            action()
        } finally {
            if (pending.isEmpty()) state = State.IDLE else schedule()
        }
    }

    private fun schedule() {
        state = State.SCHEDULED
        post {
            val scope = AccentApplyScope.active
            if (scope != null) {
                state = State.WAITING
                scope.requestAbort()
            } else {
                execute(pending.removeFirst())
            }
        }
    }
}

internal object AccentLifecycle {
    private val queue = AccentLifecycleQueue { action -> AccentApplyPlanRunner.post(Runnable(action)) }

    fun request(action: (LifecycleTiming) -> Unit) = queue.request(action)

    fun afterApply() = queue.afterApply()
}
