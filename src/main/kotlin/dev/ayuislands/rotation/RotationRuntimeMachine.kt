package dev.ayuislands.rotation

internal fun transitionRotationRuntime(
    state: RotationRuntimeState,
    event: RotationRuntimeEvent,
    clock: ClockSample,
    sessionFault: RuntimeFault.SessionCircuitBreaker? = null,
): RotationTransition {
    if (state == RotationRuntimeState.Disposed) return RotationTransition(state)
    return when (event) {
        is RotationRuntimeEvent.Reconcile -> reconcileRotation(state, event.current, clock, sessionFault)
        is RotationRuntimeEvent.TimerDue -> consumeRotationWake(state, event.ticket, clock.monotonicNanos)
        is RotationRuntimeEvent.CommandFinished -> reconcileReadback(state, event, clock, sessionFault)
        is RotationRuntimeEvent.CommandBlocked ->
            if (state is RotationRuntimeState.Applying && state.current == event.source) {
                faultedRotation(state, state.current, event.fault)
            } else {
                RotationTransition(state)
            }
        is RotationRuntimeEvent.ArmRejected ->
            if (state is RotationRuntimeState.Waiting && state.ticket == event.ticket) {
                faultedRotation(state, state.current, RuntimeFault.SchedulerUnavailable)
            } else {
                RotationTransition(state)
            }
        RotationRuntimeEvent.Dispose -> cancelPreviousWait(state, RotationTransition(RotationRuntimeState.Disposed))
        RotationRuntimeEvent.AuthorityUnavailable ->
            cancelPreviousWait(state, RotationTransition(RotationRuntimeState.Loading))
    }
}

private fun reconcileReadback(
    state: RotationRuntimeState,
    event: RotationRuntimeEvent.CommandFinished,
    clock: ClockSample,
    sessionFault: RuntimeFault.SessionCircuitBreaker?,
): RotationTransition =
    when {
        (state as? RotationRuntimeState.Applying)?.current != event.source -> RotationTransition(state)
        // Wait for another refresh before retrying a command whose source is still current.
        event.current == event.source -> RotationTransition(RotationRuntimeState.Loading)
        else -> reconcileRotation(state, event.current, clock, sessionFault)
    }

private fun reconcileRotation(
    state: RotationRuntimeState,
    current: RotationRuntimeSnapshot,
    clock: ClockSample,
    sessionFault: RuntimeFault.SessionCircuitBreaker?,
): RotationTransition {
    val awaitingRecovery = (state as? RotationRuntimeState.Faulted)?.fault == RuntimeFault.RecoveryPending
    if (!awaitingRecovery &&
        (state as? RotationRuntimeState.Loaded)?.current == current
    ) {
        return RotationTransition(state)
    }
    val fault = current.checkpoint.recoveryFault ?: sessionFault
    val next =
        when {
            fault != null -> faultedRotation(state, current, fault)
            !current.intent.enabled -> RotationTransition(RotationRuntimeState.Off(current))
            current.availability.pauseReasons.isNotEmpty() -> RotationTransition(RotationRuntimeState.Paused(current))
            else -> reconcileEligibleRotation(state, current, clock)
        }
    return cancelPreviousWait(state, next)
}

private fun reconcileEligibleRotation(
    state: RotationRuntimeState,
    current: RotationRuntimeSnapshot,
    clock: ClockSample,
): RotationTransition {
    val retained = retainRotationWait(state as? RotationRuntimeState.Waiting, current, clock.monotonicNanos)
    if (retained != null) return retained
    return when (
        val timing =
            planRotationTiming(
                current.checkpoint,
                current.intent.intervalHoursRaw,
                clock.epochMillis,
            )
    ) {
        RotationTimingPlan.StepDue -> stepDue(current)
        is RotationTimingPlan.Wait -> {
            val ticket =
                checkNotNull(armRotationWait(current.checkpoint, timing, current.generation, clock.monotonicNanos))
            waitForRotation(current, ticket)
        }
        is RotationTimingPlan.Persist ->
            RotationTransition.applying(RotationRuntimeCommand.PersistCheckpoint(current, timing))
        RotationTimingPlan.Unrepresentable -> faultedRotation(state, current, RuntimeFault.UnrepresentableAppointment)
    }
}

private fun retainRotationWait(
    waiting: RotationRuntimeState.Waiting?,
    current: RotationRuntimeSnapshot,
    nowNanos: Long,
): RotationTransition? {
    if (waiting == null || waiting.current.checkpoint.schedule != current.checkpoint.schedule) return null
    val ticket = waiting.ticket
    val rebound = rotationTimerKey(current.checkpoint, current.generation) ?: return null
    if (rebound.generation.service != ticket.key.generation.service) return null
    if (rebound == ticket.key) return RotationTransition(waiting.copy(current = current))
    return when (val next = advanceRotationWait(ticket, ticket, ticket.key, nowNanos)) {
        RotationWakePlan.Due -> stepDue(current)
        is RotationWakePlan.Arm -> waitForRotation(current, next.ticket.copy(key = rebound))
        RotationWakePlan.Ignored -> error("The current rotation ticket could not be rebound")
    }
}

private fun consumeRotationWake(
    state: RotationRuntimeState,
    callback: RotationWaitTicket,
    nowNanos: Long,
): RotationTransition {
    if (state !is RotationRuntimeState.Waiting) return RotationTransition(state)
    val current = state.current
    val key = rotationTimerKey(current.checkpoint, current.generation)
    return when (val next = advanceRotationWait(state.ticket, callback, key, nowNanos)) {
        RotationWakePlan.Due -> stepDue(current)
        is RotationWakePlan.Arm -> waitForRotation(current, next.ticket)
        RotationWakePlan.Ignored -> RotationTransition(state)
    }
}

private fun cancelPreviousWait(
    previous: RotationRuntimeState,
    next: RotationTransition,
): RotationTransition {
    if (previous !is RotationRuntimeState.Waiting) return next
    if ((next.state as? RotationRuntimeState.Waiting)?.ticket == previous.ticket) return next
    return next.copy(
        commands =
            buildList {
                add(RotationRuntimeCommand.CancelSchedule(previous.ticket))
                addAll(next.commands)
            },
    )
}

private fun waitForRotation(
    current: RotationRuntimeSnapshot,
    ticket: RotationWaitTicket,
): RotationTransition =
    RotationTransition(
        RotationRuntimeState.Waiting(current, ticket),
        listOf(RotationRuntimeCommand.ArmSchedule(ticket)),
    )

private fun stepDue(current: RotationRuntimeSnapshot): RotationTransition =
    RotationTransition.applying(RotationRuntimeCommand.StepDue(current))

private fun faultedRotation(
    previous: RotationRuntimeState,
    current: RotationRuntimeSnapshot,
    fault: RuntimeFault,
): RotationTransition {
    val commands =
        if ((previous as? RotationRuntimeState.Faulted)?.fault ==
            fault
        ) {
            emptyList()
        } else {
            listOf(RotationRuntimeCommand.ReportFault(fault))
        }
    return RotationTransition(RotationRuntimeState.Faulted(current, fault), commands)
}
