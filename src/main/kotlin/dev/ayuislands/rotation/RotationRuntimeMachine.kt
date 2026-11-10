package dev.ayuislands.rotation

import dev.ayuislands.studio.model.ApplyFailure
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.CommitResolution
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Pure transitions emit storage and timer requests; an adapter returns acknowledgements as events. */
internal object RotationRuntimeMachine {
    fun transition(
        state: RotationRuntimeState,
        event: RotationEvent,
        clock: ClockSample,
    ): RotationTransition {
        if (state !is RotationRuntimeState.Loaded) return Startup.transition(state, event, clock)
        return when (event) {
            is RotationEvent.Initialize, is RotationEvent.Loaded, RotationEvent.ViewOnly -> {
                RotationTransition(state)
            }

            is RotationEvent.UserAccepted -> {
                RotationTransition(RotationRuntimeState.Applying(event.snapshot, event.settlement))
            }

            is RotationEvent.NativeChange -> RotationNativeChange.prepare(state, event, clock)
            is RotationEvent.ContextChange -> RotationContextHandoff.prepare(state, event, clock)

            RotationEvent.Dispose -> {
                Startup.dispose(state)
            }

            is RotationEvent.TimerDue -> {
                timerDue(state, event, clock)
            }

            is RotationEvent.RandomColorChosen -> {
                colorChosen(state, event)
            }

            is RotationEvent.AvailabilityObserved -> {
                observe(state, event, clock)
            }

            is RotationEvent.Prepared -> {
                RotationStepLifecycle.prepared(state, event, clock)
            }

            is RotationEvent.EffectsSettled -> {
                RotationStepLifecycle.settled(state, event)
            }

            is RotationEvent.CommitResolved -> {
                committed(state, event, clock)
            }

            is RotationEvent.RetryRecovery -> {
                RotationRecovery.retryRecovery(state, event, clock)
            }
        }
    }

    private fun timerDue(
        state: RotationRuntimeState,
        event: RotationEvent.TimerDue,
        clock: ClockSample,
    ): RotationTransition {
        if (state !is RotationRuntimeState.Waiting || event.ticket != state.ticket) return RotationTransition(state)
        val elapsed = elapsedMillis(state.ticket.startedAtMonotonicNanos, clock)
        val remaining = maxOf(0L, state.ticket.remainingMillis - elapsed)
        return if (remaining > 0) {
            arm(state.snapshot, remaining, clock)
        } else {
            prepareStep(state.snapshot, event.operationId, null)
        }
    }

    private fun colorChosen(
        state: RotationRuntimeState,
        event: RotationEvent.RandomColorChosen,
    ): RotationTransition {
        if (state !is RotationRuntimeState.Applying) return RotationTransition(state)
        val request = state.operation
        if (request !is RotationColorRequest || request.operationId != event.operationId) {
            return RotationTransition(state)
        }
        val deferred = state.deferredResume
        if (deferred != null) return resumeDeferred(state.snapshot, deferred)
        if (state.snapshot.inputs.availability.reasons
                .isNotEmpty()
        ) {
            return RotationTransition(RotationRuntimeState.Paused(state.snapshot))
        }
        return prepareStep(state.snapshot, event.operationId, event.hex)
    }

    private fun prepareStep(
        snapshot: RotationSnapshot,
        operationId: UUID,
        randomHex: String?,
    ): RotationTransition =
        when (val plan = RotationStepPlanner.planStep(snapshot.checkpoint, snapshot.inputs, operationId, randomHex)) {
            is RotationStepPlan.Ready -> {
                RotationTransition(
                    RotationRuntimeState.Applying(snapshot, RotationStepPreparation(operationId, plan.candidate)),
                    StudioList(listOf(RotationCommand.PrepareStep(operationId, plan.candidate, snapshot.checkpoint))),
                )
            }

            is RotationStepPlan.RandomRequired -> {
                RotationTransition(
                    RotationRuntimeState.Applying(snapshot, RotationColorRequest(plan.operationId, plan.variant)),
                    StudioList(listOf(RotationCommand.RequestRandomColor(plan.operationId, plan.variant))),
                )
            }

            is RotationStepPlan.Blocked -> {
                fault(snapshot, "rotation-step-${plan.reason.name.lowercase()}")
            }
        }

    private fun observe(
        state: RotationRuntimeState.Loaded,
        event: RotationEvent.AvailabilityObserved,
        clock: ClockSample,
    ): RotationTransition {
        val previous = state.snapshot
        if (event.generation <= previous.observationGeneration) return RotationTransition(state)
        val availability =
            event.availability.copy(
                lastDefinitivePro =
                    event.availability.lastDefinitivePro
                        ?: previous.inputs.availability.lastDefinitivePro,
            )
        val observed =
            previous.copy(
                inputs = previous.inputs.copy(availability = availability),
                observationGeneration = event.generation,
                consecutiveFailures = if (availability.reasons.isEmpty()) previous.consecutiveFailures else 0,
            )
        return when (state) {
            is RotationRuntimeState.Off -> {
                RotationTransition(state.copy(snapshot = observed))
            }

            is RotationRuntimeState.Paused -> {
                observePaused(state, observed, event.operationId, clock)
            }

            is RotationRuntimeState.Waiting -> {
                observeWaiting(state, observed, event.operationId, clock)
            }

            is RotationRuntimeState.Applying -> {
                observeApplying(state, observed, event.operationId, clock)
            }

            is RotationRuntimeState.Faulted.Loaded -> {
                RotationTransition(
                    state.copy(
                        snapshot = observed,
                        deferredResume = RotationResumePolicy.observe(state, observed, event.operationId, clock),
                    ),
                )
            }
        }
    }

    private fun observePaused(
        state: RotationRuntimeState.Paused,
        observed: RotationSnapshot,
        operationId: UUID,
        clock: ClockSample,
    ): RotationTransition {
        val retry = state.pendingRetry
        if (retry != null) {
            return RotationRecovery.scheduleRetry(observed, retry.copy(operationId = operationId), clock)
        }
        val needsFollow =
            observed.inputs.availability.effectiveFollowSystem &&
                observed.checkpoint.resume !is RotationResume.FullInterval
        val deferred =
            state.deferredResume
                ?: if (needsFollow) {
                    RotationResumeRequest(
                        operationId,
                        RotationResume.FullInterval(FullIntervalReason.FOLLOW_SYSTEM),
                        clock,
                    )
                } else {
                    null
                }
        return if (deferred == null) {
            reconcile(observed, operationId, clock)
        } else {
            resumeDeferred(observed, deferred.copy(operationId = operationId, observedAt = clock))
        }
    }

    private fun observeWaiting(
        state: RotationRuntimeState.Waiting,
        observed: RotationSnapshot,
        operationId: UUID,
        clock: ClockSample,
    ): RotationTransition {
        val availability = observed.inputs.availability
        if (availability.reasons.isEmpty()) return RotationTransition(state.copy(snapshot = observed))
        val paused =
            if (availability.effectiveFollowSystem) {
                resumeDeferred(
                    observed,
                    RotationResumeRequest(
                        operationId,
                        RotationResume.FullInterval(FullIntervalReason.FOLLOW_SYSTEM),
                        clock,
                    ),
                )
            } else {
                RotationTransition(RotationRuntimeState.Paused(observed))
            }
        return paused.copy(
            commands = StudioList(listOf(RotationCommand.CancelSchedule(state.ticket)) + paused.commands),
        )
    }

    private fun observeApplying(
        state: RotationRuntimeState.Applying,
        observed: RotationSnapshot,
        operationId: UUID,
        clock: ClockSample,
    ): RotationTransition {
        val current = observed.inputs.availability
        val deferred = RotationResumePolicy.observe(state, observed, operationId, clock)
        val request = state.operation
        if (request is RotationColorRequest && request.variant != current.variant) {
            if (deferred != null) return resumeDeferred(observed, deferred)
            if (current.reasons.isNotEmpty()) return RotationTransition(RotationRuntimeState.Paused(observed))
            return prepareStep(observed, operationId, null)
        }
        return RotationTransition(state.copy(snapshot = observed, deferredResume = deferred))
    }

    private fun reconcile(
        snapshot: RotationSnapshot,
        operationId: UUID,
        clock: ClockSample,
    ): RotationTransition {
        if (!snapshot.inputs.intent.enabled) return RotationTransition(RotationRuntimeState.Off(snapshot))
        if (snapshot.checkpoint.owner != snapshot.inputs.owner) return Startup.rebind(snapshot, operationId, clock)
        if (snapshot.inputs.availability.reasons
                .isNotEmpty()
        ) {
            return RotationTransition(RotationRuntimeState.Paused(snapshot))
        }
        return when (snapshot.checkpoint.resume) {
            RotationResume.KeepDeadline -> restoreAppointment(snapshot, operationId, clock)

            RotationResume.NoHistory,
            RotationResume.ImmediateEnable,
            RotationResume.ImmediateConfiguration,
            -> prepareStep(snapshot, operationId, null)

            is RotationResume.FullInterval -> schedule(snapshot, operationId, clock)
        }
    }

    private fun restoreAppointment(
        snapshot: RotationSnapshot,
        operationId: UUID,
        clock: ClockSample,
    ): RotationTransition {
        val appointment = snapshot.checkpoint.schedule
        if (appointment == null || !isUsable(appointment)) return schedule(snapshot, operationId, clock)
        if (clock.epochMillis < appointment.scheduledAtEpochMillis) {
            return schedule(snapshot, operationId, clock, appointment.intervalMillisAtScheduling)
        }
        val remaining =
            if (appointment.deadlineEpochMillis <= clock.epochMillis) {
                0L
            } else {
                appointment.deadlineEpochMillis - clock.epochMillis
            }
        return arm(snapshot, remaining, clock)
    }

    private fun isUsable(appointment: RotationSchedule): Boolean {
        val interval = appointment.intervalMillisAtScheduling
        if (interval <= 0 || appointment.scheduleSerial <= 0) return false
        if (appointment.scheduledAtEpochMillis > Long.MAX_VALUE - interval) return false
        return appointment.deadlineEpochMillis == appointment.scheduledAtEpochMillis + interval
    }

    private fun schedule(
        snapshot: RotationSnapshot,
        operationId: UUID,
        clock: ClockSample,
        interval: Long = snapshot.inputs.intent.intervalMillis,
    ): RotationTransition =
        when (val plan = RotationStepPlanner.planSchedule(snapshot.checkpoint, interval, clock.epochMillis)) {
            is RotationCompletionPlan.Blocked -> fault(snapshot, "rotation-schedule-overflow")
            is RotationCompletionPlan.Ready -> persist(snapshot, operationId, plan.checkpoint, clock)
        }

    private fun persist(
        snapshot: RotationSnapshot,
        operationId: UUID,
        proposed: RotationRuntimeCheckpoint,
        clock: ClockSample,
    ): RotationTransition {
        val operation = RotationScheduleCommit(operationId, proposed, clock)
        return RotationTransition(
            RotationRuntimeState.Applying(snapshot, operation),
            StudioList(listOf(RotationCommand.PersistCheckpoint(operation))),
        )
    }

    private fun arm(
        snapshot: RotationSnapshot,
        delayMillis: Long,
        clock: ClockSample,
    ): RotationTransition {
        val appointment = checkNotNull(snapshot.checkpoint.schedule)
        val ticket =
            RotationTicket(
                snapshot.inputs.owner,
                appointment.scheduleSerial,
                minOf(delayMillis, TimeUnit.DAYS.toMillis(1)),
                clock.monotonicNanos,
                delayMillis,
            )
        return RotationTransition(
            RotationRuntimeState.Waiting(snapshot, ticket),
            StudioList(listOf(RotationCommand.ArmSchedule(ticket))),
        )
    }

    private fun committed(
        state: RotationRuntimeState,
        event: RotationEvent.CommitResolved,
        clock: ClockSample,
    ): RotationTransition {
        if (state !is RotationRuntimeState.Applying ||
            !matchesCompletion(state.operation, event.operationId, event.completionId)
        ) {
            return RotationTransition(state)
        }
        return when (val pending = state.operation) {
            is RotationSettlement -> {
                settlementOutcome(state, pending, event.outcome, clock)
            }

            is RotationOutcomeResolution -> {
                RotationRecovery.resolved(state, pending, event.outcome)
            }

            is RotationRestore -> {
                RotationTransition(state)
            }

            is RotationCheckpointCommit -> {
                when (event.outcome) {
                    RotationCommitOutcome.RejectedRestored -> {
                        RotationRecovery.rejectedCheckpoint(state, pending)
                    }

                    RotationCommitOutcome.Accepted -> {
                        RotationStepLifecycle.startSettlement(state, pending)
                    }

                    is RotationCommitOutcome.NotCommitted -> {
                        RotationRecovery.restore(state, pending, event.outcome.failure)
                    }

                    is RotationCommitOutcome.OutcomeUnknown -> {
                        RotationRecovery.resolve(state, pending, event.outcome.failure)
                    }

                    RotationCommitOutcome.Settled, is RotationCommitOutcome.AcceptedCleanupPending,
                    is RotationCommitOutcome.Readback,
                    -> {
                        error("A settlement acknowledgement requires a durably accepted step")
                    }
                }
            }

            is RotationColorRequest, is RotationStepPreparation, is RotationStepEffects,
            is RotationCheckpointRetry, is RotationRetryRequest,
            -> {
                error("A checkpoint acknowledgement requires a pending checkpoint write")
            }
        }
    }

    private fun settlementOutcome(
        state: RotationRuntimeState.Applying,
        operation: RotationSettlement,
        outcome: RotationCommitOutcome,
        clock: ClockSample,
    ): RotationTransition =
        when (outcome) {
            RotationCommitOutcome.Accepted, is RotationCommitOutcome.Readback -> {
                RotationTransition(state)
            }

            RotationCommitOutcome.Settled -> {
                accepted(state, operation, clock)
            }

            is RotationCommitOutcome.AcceptedCleanupPending -> {
                RotationStepLifecycle.cleanupPending(
                    state,
                    operation,
                    outcome,
                )
            }

            RotationCommitOutcome.RejectedRestored, is RotationCommitOutcome.NotCommitted,
            is RotationCommitOutcome.OutcomeUnknown,
            -> {
                error("A durably accepted step cannot be rolled back")
            }
        }

    private fun accepted(
        state: RotationRuntimeState.Applying,
        operation: RotationSettlement,
        clock: ClockSample,
    ): RotationTransition {
        val snapshot = state.snapshot.copy(checkpoint = operation.commit.checkpoint)
        retainedTransition(snapshot, operation.commit)?.let { return it }
        if (!snapshot.inputs.intent.enabled) return RotationTransition(RotationRuntimeState.Off(snapshot))
        if (snapshot.consecutiveFailures >= MAX_RESTORED_FAILURES) {
            val breaker = RuntimeFault.SessionCircuitBreaker
            return RotationTransition(
                RotationRuntimeState.Faulted.Loaded(snapshot, breaker),
                StudioList(listOf(RotationCommand.ReportFault(breaker))),
            )
        }
        val deferred = state.deferredResume
        if (deferred != null) return resumeDeferred(snapshot, deferred)
        if (snapshot.inputs.availability.reasons
                .isNotEmpty()
        ) {
            return RotationTransition(RotationRuntimeState.Paused(snapshot.copy(consecutiveFailures = 0)))
        }
        val restored = operation.restoredWait
        val remaining =
            if (restored != null) {
                restored.remainingMillis - elapsedMillis(restored.startedAtMonotonicNanos, clock)
            } else {
                val appointment = checkNotNull(operation.commit.checkpoint.schedule)
                appointment.intervalMillisAtScheduling - elapsedMillis(operation.commit.startedAt.monotonicNanos, clock)
            }
        return arm(snapshot, maxOf(0L, remaining), clock)
    }

    private fun retainedTransition(
        snapshot: RotationSnapshot,
        commit: RotationCheckpointCommit,
    ): RotationTransition? {
        val previous =
            when (commit) {
                is RotationNativeCommit -> commit.previous
                is RotationContextCommit -> {
                    if (commit.kind != RotationHandoffKind.CONTINUITY) return null
                    commit.previous
                }
                else -> return null
            }
        return when (previous) {
            is RotationRuntimeState.Faulted.Loaded -> RotationTransition(previous.copy(snapshot = snapshot))
            is RotationRuntimeState.Paused ->
                RotationTransition(
                    previous.copy(
                        snapshot = snapshot,
                        pendingRetry =
                            previous.pendingRetry?.let { retry ->
                                retry.copy(candidate = retry.candidate.copy(owner = snapshot.inputs.owner))
                            },
                    ),
                )
            else ->
                snapshot.checkpoint.recoveryFault?.let { fault ->
                    RotationTransition(RotationRuntimeState.Faulted.Loaded(snapshot, fault))
                }
        }
    }

    private fun resumeDeferred(
        snapshot: RotationSnapshot,
        deferred: RotationResumeRequest,
    ): RotationTransition {
        if (snapshot.inputs.availability.recoveryPending) {
            return RotationTransition(RotationRuntimeState.Paused(snapshot, deferred))
        }
        if (snapshot.checkpoint.owner != snapshot.inputs.owner) {
            return Startup.rebind(snapshot, deferred.operationId, deferred.observedAt)
        }
        if (snapshot.inputs.availability.reasons
                .isEmpty()
        ) {
            return schedule(snapshot, deferred.operationId, deferred.observedAt)
        }
        if (snapshot.checkpoint.resume == deferred.resume) {
            return RotationTransition(RotationRuntimeState.Paused(snapshot))
        }
        return persist(
            snapshot,
            deferred.operationId,
            snapshot.checkpoint.copy(resume = deferred.resume),
            deferred.observedAt,
        )
    }

    /** Bootstrap and terminal states never invent an accepted owner or read platform state. */
    private object Startup {
        fun transition(
            state: RotationRuntimeState,
            event: RotationEvent,
            clock: ClockSample,
        ): RotationTransition {
            if (state is RotationRuntimeState.Disposed) return RotationTransition(state)
            return when (event) {
                is RotationEvent.Initialize -> {
                    if (state is RotationRuntimeState.Loading && state.operationId == null) {
                        RotationTransition(state.copy(operationId = event.operationId))
                    } else {
                        RotationTransition(state)
                    }
                }

                is RotationEvent.Loaded -> {
                    if (state is RotationRuntimeState.Loading && state.operationId == event.operationId) {
                        loaded(state, event, clock)
                    } else {
                        RotationTransition(state)
                    }
                }

                is RotationEvent.AvailabilityObserved -> {
                    observe(state, event)
                }

                is RotationEvent.RetryRecovery -> {
                    if (state is RotationRuntimeState.Faulted.Unloaded) {
                        RotationTransition(RotationRuntimeState.Loading(event.operationId, state.observation))
                    } else {
                        RotationTransition(state)
                    }
                }

                RotationEvent.Dispose -> {
                    dispose(state)
                }

                RotationEvent.ViewOnly, is RotationEvent.TimerDue, is RotationEvent.RandomColorChosen,
                is RotationEvent.Prepared, is RotationEvent.EffectsSettled, is RotationEvent.CommitResolved,
                is RotationEvent.UserAccepted, is RotationEvent.ContextChange, is RotationEvent.NativeChange,
                -> {
                    RotationTransition(state)
                }
            }
        }

        fun dispose(state: RotationRuntimeState): RotationTransition {
            val pending =
                when (state) {
                    is RotationRuntimeState.Applying -> state.operation

                    is RotationRuntimeState.Faulted.Loaded -> state.pendingOperation

                    is RotationRuntimeState.Disposed -> return RotationTransition(state)

                    is RotationRuntimeState.Loading, is RotationRuntimeState.Faulted.Unloaded,
                    is RotationRuntimeState.Off, is RotationRuntimeState.Paused, is RotationRuntimeState.Waiting,
                    -> null
                }
            val commands =
                if (state is RotationRuntimeState.Waiting) {
                    StudioList(listOf(RotationCommand.CancelSchedule(state.ticket)))
                } else {
                    StudioList(emptyList())
                }
            return RotationTransition(
                RotationRuntimeState.Disposed((state as? RotationRuntimeState.Loaded)?.snapshot, pending),
                commands,
            )
        }

        private fun observe(
            state: RotationRuntimeState,
            event: RotationEvent.AvailabilityObserved,
        ): RotationTransition {
            val previous =
                when (state) {
                    is RotationRuntimeState.Loading -> {
                        state.observation
                    }

                    is RotationRuntimeState.Faulted.Unloaded -> {
                        state.observation
                    }

                    is RotationRuntimeState.Loaded, is RotationRuntimeState.Disposed -> {
                        error("A cold observation requires loading or an unloaded fault")
                    }
                }
            if (previous != null && event.generation <= previous.generation) return RotationTransition(state)
            val observed =
                event.copy(
                    availability =
                        event.availability.copy(
                            lastDefinitivePro =
                                event.availability.lastDefinitivePro ?: previous?.availability?.lastDefinitivePro,
                        ),
                )
            return when (state) {
                is RotationRuntimeState.Loading -> {
                    RotationTransition(state.copy(observation = observed))
                }

                is RotationRuntimeState.Faulted.Unloaded -> {
                    RotationTransition(state.copy(observation = observed))
                }

                is RotationRuntimeState.Loaded, is RotationRuntimeState.Disposed -> {
                    error("A cold observation requires loading or an unloaded fault")
                }
            }
        }

        private fun loaded(
            state: RotationRuntimeState.Loading,
            event: RotationEvent.Loaded,
            clock: ClockSample,
        ): RotationTransition =
            when (val outcome = event.outcome) {
                is RotationLoadOutcome.Unavailable -> {
                    RotationTransition(
                        RotationRuntimeState.Faulted.Unloaded(outcome.fault, state.observation),
                        StudioList(listOf(RotationCommand.ReportFault(outcome.fault))),
                    )
                }

                is RotationLoadOutcome.Ready -> {
                    ready(merge(outcome.snapshot, state.observation), event.operationId, clock)
                }

                is RotationLoadOutcome.Recovering -> {
                    RotationTransition(
                        RotationRuntimeState.Applying(merge(outcome.snapshot, state.observation), outcome.operation),
                        StudioList(listOf(RotationCommand.ResolveStoreOutcome(outcome.operation))),
                    )
                }

                is RotationLoadOutcome.Settling -> {
                    settle(merge(outcome.snapshot, state.observation), outcome.operation, clock)
                }
            }

        private fun merge(
            snapshot: RotationSnapshot,
            observation: RotationEvent.AvailabilityObserved?,
        ): RotationSnapshot {
            if (observation == null || observation.generation <= snapshot.observationGeneration) return snapshot
            val availability =
                observation.availability.copy(
                    lastDefinitivePro =
                        observation.availability.lastDefinitivePro ?: snapshot.inputs.availability.lastDefinitivePro,
                )
            return snapshot.copy(
                inputs = snapshot.inputs.copy(availability = availability),
                observationGeneration = observation.generation,
            )
        }

        private fun ready(
            snapshot: RotationSnapshot,
            operationId: UUID,
            clock: ClockSample,
        ): RotationTransition {
            val recovery = snapshot.checkpoint.recoveryFault
            if (recovery != null) {
                return RotationTransition(
                    RotationRuntimeState.Faulted.Loaded(snapshot, recovery),
                    StudioList(listOf(RotationCommand.ReportFault(recovery))),
                )
            }
            val fresh = snapshot.copy(consecutiveFailures = 0)
            if (!fresh.inputs.intent.enabled) return RotationTransition(RotationRuntimeState.Off(fresh))
            if (fresh.inputs.availability.effectiveFollowSystem) {
                return resumeDeferred(
                    fresh,
                    RotationResumeRequest(
                        operationId,
                        RotationResume.FullInterval(FullIntervalReason.FOLLOW_SYSTEM),
                        clock,
                    ),
                )
            }
            return reconcile(fresh, operationId, clock)
        }

        fun rebind(
            snapshot: RotationSnapshot,
            operationId: UUID,
            clock: ClockSample,
        ): RotationTransition {
            if (snapshot.inputs.availability.recoveryPending) {
                return RotationTransition(
                    RotationRuntimeState.Paused(snapshot),
                )
            }
            val reason =
                if (snapshot.inputs.availability.effectiveFollowSystem) {
                    FullIntervalReason.FOLLOW_SYSTEM
                } else {
                    FullIntervalReason.INTERRUPTED_HANDOFF
                }
            val replacement =
                snapshot.checkpoint.copy(
                    owner = snapshot.inputs.owner,
                    output = null,
                    retryCandidate = null,
                    resume = RotationResume.FullInterval(reason),
                )
            val proposed =
                if (snapshot.inputs.availability.reasons
                        .isEmpty()
                ) {
                    RotationStepPlanner.planSchedule(
                        replacement,
                        snapshot.inputs.intent.intervalMillis,
                        clock.epochMillis,
                    )
                } else {
                    RotationCompletionPlan.Ready(replacement)
                }
            return when (proposed) {
                is RotationCompletionPlan.Ready -> persist(snapshot, operationId, proposed.checkpoint, clock)
                is RotationCompletionPlan.Blocked -> fault(snapshot, "rotation-schedule-overflow")
            }
        }

        private fun settle(
            snapshot: RotationSnapshot,
            operation: RotationSettlement,
            clock: ClockSample,
        ): RotationTransition {
            require(snapshot.checkpoint == operation.commit.checkpoint) {
                "Accepted recovery requires the exact committed checkpoint"
            }
            val restored = restoreAppointment(snapshot, operation.operationId, clock)
            val waiting =
                checkNotNull(restored.state as? RotationRuntimeState.Waiting) {
                    "Accepted recovery requires a qualified appointment"
                }
            val pending =
                when (operation) {
                    is RotationStepSettlement -> operation.copy(restoredWait = waiting.ticket)
                    is RotationScheduleSettlement -> operation.copy(restoredWait = waiting.ticket)
                    is RotationContextSettlement -> operation.copy(restoredWait = waiting.ticket)
                    is RotationNativeSettlement -> operation.copy(restoredWait = waiting.ticket)
                }
            return RotationTransition(
                RotationRuntimeState.Applying(snapshot, pending),
                StudioList(listOf(RotationCommand.PublishAccepted(pending))),
            )
        }
    }
}

/** Handles step phase acknowledgements; the shared arbiter owns native work and recovery. */
private fun elapsedMillis(
    startedAtMonotonicNanos: Long,
    clock: ClockSample,
): Long = TimeUnit.NANOSECONDS.toMillis(maxOf(0L, clock.monotonicNanos - startedAtMonotonicNanos))

private object RotationStepLifecycle {
    fun prepared(
        state: RotationRuntimeState,
        event: RotationEvent.Prepared,
        clock: ClockSample,
    ): RotationTransition {
        if (state !is RotationRuntimeState.Applying || state.operation.operationId != event.operationId) {
            return RotationTransition(state)
        }
        val operation =
            when (val pending = state.operation) {
                is RotationStepPreparation -> {
                    pending
                }

                is RotationStepEffects, is RotationStepCommit, is RotationSettlement,
                is RotationOutcomeResolution, is RotationRestore,
                -> {
                    return RotationTransition(state)
                }

                is RotationColorRequest, is RotationScheduleCommit, is RotationContextCommit, is RotationNativeCommit,
                is RotationCheckpointRetry,
                is RotationRetryRequest,
                -> {
                    error("A preparation acknowledgement requires a pending step preparation")
                }
            }
        return when (val outcome = event.outcome) {
            is RotationPreparationOutcome.RejectedRestored -> {
                RotationRecovery.restoredStep(
                    state,
                    operation.candidate,
                    outcome.nextOperationId,
                    clock,
                )
            }

            is RotationPreparationOutcome.OutcomeUnknown -> {
                RotationRecovery.resolve(state, operation, outcome.failure)
            }

            RotationPreparationOutcome.Ready -> {
                check(
                    state.deferredResume == null &&
                        state.snapshot.inputs.availability.reasons
                            .isEmpty(),
                ) {
                    "A qualified preparation acknowledgement requires a current eligible lease"
                }
                RotationTransition(
                    state.copy(operation = RotationStepEffects(event.operationId, operation.candidate)),
                    StudioList(listOf(RotationCommand.ApplyPreparedEffects(event.operationId, operation.candidate))),
                )
            }
        }
    }

    fun settled(
        state: RotationRuntimeState,
        event: RotationEvent.EffectsSettled,
    ): RotationTransition {
        if (state !is RotationRuntimeState.Applying ||
            !matchesCompletion(state.operation, event.operationId, event.completionId)
        ) {
            return RotationTransition(state)
        }
        return when (val operation = state.operation) {
            is RotationStepEffects -> {
                when (val outcome = event.outcome) {
                    RotationEffectOutcome.Applied -> {
                        completeStep(state, operation, event.completedAt)
                    }

                    is RotationEffectOutcome.Failed -> {
                        RotationRecovery.restore(state, operation, outcome.failure)
                    }

                    is RotationEffectOutcome.RejectedRestored -> {
                        RotationRecovery.restoredStep(
                            state,
                            operation.candidate,
                            outcome.nextOperationId,
                            event.completedAt,
                        )
                    }

                    is RotationEffectOutcome.RecoveryPending -> {
                        RotationRecovery.unfinished(
                            state,
                            RotationRestore(operation, outcome.failure),
                            outcome.failure,
                        )
                    }
                }
            }

            is RotationRestore -> {
                RotationRecovery.restored(state, operation, event)
            }

            is RotationStepCommit, is RotationSettlement, is RotationOutcomeResolution -> {
                RotationTransition(state)
            }

            is RotationColorRequest,
            is RotationScheduleCommit,
            is RotationContextCommit,
            is RotationNativeCommit,
            is RotationStepPreparation,
            is RotationCheckpointRetry,
            is RotationRetryRequest,
            -> {
                error("An effects acknowledgement requires pending step effects")
            }
        }
    }

    private fun completeStep(
        state: RotationRuntimeState.Applying,
        operation: RotationStepEffects,
        completedAt: ClockSample,
    ): RotationTransition {
        check(state.deferredResume == null) { "A qualified effects acknowledgement cannot follow lease invalidation" }
        return when (
            val plan =
                RotationStepPlanner.planCompletion(
                    state.snapshot.checkpoint,
                    state.snapshot.inputs,
                    operation.candidate,
                    completedAt.epochMillis,
                )
        ) {
            is RotationCompletionPlan.Ready -> {
                val commit =
                    RotationStepCommit(operation.operationId, plan.checkpoint, completedAt, operation.candidate)
                RotationTransition(
                    state.copy(operation = commit),
                    StudioList(listOf(RotationCommand.PersistCheckpoint(commit))),
                )
            }

            is RotationCompletionPlan.Blocked -> {
                RotationRecovery.restore(
                    state,
                    operation,
                    ApplyFailure("rotation-completion-${plan.reason.name.lowercase()}"),
                )
            }
        }
    }

    fun startSettlement(
        state: RotationRuntimeState.Applying,
        operation: RotationCheckpointCommit,
        completionId: UUID = operation.operationId,
    ): RotationTransition {
        val settlement =
            when (operation) {
                is RotationStepCommit -> {
                    RotationStepSettlement(
                        operation,
                        RotationSettlementProgress.PUBLICATION_PENDING,
                        completionId,
                    )
                }

                is RotationNativeCommit -> {
                    RotationNativeSettlement(operation, RotationSettlementProgress.PUBLICATION_PENDING, completionId)
                }

                is RotationContextCommit -> {
                    RotationContextSettlement(operation, RotationSettlementProgress.PUBLICATION_PENDING, completionId)
                }

                is RotationScheduleCommit -> {
                    RotationScheduleSettlement(
                        operation,
                        RotationSettlementProgress.PUBLICATION_PENDING,
                        completionId,
                    )
                }
            }
        val snapshot =
            state.snapshot.copy(
                checkpoint = operation.checkpoint,
                consecutiveFailures = if (operation is RotationStepCommit) 0 else state.snapshot.consecutiveFailures,
            )
        return RotationTransition(
            state.copy(snapshot = snapshot, operation = settlement),
            StudioList(listOf(RotationCommand.PublishAccepted(settlement))),
        )
    }

    fun cleanupPending(
        state: RotationRuntimeState.Applying,
        operation: RotationSettlement,
        outcome: RotationCommitOutcome.AcceptedCleanupPending,
    ): RotationTransition {
        check(
            operation.progress != RotationSettlementProgress.CLEANUP_PENDING ||
                outcome.progress == RotationSettlementProgress.CLEANUP_PENDING,
        ) {
            "Acknowledged publication cannot become pending again"
        }
        val pending =
            when (operation) {
                is RotationStepSettlement -> operation.copy(progress = outcome.progress)
                is RotationScheduleSettlement -> operation.copy(progress = outcome.progress)
                is RotationContextSettlement -> operation.copy(progress = outcome.progress)
                is RotationNativeSettlement -> operation.copy(progress = outcome.progress)
            }
        return RotationTransition(
            RotationRuntimeState.Faulted.Loaded(state.snapshot, outcome.failure, pending, state.deferredResume),
            StudioList(listOf(RotationCommand.ReportFault(outcome.failure))),
        )
    }
}

/** Failure recovery retains one journal identity and qualifies each new I/O attempt separately. */
private object RotationRecovery {
    fun rejectedCheckpoint(
        state: RotationRuntimeState.Applying,
        operation: RotationCheckpointCommit,
    ): RotationTransition {
        if (operation is RotationNativeCommit) {
            val failure = RuntimeFault.DurableRecovery("native-context-not-applied")
            return RotationTransition(
                RotationRuntimeState.Faulted.Loaded(operation.before, failure, RotationCheckpointRetry(operation)),
                StudioList(listOf(RotationCommand.ReportFault(failure))),
            )
        }
        if (operation is RotationContextCommit) {
            val failure = RuntimeFault.DurableRecovery("context-not-applied")
            return RotationTransition(
                RotationRuntimeState.Faulted.Loaded(operation.before, failure, RotationCheckpointRetry(operation)),
                StudioList(listOf(RotationCommand.ReportFault(failure))),
            )
        }
        check(operation is RotationScheduleCommit) {
            "A restored color step requires a rollback acknowledgement with a fresh checkpoint identity"
        }
        return unfinished(state, RotationCheckpointRetry(operation), ApplyFailure("rotation-checkpoint-not-saved"))
    }

    fun resolve(
        state: RotationRuntimeState.Applying,
        pending: RotationJournalOperation,
        failure: ApplyFailure,
        completionId: UUID = pending.operationId,
    ): RotationTransition {
        val request = RotationOutcomeResolution(pending, failure, completionId)
        return RotationTransition(
            state.copy(operation = request),
            StudioList(listOf(RotationCommand.ResolveStoreOutcome(request))),
        )
    }

    fun restore(
        state: RotationRuntimeState.Applying,
        pending: RotationJournalOperation,
        failure: ApplyFailure,
        completionId: UUID = pending.operationId,
    ): RotationTransition {
        val request = RotationRestore(pending, failure, completionId)
        return RotationTransition(
            state.copy(operation = request),
            StudioList(listOf(RotationCommand.RestoreOwnedEffects(request))),
        )
    }

    fun resolved(
        state: RotationRuntimeState.Applying,
        operation: RotationOutcomeResolution,
        outcome: RotationCommitOutcome,
    ): RotationTransition =
        when (outcome) {
            is RotationCommitOutcome.Readback -> {
                when (outcome.resolution) {
                    CommitResolution.Committed -> {
                        val commit = operation.pending
                        check(
                            commit is RotationCheckpointCommit,
                        ) { "A committed readback requires an exact proposed checkpoint" }
                        RotationStepLifecycle.startSettlement(state, commit, operation.completionId)
                    }

                    CommitResolution.NotCommitted -> {
                        restore(state, operation.pending, operation.failure, operation.completionId)
                    }

                    CommitResolution.Unknown -> {
                        unfinished(state, operation, operation.failure)
                    }
                }
            }

            is RotationCommitOutcome.OutcomeUnknown, is RotationCommitOutcome.NotCommitted -> {
                RotationTransition(state)
            }

            RotationCommitOutcome.Accepted, RotationCommitOutcome.RejectedRestored, RotationCommitOutcome.Settled,
            is RotationCommitOutcome.AcceptedCleanupPending,
            -> {
                error("Store resolution requires a qualified readback")
            }
        }

    fun unfinished(
        state: RotationRuntimeState.Applying,
        operation: RotationOperation,
        failure: ApplyFailure,
    ): RotationTransition {
        val fault = RuntimeFault.DurableRecovery(failure.code)
        return RotationTransition(
            RotationRuntimeState.Faulted.Loaded(state.snapshot, fault, operation, state.deferredResume),
            StudioList(listOf(RotationCommand.ReportFault(fault))),
        )
    }

    fun restored(
        state: RotationRuntimeState.Applying,
        operation: RotationRestore,
        event: RotationEvent.EffectsSettled,
    ): RotationTransition =
        when (val outcome = event.outcome) {
            is RotationEffectOutcome.RejectedRestored -> {
                when (val pending = operation.pending) {
                    is RotationStepPreparation -> {
                        restoredStep(
                            state,
                            pending.candidate,
                            outcome.nextOperationId,
                            event.completedAt,
                        )
                    }

                    is RotationStepEffects -> {
                        restoredStep(state, pending.candidate, outcome.nextOperationId, event.completedAt)
                    }

                    is RotationStepCommit -> {
                        restoredStep(state, pending.candidate, outcome.nextOperationId, event.completedAt)
                    }

                    is RotationScheduleCommit -> {
                        unfinished(state, RotationCheckpointRetry(pending), operation.failure)
                    }
                    is RotationContextCommit, is RotationNativeCommit -> rejectedCheckpoint(state, pending)
                }
            }

            is RotationEffectOutcome.RecoveryPending -> {
                unfinished(state, operation, outcome.failure)
            }

            is RotationEffectOutcome.Failed -> {
                RotationTransition(state)
            }

            RotationEffectOutcome.Applied -> {
                error("A rollback acknowledgement cannot accept a color step")
            }
        }

    fun restoredStep(
        state: RotationRuntimeState.Applying,
        candidate: RotationCandidate,
        nextOperationId: UUID,
        clock: ClockSample,
    ): RotationTransition {
        require(
            nextOperationId != state.operation.operationId,
        ) { "A retry checkpoint requires a fresh transaction identity" }
        val snapshot =
            state.snapshot.copy(
                consecutiveFailures =
                    minOf(MAX_RESTORED_FAILURES - 1, state.snapshot.consecutiveFailures) + 1,
            )
        return scheduleRetry(snapshot, RotationRetryRequest(nextOperationId, candidate), clock)
    }

    fun scheduleRetry(
        snapshot: RotationSnapshot,
        request: RotationRetryRequest,
        clock: ClockSample,
    ): RotationTransition {
        val availability = snapshot.inputs.availability
        val stopped = if (availability.reasons.isEmpty()) snapshot else snapshot.copy(consecutiveFailures = 0)
        if (availability.recoveryPending) {
            return RotationTransition(RotationRuntimeState.Paused(stopped, pendingRetry = request))
        }
        val retained = snapshot.checkpoint.copy(retryCandidate = request.candidate)
        val plan =
            if (availability.reasons.isEmpty()) {
                RotationStepPlanner.planSchedule(retained, snapshot.inputs.intent.intervalMillis, clock.epochMillis)
            } else {
                val reason =
                    if (availability.effectiveFollowSystem) {
                        FullIntervalReason.FOLLOW_SYSTEM
                    } else {
                        FullIntervalReason.RESTORED_RETRY
                    }
                RotationCompletionPlan.Ready(retained.copy(resume = RotationResume.FullInterval(reason)))
            }
        return when (plan) {
            is RotationCompletionPlan.Blocked -> {
                unfinished(
                    RotationRuntimeState.Applying(stopped, request),
                    request,
                    ApplyFailure("rotation-retry-${plan.reason.name.lowercase()}"),
                )
            }

            is RotationCompletionPlan.Ready -> {
                val commit = RotationScheduleCommit(request.operationId, plan.checkpoint, clock)
                RotationTransition(
                    RotationRuntimeState.Applying(stopped, commit),
                    StudioList(listOf(RotationCommand.PersistCheckpoint(commit))),
                )
            }
        }
    }

    fun retryRecovery(
        state: RotationRuntimeState,
        event: RotationEvent.RetryRecovery,
        clock: ClockSample,
    ): RotationTransition {
        if (state !is RotationRuntimeState.Faulted.Loaded) return RotationTransition(state)
        val pending = state.pendingOperation
        if (pending == null) {
            if (state.fault != RuntimeFault.SessionCircuitBreaker) return RotationTransition(state)
            val candidate = checkNotNull(state.snapshot.checkpoint.retryCandidate)
            return scheduleRetry(
                state.snapshot.copy(consecutiveFailures = 0),
                RotationRetryRequest(event.operationId, candidate),
                clock,
            )
        }
        val applying = RotationRuntimeState.Applying(state.snapshot, pending, state.deferredResume)
        return when (pending) {
            is RotationSettlement -> {
                resumeSettlement(applying, pending, event.operationId)
            }

            is RotationOutcomeResolution -> {
                resolve(applying, pending.pending, pending.failure, event.operationId)
            }

            is RotationRestore -> {
                resolve(applying, pending.pending, pending.failure, event.operationId)
            }

            is RotationCheckpointRetry -> {
                retryCheckpoint(state, pending, event.operationId, clock)
            }

            is RotationRetryRequest -> {
                scheduleRetry(state.snapshot, pending.copy(operationId = event.operationId), clock)
            }

            is RotationColorRequest, is RotationJournalOperation -> {
                error("Recovery requires retained settlement or ownership evidence")
            }
        }
    }

    private fun retryCheckpoint(
        state: RotationRuntimeState.Faulted.Loaded,
        pending: RotationCheckpointRetry,
        operationId: UUID,
        clock: ClockSample,
    ): RotationTransition {
        val snapshot = state.snapshot
        val availability = snapshot.inputs.availability
        if (availability.recoveryPending) return RotationTransition(state)
        val native = pending.commit as? RotationNativeCommit
        if (native != null) return RotationNativeChange.retry(native, operationId, clock)
        val context = pending.commit as? RotationContextCommit
        if (context != null) return RotationContextHandoff.retry(context, operationId, clock)
        val candidate = pending.commit.checkpoint.retryCandidate
        if (candidate != null) return scheduleRetry(snapshot, RotationRetryRequest(operationId, candidate), clock)
        val plan =
            if (availability.reasons.isEmpty()) {
                RotationStepPlanner.planSchedule(
                    snapshot.checkpoint,
                    snapshot.inputs.intent.intervalMillis,
                    clock.epochMillis,
                )
            } else {
                val resume =
                    state.deferredResume?.resume
                        ?: pending.commit.checkpoint.resume as? RotationResume.FullInterval
                        ?: RotationResume.FullInterval(FullIntervalReason.REPAIRED_SCHEDULE)
                RotationCompletionPlan.Ready(snapshot.checkpoint.copy(resume = resume))
            }
        return when (plan) {
            is RotationCompletionPlan.Blocked -> {
                unfinished(
                    RotationRuntimeState.Applying(snapshot, pending, state.deferredResume),
                    pending,
                    ApplyFailure("rotation-schedule-retry-${plan.reason.name.lowercase()}"),
                )
            }

            is RotationCompletionPlan.Ready -> {
                val commit = RotationScheduleCommit(operationId, plan.checkpoint, clock)
                RotationTransition(
                    RotationRuntimeState.Applying(snapshot, commit),
                    StudioList(listOf(RotationCommand.PersistCheckpoint(commit))),
                )
            }
        }
    }

    private fun resumeSettlement(
        state: RotationRuntimeState.Applying,
        pending: RotationSettlement,
        completionId: UUID,
    ): RotationTransition {
        val request =
            when (pending) {
                is RotationStepSettlement -> pending.copy(completionId = completionId)
                is RotationScheduleSettlement -> pending.copy(completionId = completionId)
                is RotationContextSettlement -> pending.copy(completionId = completionId)
                is RotationNativeSettlement -> pending.copy(completionId = completionId)
            }
        return RotationTransition(
            state.copy(operation = request),
            StudioList(listOf(RotationCommand.PublishAccepted(request))),
        )
    }
}

/** Observations retain timing debt even while a journal prevents the next schedule write. */
private object RotationResumePolicy {
    fun observe(
        state: RotationRuntimeState.Loaded,
        observed: RotationSnapshot,
        operationId: UUID,
        clock: ClockSample,
    ): RotationResumeRequest? {
        val (operation, existing) =
            when (state) {
                is RotationRuntimeState.Applying -> {
                    state.operation to state.deferredResume
                }

                is RotationRuntimeState.Faulted.Loaded -> {
                    state.pendingOperation to state.deferredResume
                }

                is RotationRuntimeState.Off, is RotationRuntimeState.Paused, is RotationRuntimeState.Waiting -> {
                    error("Deferred observation requires an active or faulted operation")
                }
            }
        val previous = state.snapshot.inputs.availability
        val current = observed.inputs.availability
        if (previous == current) return existing
        if (previous.reasons.isEmpty() && current.reasons.isEmpty()) return existing
        val resume =
            when {
                previous.effectiveFollowSystem || current.effectiveFollowSystem -> {
                    RotationResume.FullInterval(FullIntervalReason.FOLLOW_SYSTEM)
                }

                existing != null -> {
                    existing.resume
                }

                else -> {
                    checkpoint(operation)?.resume as? RotationResume.FullInterval
                        ?: state.snapshot.checkpoint.resume as? RotationResume.FullInterval
                        ?: return null
                }
            }
        return RotationResumeRequest(operationId, resume, clock)
    }

    private fun checkpoint(operation: RotationOperation?): RotationRuntimeCheckpoint? =
        when (operation) {
            is RotationCheckpointCommit -> operation.checkpoint

            is RotationSettlement -> operation.commit.checkpoint

            is RotationOutcomeResolution -> checkpoint(operation.pending)

            is RotationRestore -> checkpoint(operation.pending)

            is RotationCheckpointRetry -> operation.commit.checkpoint

            is RotationColorRequest, is RotationStepPreparation, is RotationStepEffects,
            is RotationRetryRequest, null,
            -> null
        }
}

private const val MAX_RESTORED_FAILURES = 3

private fun matchesCompletion(
    operation: RotationOperation,
    operationId: UUID,
    completionId: UUID,
): Boolean = operation.operationId == operationId && operation.completionId == completionId

private fun fault(
    snapshot: RotationSnapshot,
    code: String,
): RotationTransition {
    val fault = RuntimeFault.DurableRecovery(code)
    return RotationTransition(
        RotationRuntimeState.Faulted.Loaded(snapshot, fault),
        StudioList(listOf(RotationCommand.ReportFault(fault))),
    )
}

internal val RuntimeAvailability.reasons: StudioList<RotationPauseReason>
    get() =
        StudioList(
            buildList {
                if (effectiveFollowSystem) add(RotationPauseReason.FOLLOW_SYSTEM)
                if (lastDefinitivePro != true) add(RotationPauseReason.NO_PRO)
                if (variant == null) add(RotationPauseReason.EXTERNAL_THEME)
                if (!targetValid) add(RotationPauseReason.TARGET_UNAVAILABLE)
                if (recoveryPending) add(RotationPauseReason.RECOVERY_PENDING)
            },
        )

internal val RotationRuntimeState.Paused.reasons: StudioList<RotationPauseReason>
    get() = snapshot.inputs.availability.reasons

internal val RotationIntent.intervalMillis: Long
    get() = TimeUnit.HOURS.toMillis(maxOf(1L, intervalHoursRaw.toLong()))

/** Read-time interpretation never replaces raw accepted fields or their migration provenance. */
internal val RuntimeInputs.intent: RotationIntent
    get() {
        val fields = appearance.accent.records.associate { it.key.name to it.value }
        val mode = fields.getValue("accentRotationMode")
        return RotationIntent(
            requireNotNull(fields["accentRotationEnabled"] as? RawValue.BooleanValue).value,
            if (mode == RawValue.Null) null else requireNotNull(mode as? RawValue.Text).value,
            requireNotNull(fields["accentRotationIntervalHours"] as? RawValue.Integer).value,
        )
    }
