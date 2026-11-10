package dev.ayuislands.studio.session

import dev.ayuislands.rotation.ClockSample
import dev.ayuislands.rotation.RotationCheckpointCommit
import dev.ayuislands.rotation.RotationCommand
import dev.ayuislands.rotation.RotationCommitOutcome
import dev.ayuislands.rotation.RotationContextCommit
import dev.ayuislands.rotation.RotationEffectOutcome
import dev.ayuislands.rotation.RotationEvent
import dev.ayuislands.rotation.RotationHandoffKind
import dev.ayuislands.rotation.RotationJournalOperation
import dev.ayuislands.rotation.RotationLoadOutcome
import dev.ayuislands.rotation.RotationNativeCommit
import dev.ayuislands.rotation.RotationOperation
import dev.ayuislands.rotation.RotationOutcomeResolution
import dev.ayuislands.rotation.RotationPreparationOutcome
import dev.ayuislands.rotation.RotationRestore
import dev.ayuislands.rotation.RotationRuntimeCheckpoint
import dev.ayuislands.rotation.RotationRuntimeState
import dev.ayuislands.rotation.RotationScheduleCommit
import dev.ayuislands.rotation.RotationSettlement
import dev.ayuislands.rotation.RotationSettlementProgress
import dev.ayuislands.rotation.RotationSnapshot
import dev.ayuislands.rotation.RotationStepCommit
import dev.ayuislands.rotation.RotationStepEffects
import dev.ayuislands.rotation.RotationStepPreparation
import dev.ayuislands.rotation.RotationTransition
import dev.ayuislands.rotation.RuntimeFault
import dev.ayuislands.rotation.RuntimeInputs
import dev.ayuislands.rotation.RuntimeOwner
import dev.ayuislands.studio.model.AcceptancePlan
import dev.ayuislands.studio.model.AcceptanceResult
import dev.ayuislands.studio.model.ApplyFailure
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.GlowDependency
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioGlowSelection
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioNativeChange
import dev.ayuislands.studio.model.StudioRuntimeState
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.CommitResolution
import dev.ayuislands.studio.persistence.DecodeResult
import dev.ayuislands.studio.persistence.PendingJournal
import dev.ayuislands.studio.persistence.RecoveryPurpose
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioStateCodec
import dev.ayuislands.studio.runtime.RuntimeDispatch
import dev.ayuislands.studio.runtime.StudioRotationProjection
import dev.ayuislands.studio.runtime.StudioRuntimeCoordinator
import dev.ayuislands.studio.runtime.StudioRuntimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID

/** Interprets one admitted dispatch using the coordinator's existing transaction and recovery operations. */
internal class StudioRuntimeExecution(
    private val runtime: StudioRuntimeCoordinator,
    private val source: StudioRuntimeSource,
    private val load: suspend () -> StoreLoad,
    private val perform: suspend (
        StudioDocument,
        AcceptancePlan.Commit,
        RecoveryPurpose.Automatic,
        StudioApplyProgress?,
    ) -> AcceptanceResult,
    private val recover: suspend (String) -> AcceptanceResult,
    private val outcome: (String) -> AcceptanceResult?,
    private val retireOutcome: (String) -> Boolean,
) {
    private val remaining = mutableListOf<RotationCommand>()
    private var restoredFromJournal = false

    suspend fun execute(
        dispatch: RuntimeDispatch,
        onAdmission: () -> Unit,
    ): RotationTransition {
        val loaded = load()
        when {
            dispatch is RuntimeDispatch.Start -> {
                start(loaded, dispatch)
            }
            dispatch == RuntimeDispatch.Recover -> {
                recover(loaded)
            }
            loaded is StoreLoad.Ready && dispatch is RuntimeDispatch.ContextChange -> {
                context(loaded.document, dispatch)
            }
            loaded is StoreLoad.Ready && owns(loaded.document) -> {
                if (dispatch is RuntimeDispatch.NativeChange) onAdmission()
                val clock = source.clock()
                val operationId = source.operationId()
                val event =
                    when (dispatch) {
                        is RuntimeDispatch.Start, is RuntimeDispatch.ContextChange -> {
                            error("Startup and context were already dispatched")
                        }

                        is RuntimeDispatch.NativeChange -> RotationEvent.NativeChange(operationId, dispatch.change)

                        is RuntimeDispatch.Due -> {
                            RotationEvent.TimerDue(dispatch.ticket, operationId)
                        }

                        is RuntimeDispatch.Availability -> {
                            RotationEvent.AvailabilityObserved(dispatch.generation, dispatch.availability, operationId)
                        }

                        RuntimeDispatch.Recover -> {
                            error("Recovery was already dispatched")
                        }
                    }
                consume(runtime.accept(event, clock))
            }
        }
        return RotationTransition(runtime.state, StudioList(remaining))
    }

    private suspend fun context(
        document: StudioDocument,
        dispatch: RuntimeDispatch.ContextChange,
    ) {
        val before = runtime.snapshot()
        val clock = source.clock()
        val operationId = source.operationId()
        val event = RuntimePlans.contextEvent(document, dispatch, before, operationId) ?: return
        consume(runtime.accept(event, clock))
    }

    private suspend fun start(
        loaded: StoreLoad,
        observation: RuntimeDispatch.Start,
    ) {
        val clock = source.clock()
        val operationId = source.operationId()
        when (loaded) {
            is StoreLoad.Ready -> {
                consume(
                    runtime.initialize(runtime.project(loaded.document, observation), observation, operationId, clock),
                )
                StudioRotationProjection
                    .diagnostic(
                        loaded.document,
                    )?.let { remaining.add(RotationCommand.ReportFault(it)) }
            }

            is StoreLoad.Recovering -> {
                val purpose = loaded.journal.recovery.purpose as? RecoveryPurpose.Automatic
                if (purpose == null) {
                    consume(
                        runtime.initialize(unavailable("rotation-recovery-pending"), observation, operationId, clock),
                    )
                    return
                }
                if (!restoreJournal(loaded.journal, purpose, clock, observation)) return
                val result = withContext(NonCancellable) { recover(loaded.journal.token.transactionId) }
                consumeOutcome(loaded.journal.token.transactionId, result)
                val settled = load() as? StoreLoad.Ready ?: return
                if (operation() != null) return
                remaining.removeAll { it is RotationCommand.ArmSchedule }
                consume(
                    runtime.restore(
                        runtime.project(settled.document, observation),
                        source.operationId(),
                        source.clock(),
                    ),
                )
            }

            is StoreLoad.ReadOnly, StoreLoad.Empty -> {
                consume(runtime.initialize(unavailable("rotation-store-unavailable"), observation, operationId, clock))
            }
        }
    }

    private fun unavailable(code: String): RotationLoadOutcome.Unavailable =
        RotationLoadOutcome.Unavailable(RuntimeFault.DurableRecovery(code))

    private fun owns(document: StudioDocument): Boolean {
        val owner = runtime.snapshot().inputs.owner
        return runtime.isCurrentOwner(owner) &&
            StudioTargets.current(document, owner.target)?.revision == owner.appearanceRevision
    }

    private suspend fun consume(result: RotationTransition) {
        for (command in result.commands) {
            when (command) {
                is RotationCommand.RequestRandomColor -> {
                    consume(
                        runtime.accept(
                            RotationEvent.RandomColorChosen(command.operationId, source.randomColor(command.variant)),
                            source.clock(),
                        ),
                    )
                }

                is RotationCommand.PrepareStep -> {
                    step(command)
                }

                is RotationCommand.PersistCheckpoint -> {
                    schedule(command)
                }

                is RotationCommand.ArmSchedule, is RotationCommand.CancelSchedule, is RotationCommand.ReportFault -> {
                    remaining.add(command)
                }

                is RotationCommand.ApplyPreparedEffects, is RotationCommand.PublishAccepted,
                is RotationCommand.ResolveStoreOutcome, is RotationCommand.RestoreOwnedEffects,
                -> {
                    error("Transaction phase commands require their shared coordinator acknowledgement")
                }
            }
        }
    }

    private suspend fun step(command: RotationCommand.PrepareStep) {
        val latest = (load() as? StoreLoad.Ready)?.document ?: return
        val plan = plan(latest, command.checkpoint, listOf(EffectOwner.ACCENT)).copy(rotationStep = command.candidate)
        val progress =
            object : StudioApplyProgress {
                override fun prepared() {
                    runtime.accept(RotationEvent.Prepared(command.operationId), source.clock())
                }

                override fun settled(runtime: StudioRuntimeState): StudioRuntimeState {
                    val completed = source.clock()
                    this@StudioRuntimeExecution.runtime.accept(
                        RotationEvent.EffectsSettled(command.operationId, completed),
                        completed,
                    )
                    val pending =
                        checkNotNull(operation() as? RotationStepCommit) {
                            "Rotation effects could not produce a committable checkpoint"
                        }
                    return StudioContinuationPolicy.releaseAccent(runtime.copy(rotation = pending.checkpoint))
                }
            }
        transact(latest, plan, RecoveryPurpose.RotationStep(command.operationId), progress)
    }

    private suspend fun schedule(command: RotationCommand.PersistCheckpoint) {
        val native = command.operation as? RotationNativeCommit
        if (native != null) {
            applyNative(native)
            return
        }
        val context = command.operation as? RotationContextCommit
        if (context != null) {
            applyContext(context)
            return
        }
        check(command.operation is RotationScheduleCommit) {
            "Step persistence belongs to its prepared transaction"
        }
        val latest = (load() as? StoreLoad.Ready)?.document ?: return
        transact(
            latest,
            plan(latest, command.checkpoint, emptyList()),
            RecoveryPurpose.RotationSchedule(command.operationId),
            null,
        )
    }

    private suspend fun applyNative(operation: RotationNativeCommit) {
        val latest = (load() as? StoreLoad.Ready)?.document ?: return
        val owners = RuntimePlans.nativeOwners(operation.change)
        val selection = (operation.change as? StudioNativeChange.Theme)?.selection
        val projected = latest.copy(shared = StudioThemeHistory.record(latest.shared, selection))
        val prepared = RuntimePlans.nativePlan(plan(projected, operation.checkpoint, owners), operation)
        transact(latest, prepared, RecoveryPurpose.NativeChange(operation.operationId, operation.change), null)
    }

    private suspend fun applyContext(operation: RotationContextCommit) {
        val latest = (load() as? StoreLoad.Ready)?.document ?: return
        val continuity = operation.kind == RotationHandoffKind.CONTINUITY
        val projected =
            if (continuity) {
                StudioContinuationPolicy.transfer(latest, operation)
            } else {
                latest.copy(runtime = latest.runtime.copy(effectiveContinuation = null))
            }
        val appearance = operation.incoming.inputs.appearance
        val owners =
            if (continuity) {
                emptyList()
            } else {
                listOf(
                    EffectOwner.ACCENT,
                    EffectOwner.FONT,
                    EffectOwner.SYNTAX,
                    EffectOwner.VCS,
                    EffectOwner.GLOW,
                )
            }
        val prepared =
            plan(projected, operation.checkpoint, owners).copy(
                glowSelection =
                    if (continuity) {
                        StudioGlowSelection()
                    } else {
                        StudioGlowSelection(
                            fields = StudioList(appearance.glow.records.map { FieldAddress(it.key) }),
                        )
                    },
            )
        transact(latest, prepared, RecoveryPurpose.ContextHandoff(operation.operationId, operation.kind), null)
    }

    private fun plan(
        latest: StudioDocument,
        checkpoint: RotationRuntimeCheckpoint,
        owners: List<EffectOwner>,
    ): AcceptancePlan.Commit = RuntimePlans.checkpoint(latest, checkpoint, owners, source.transactionId())

    private suspend fun transact(
        latest: StudioDocument,
        plan: AcceptancePlan.Commit,
        purpose: RecoveryPurpose.Automatic,
        progress: StudioApplyProgress?,
    ) {
        val transactionId = plan.candidate.header.transactionId
        val result =
            try {
                perform(latest, plan, purpose, progress)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    val retained = outcome(transactionId)
                    if (retained != null) consumeOutcome(transactionId, retained)
                }
                throw cancelled
            }
        consumeOutcome(transactionId, result)
    }

    private suspend fun consumeOutcome(
        transactionId: String,
        result: AcceptanceResult,
    ) {
        val next = acknowledge(result)
        retireOutcome(transactionId)
        consume(next)
    }

    private fun acknowledge(result: AcceptanceResult): RotationTransition =
        when (result) {
            is AcceptanceResult.Accepted -> {
                accepted(null)
            }

            is AcceptanceResult.AcceptedCleanupPending -> {
                accepted(result.failure)
            }

            is AcceptanceResult.NotAccepted -> {
                restored(result.failure)
            }

            is AcceptanceResult.RecoveryRequired -> {
                unresolved(result.failure)
            }

            is AcceptanceResult.Blocked, is AcceptanceResult.ReviewRequired, AcceptanceResult.NoChanges -> {
                error("Automatic transactions return a qualified journal outcome")
            }
        }

    private fun accepted(failure: ApplyFailure?): RotationTransition {
        val pending = checkNotNull(operation())
        val clock = source.clock()
        when (pending) {
            is RotationOutcomeResolution -> {
                runtime.accept(
                    RotationEvent.CommitResolved(
                        pending.operationId,
                        RotationCommitOutcome.Readback(CommitResolution.Committed),
                        pending.completionId,
                    ),
                    clock,
                )
            }

            is RotationCheckpointCommit -> {
                runtime.accept(
                    RotationEvent.CommitResolved(pending.operationId, RotationCommitOutcome.Accepted),
                    clock,
                )
            }

            is RotationSettlement -> Unit

            else -> {
                error("Acceptance requires a completed checkpoint")
            }
        }
        if (restoredFromJournal && runtime.snapshot().checkpoint.schedule != null) {
            val retained = checkNotNull(operation() as? RotationSettlement)
            runtime.restore(RotationLoadOutcome.Settling(runtime.snapshot(), retained), retained.operationId, clock)
            restoredFromJournal = false
        }
        val settlement = checkNotNull(operation() as? RotationSettlement)
        val settled =
            if (failure ==
                null
            ) {
                RotationCommitOutcome.Settled
            } else {
                RotationCommitOutcome.AcceptedCleanupPending(
                    RotationSettlementProgress.CLEANUP_PENDING,
                    RuntimeFault.DurableRecovery(failure.code),
                )
            }
        return runtime.accept(
            RotationEvent.CommitResolved(settlement.operationId, settled, settlement.completionId),
            clock,
        )
    }

    private fun restored(failure: ApplyFailure): RotationTransition {
        val clock = source.clock()
        return when (val pending = checkNotNull(operation())) {
            is RotationStepPreparation -> {
                runtime.accept(
                    RotationEvent.Prepared(
                        pending.operationId,
                        RotationPreparationOutcome.RejectedRestored(source.operationId(), failure),
                    ),
                    clock,
                )
            }

            is RotationStepEffects, is RotationRestore -> {
                runtime.accept(
                    RotationEvent.EffectsSettled(
                        pending.operationId,
                        clock,
                        RotationEffectOutcome.RejectedRestored(source.operationId()),
                        pending.completionId,
                    ),
                    clock,
                )
            }

            is RotationScheduleCommit, is RotationContextCommit, is RotationNativeCommit -> {
                runtime.accept(
                    RotationEvent.CommitResolved(pending.operationId, RotationCommitOutcome.RejectedRestored),
                    clock,
                )
            }

            is RotationStepCommit -> {
                runtime.accept(
                    RotationEvent.CommitResolved(
                        pending.operationId,
                        RotationCommitOutcome.NotCommitted(failure),
                    ),
                    clock,
                )
                restored(failure)
            }

            is RotationOutcomeResolution -> {
                runtime.accept(
                    RotationEvent.CommitResolved(
                        pending.operationId,
                        RotationCommitOutcome.Readback(CommitResolution.NotCommitted),
                        pending.completionId,
                    ),
                    clock,
                )
                restored(failure)
            }

            else -> {
                error("Restoration requires preaccept transaction evidence")
            }
        }
    }

    private fun unresolved(failure: ApplyFailure): RotationTransition {
        val pending = checkNotNull(operation())
        val clock = source.clock()
        return when (pending) {
            is RotationStepEffects, is RotationRestore -> {
                runtime.accept(
                    RotationEvent.EffectsSettled(
                        pending.operationId,
                        clock,
                        RotationEffectOutcome.RecoveryPending(failure),
                        pending.completionId,
                    ),
                    clock,
                )
            }

            is RotationStepPreparation -> {
                runtime.accept(
                    RotationEvent.Prepared(
                        pending.operationId,
                        RotationPreparationOutcome.OutcomeUnknown(failure),
                    ),
                    clock,
                )
                unresolved(failure)
            }

            is RotationCheckpointCommit -> {
                runtime.accept(
                    RotationEvent.CommitResolved(
                        pending.operationId,
                        RotationCommitOutcome.OutcomeUnknown(failure),
                    ),
                    clock,
                )
                unresolved(failure)
            }

            is RotationOutcomeResolution -> {
                runtime.accept(
                    RotationEvent.CommitResolved(
                        pending.operationId,
                        RotationCommitOutcome.Readback(CommitResolution.Unknown),
                        pending.completionId,
                    ),
                    clock,
                )
            }

            else -> {
                error("Unresolved recovery requires preaccept transaction evidence")
            }
        }
    }

    private fun operation(): RotationOperation? =
        when (val state = runtime.state) {
            is RotationRuntimeState.Applying -> state.operation
            is RotationRuntimeState.Faulted.Loaded -> state.pendingOperation
            else -> null
        }

    private suspend fun recover(loaded: StoreLoad) {
        val unloaded = runtime.state as? RotationRuntimeState.Faulted.Unloaded
        if (unloaded != null) {
            val observation = unloaded.observation ?: return
            start(loaded, RuntimeDispatch.Start(observation.generation, observation.availability))
            return
        }
        if (loaded !is StoreLoad.Recovering) {
            if (loaded is StoreLoad.Ready && runtime.state is RotationRuntimeState.Faulted.Loaded) {
                consume(runtime.accept(RotationEvent.RetryRecovery(source.operationId()), source.clock()))
            }
            return
        }
        val purpose = loaded.journal.recovery.purpose as? RecoveryPurpose.Automatic ?: return
        val clock = source.clock()
        if (operation()?.operationId == purpose.operationId) {
            runtime.accept(RotationEvent.RetryRecovery(source.operationId()), clock)
        } else {
            if (!restoreJournal(loaded.journal, purpose, clock)) return
        }
        val result = withContext(NonCancellable) { recover(loaded.journal.token.transactionId) }
        consumeOutcome(loaded.journal.token.transactionId, result)
    }

    private fun restoreJournal(
        journal: PendingJournal,
        purpose: RecoveryPurpose.Automatic,
        clock: ClockSample,
        observation: RuntimeDispatch.Start? = null,
    ): Boolean {
        restoredFromJournal = true
        val base = checkNotNull(journal.base)
        val decoded = StudioStateCodec.decode(base.toByteArray())
        val previous = checkNotNull(decoded as? DecodeResult.Ready).document
        val checkpoint = checkNotNull(journal.candidate.runtime.rotation)
        val oldCheckpoint = previous.runtime.rotation
        if (oldCheckpoint == null) {
            remaining.addAll(
                runtime
                    .restore(
                        unavailable("rotation-recovery-checkpoint-missing"),
                        purpose.operationId,
                        clock,
                    ).commands,
            )
            return false
        }
        val snapshot =
            if (observation == null) {
                runtime.snapshot().copy(checkpoint = oldCheckpoint)
            } else {
                when (val projected = runtime.project(previous, observation, oldCheckpoint.owner.target)) {
                    is RotationLoadOutcome.Ready -> projected.snapshot
                    else -> {
                        remaining.addAll(
                            runtime.initialize(projected, observation, purpose.operationId, clock).commands,
                        )
                        return false
                    }
                }
            }
        val pending: RotationJournalOperation =
            when (purpose) {
                is RecoveryPurpose.RotationSchedule -> {
                    RotationScheduleCommit(purpose.operationId, checkpoint, clock)
                }

                is RecoveryPurpose.NativeChange -> {
                    RotationNativeCommit(purpose.operationId, checkpoint, clock, snapshot, purpose.change)
                }

                is RecoveryPurpose.ContextHandoff -> {
                    val current = requireNotNull(StudioTargets.current(journal.candidate, checkpoint.owner.target))
                    val inputs = snapshot.inputs.copy(owner = checkpoint.owner, appearance = current.appearance)
                    RotationContextCommit(
                        purpose.operationId,
                        checkpoint,
                        clock,
                        snapshot,
                        snapshot.copy(inputs = inputs),
                        purpose.kind,
                        accent =
                            journal.candidate.runtime.effectiveContinuation
                                ?.accent,
                    )
                }

                is RecoveryPurpose.RotationStep -> {
                    val candidate = checkNotNull(journal.recovery.rotationStep)
                    if (checkpoint.successfulStepSerial > oldCheckpoint.successfulStepSerial) {
                        RotationStepCommit(purpose.operationId, checkpoint, clock, candidate)
                    } else {
                        RotationStepEffects(purpose.operationId, candidate)
                    }
                }
            }
        runtime.restore(
            RotationLoadOutcome.Recovering(
                (pending as? RotationContextCommit)?.incoming ?: snapshot,
                RotationOutcomeResolution(pending, ApplyFailure("interrupted")),
            ),
            purpose.operationId,
            clock,
        )
        return true
    }
}

/** Pure projections of admitted native/context work; execution retains all clocks, IDs, and persistence. */
private object RuntimePlans {
    fun checkpoint(
        latest: StudioDocument,
        checkpoint: RotationRuntimeCheckpoint,
        owners: List<EffectOwner>,
        transactionId: String,
    ): AcceptancePlan.Commit =
        AcceptancePlan.Commit(
            candidate =
                latest.copy(
                    header =
                        latest.header.copy(
                            revision = latest.header.revision + 1,
                            transactionId = transactionId,
                        ),
                    runtime = latest.runtime.copy(rotation = checkpoint),
                ),
            effects = StudioList(owners),
            lease = null,
            limitations = StudioList(emptyList()),
            nativeChoices = StudioList(emptyList()),
            glowSelection = StudioGlowSelection(),
        )

    fun contextEvent(
        document: StudioDocument,
        dispatch: RuntimeDispatch.ContextChange,
        before: RotationSnapshot,
        operationId: UUID,
    ): RotationEvent? {
        val accepted =
            if (dispatch is RuntimeDispatch.ContinuityTransfer) {
                require(dispatch.focus.target == StudioTarget.Global)
                val outgoing = StudioTargets.current(document, before.inputs.owner.target) ?: return null
                outgoing.copy(revision = document.currents.global.revision + 1)
            } else {
                StudioTargets.current(document, dispatch.focus.target) ?: return null
            }
        val availability =
            if (dispatch is RuntimeDispatch.ContinuityTransfer) before.inputs.availability else dispatch.availability
        val inputs =
            RuntimeInputs(
                RuntimeOwner(dispatch.focus.target, accepted.revision),
                accepted.appearance,
                availability.copy(
                    effectiveFollowSystem =
                        StudioAccentPolicy.followsSystem(
                            accepted.appearance,
                            document.shared.systemAccentPolicy,
                        ),
                ),
            )
        return when (dispatch) {
            is RuntimeDispatch.FocusChanged ->
                RotationEvent.FocusChanged(
                    operationId,
                    dispatch.focus.generation,
                    inputs,
                )
            is RuntimeDispatch.ContinuityTransfer ->
                RotationEvent.ContinuityTransfer(
                    operationId,
                    dispatch.focus.generation,
                    inputs,
                    dispatch.accent,
                )
        }
    }

    fun nativeOwners(change: StudioNativeChange): List<EffectOwner> =
        buildList {
            if (change is StudioNativeChange.Theme) add(EffectOwner.SYSTEM)
            add(EffectOwner.ACCENT)
            if (change != StudioNativeChange.AccentContext) {
                addAll(listOf(EffectOwner.FONT, EffectOwner.SYNTAX, EffectOwner.VCS))
            }
            add(EffectOwner.GLOW)
            if (change != StudioNativeChange.AccentContext) add(EffectOwner.WORKSPACE)
        }

    fun nativePlan(
        prepared: AcceptancePlan.Commit,
        operation: RotationNativeCommit,
    ): AcceptancePlan.Commit {
        val isAccentOnly = operation.change == StudioNativeChange.AccentContext
        val appearance = operation.before.inputs.appearance
        return prepared.copy(
            themeChange = (operation.change as? StudioNativeChange.Theme)?.selection,
            glowSelection =
                if (isAccentOnly) {
                    StudioGlowSelection(
                        dependencies = StudioList(listOf(GlowDependency.ACCENT)),
                        shouldReclassifyLanguage = true,
                    )
                } else {
                    StudioGlowSelection(fields = StudioList(appearance.glow.records.map { FieldAddress(it.key) }))
                },
            workspaceFields =
                StudioList(
                    if (isAccentOnly) {
                        emptyList()
                    } else {
                        prepared.candidate.shared.workspace.records
                            .map { FieldAddress(it.key) }
                    },
                ),
        )
    }
}
