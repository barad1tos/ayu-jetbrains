package dev.ayuislands.studio.runtime

import com.intellij.openapi.project.Project
import dev.ayuislands.rotation.RotationCommand
import dev.ayuislands.rotation.RotationRuntimeState
import dev.ayuislands.rotation.RotationTicket
import dev.ayuislands.rotation.RotationTransition
import dev.ayuislands.rotation.RuntimeAvailability
import dev.ayuislands.rotation.RuntimeFault
import dev.ayuislands.rotation.RuntimeOwner
import dev.ayuislands.studio.model.StudioAccentContinuation
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioNativeChange
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.StudioThemeChange
import dev.ayuislands.studio.session.SessionScheduler
import dev.ayuislands.studio.session.StudioApplyCoordinator
import dev.ayuislands.studio.session.StudioNativeObservation
import dev.ayuislands.studio.session.StudioTargets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Both projections describe one native observation; no accepted or native state is cached here. */
internal data class StudioRuntimeObservation(
    val native: StudioNativeObservation,
    val availability: RuntimeAvailability,
)

/** Application-owned native callbacks share the existing coordinator and its single rotation timer. */
internal class StudioRuntimeBinding(
    private val runtime: StudioRuntimeCoordinator,
    private val coordinator: StudioApplyCoordinator,
    private val source: StudioRuntimeSource,
    private val observe: () -> StudioRuntimeObservation?,
    private val accepted: () -> StudioDocument,
    scope: CoroutineScope,
    scheduler: SessionScheduler,
    private val onFault: (RuntimeFault) -> Unit,
) : AutoCloseable {
    private val lifetime = SupervisorJob(scope.coroutineContext[Job])
    private val ownerScope = CoroutineScope(scope.coroutineContext + lifetime)
    private val timer =
        StudioRotationTimer(scheduler) { ticket ->
            dueJob = due(ticket)
        }
    private var dueJob: Job? = null
    private var observationGeneration = 0L
    private val completed = Job().apply { complete() }
    private var pendingContext: RuntimeDispatch.ContextChange? = null
    private var closing: ClosingContext? = null
    private val explicitNative = ArrayDeque<PendingNative>()
    private var observedNative: PendingNative? = null
    private var nativeJob: Job? = null
    private var isStarted = false
    private var isClosed = false

    fun start(): Job {
        if (isClosed || isStarted) return completed
        val observed = observe() ?: return unavailable()
        isStarted = true
        runtime.observeFocus(observed.native.focus.runtime)
        return dispatch(RuntimeDispatch.Start(nextObservation(), observed.availability))
    }

    /** The focus is observed before waiting on admission, so an older in-flight lease becomes stale immediately. */
    fun focusChanged(): Job {
        if (isClosed) return completed
        val observed = observe() ?: return unavailable()
        return focusChanged(observed)
    }

    /** A real window activation also refreshes the accepted project's existing Accent integrations. */
    fun windowActivated(): Job {
        if (isClosed) return completed
        val observed = observe() ?: return unavailable()
        val snapshot = (runtime.state as? RotationRuntimeState.Loaded)?.snapshot
        val focus = observed.native.focus.runtime
        return if (snapshot != null &&
            focus.target == snapshot.inputs.owner.target &&
            focus.generation == snapshot.focusGeneration
        ) {
            nativeChange(StudioNativeChange.AccentContext)
        } else {
            focusChanged(observed)
        }
    }

    private fun focusChanged(observed: StudioRuntimeObservation): Job {
        val focus = observed.native.focus.runtime
        runtime.observeFocus(focus)
        discardSettledContext()
        val generation = (runtime.state as? RotationRuntimeState.Loaded)?.snapshot?.focusGeneration
        if (generation != null && focus.generation <= generation) {
            return if (observed.availability != runtime.snapshot().inputs.availability) {
                dispatch(RuntimeDispatch.Availability(nextObservation(), observed.availability))
            } else {
                completed
            }
        }
        val outgoing = closing
        val owner = (runtime.state as? RotationRuntimeState.Loaded)?.snapshot?.inputs?.owner
        pendingContext =
            if (focus.target == StudioTarget.Global && outgoing != null && outgoing.owner == owner) {
                RuntimeDispatch.ContinuityTransfer(focus, observed.availability, outgoing.accent)
            } else {
                RuntimeDispatch.FocusChanged(focus, observed.availability)
            }
        closing = null
        return ownerScope.launch(start = CoroutineStart.UNDISPATCHED) { drainContext() }
    }

    /** Capture provenance while the outgoing native project still exists; later disposal requires no native reread. */
    fun projectClosing(project: Project) {
        if (isClosed) return
        val observed = observe()?.native ?: return
        val snapshot = (runtime.state as? RotationRuntimeState.Loaded)?.snapshot ?: return
        val owner = snapshot.inputs.owner
        if (observed.focus.runtime.generation != snapshot.focusGeneration) return
        if (observed.focus.project !== project || observed.focus.runtime.target != owner.target) return
        if (!runtime.isCurrentOwner(owner)) return
        val current = StudioTargets.current(accepted(), owner.target) ?: return
        if (current.revision != owner.appearanceRevision) return
        val resolved = observed.accent
        val accent =
            resolved?.focused?.takeIf { resolved.focusedProject === project }?.let { color ->
                StudioAccentContinuation(observed.variant, color, resolved.dependencies)
            }
        closing = ClosingContext(owner, accent)
    }

    fun retry(): Job = dispatch(RuntimeDispatch.Recover)

    fun selectTheme(name: String): Job = nativeChange(StudioNativeChange.Theme(StudioThemeChange.Explicit(name)))

    fun systemAppearanceChanged(): Job = nativeChange(StudioNativeChange.Theme(StudioThemeChange.FollowSystem))

    fun appearanceChanged(): Job = nativeChange(StudioNativeChange.Appearance)

    fun accentContextChanged(): Job = nativeChange(StudioNativeChange.AccentContext)

    /** Shared acceptance/recovery publishes its existing timer commands here after durable settlement. */
    fun consume(transition: RotationTransition) {
        if (isClosed) return
        consumeCommands(transition)
        if (pendingContext != null) ownerScope.launch(start = CoroutineStart.UNDISPATCHED) { drainContext() }
        resumePending()
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        pendingContext = null
        closing = null
        explicitNative.clear()
        observedNative = null
        timer.dispose()
        lifetime.cancel()
    }

    private fun dispatch(request: RuntimeDispatch): Job {
        if (isClosed) return completed
        return ownerScope.launch(start = CoroutineStart.UNDISPATCHED) {
            consumeCommands(coordinator.executeRuntime(runtime, request, source))
            drainContext()
            resumePending().join()
        }
    }

    private suspend fun drainContext() {
        while (!isClosed) {
            discardSettledContext()
            val pending = pendingContext ?: return
            if (observe() == null || !runtime.qualifies(pending)) return
            val transition = coordinator.executeRuntime(runtime, pending, source)
            val generation = (runtime.state as? RotationRuntimeState.Loaded)?.snapshot?.focusGeneration
            if (generation != null && generation >= pending.focus.generation && pendingContext === pending) {
                pendingContext = null
            }
            consumeCommands(transition)
            if (pendingContext === pending) return
        }
    }

    private fun consumeCommands(transition: RotationTransition) {
        if (isClosed) return
        discardSettledContext()
        for (command in transition.commands) {
            when (command) {
                is RotationCommand.ArmSchedule -> if (pendingContext == null) timer.arm(command.ticket)
                is RotationCommand.CancelSchedule -> timer.cancel(command.ticket)
                is RotationCommand.ReportFault -> onFault(command.fault)
                else -> error("Native binding received an unsettled runtime command")
            }
        }
    }

    private fun nativeChange(change: StudioNativeChange): Job {
        if (isClosed) return completed
        val pending = PendingNative(change)
        if (change is StudioNativeChange.Theme && change.selection is StudioThemeChange.Explicit) {
            explicitNative.addLast(pending)
        } else {
            val retained = observedNative?.change
            val strongest =
                when {
                    retained is StudioNativeChange.Theme -> retained
                    change is StudioNativeChange.Theme -> change
                    retained == StudioNativeChange.Appearance -> retained
                    else -> change
                }
            observedNative = PendingNative(strongest)
        }
        return resumePending()
    }

    /** Metadata settlement releases admission without manufacturing a new native observation or timer event. */
    fun resumePending(): Job {
        if (isClosed || explicitNative.isEmpty() && observedNative == null) return completed
        nativeJob?.takeIf { it.isActive }?.let { return it }
        return ownerScope.launch(start = CoroutineStart.UNDISPATCHED) { drainNative() }.also { nativeJob = it }
    }

    private suspend fun drainNative() {
        while (!isClosed) {
            drainContext()
            if (pendingContext != null) return
            val pending = explicitNative.firstOrNull() ?: observedNative ?: return
            val observed = observe()
            if (observed == null) {
                val availability = unavailableRequest() ?: return
                consumeCommands(coordinator.executeRuntime(runtime, availability, source))
                return
            }
            val loaded = runtime.state as? RotationRuntimeState.Loaded ?: return
            if (observed.native.focus.runtime.generation > loaded.snapshot.focusGeneration) {
                focusChanged(observed).join()
                continue
            }
            var wasAdmitted = false
            val transition =
                coordinator.executeRuntime(
                    runtime,
                    RuntimeDispatch.NativeChange(observed.native.focus.runtime, pending.change),
                    source,
                    onAdmission = { wasAdmitted = true },
                )
            if (!wasAdmitted) return
            if (explicitNative.firstOrNull() === pending) explicitNative.removeFirst()
            if (observedNative === pending) observedNative = null
            val reconciled = reconcileAvailability()
            consumeCommands(transition)
            if (reconciled != null) consumeCommands(reconciled)
        }
    }

    private suspend fun reconcileAvailability(): RotationTransition? {
        val actual = observe() ?: return null
        if (runtime.state is RotationRuntimeState.Applying) return null
        if (actual.availability == runtime.snapshot().inputs.availability) return null
        return coordinator.executeRuntime(
            runtime,
            RuntimeDispatch.Availability(nextObservation(), actual.availability),
            source,
        )
    }

    private fun due(ticket: RotationTicket): Job {
        if (isClosed) return completed
        val observed = observe() ?: return unavailable()
        if (observed.native.focus.runtime.generation > runtime.snapshot().focusGeneration) {
            return focusChanged(observed)
        }
        if (observed.availability != runtime.snapshot().inputs.availability) {
            return dispatch(RuntimeDispatch.Availability(nextObservation(), observed.availability))
        }
        return dispatch(RuntimeDispatch.Due(ticket))
    }

    private fun unavailable(): Job {
        val request = unavailableRequest() ?: return completed
        return dispatch(request)
    }

    private fun unavailableRequest(): RuntimeDispatch.Availability? {
        val loaded = runtime.state as? RotationRuntimeState.Loaded ?: return null
        val waiting = runtime.state as? RotationRuntimeState.Waiting
        if (waiting != null) timer.cancel(waiting.ticket)
        dueJob?.cancel()
        if (!loaded.snapshot.inputs.availability.targetValid) return null
        return RuntimeDispatch.Availability(
            nextObservation(),
            loaded.snapshot.inputs.availability
                .copy(targetValid = false),
        )
    }

    private fun nextObservation(): Long {
        val recorded = (runtime.state as? RotationRuntimeState.Loaded)?.snapshot?.observationGeneration ?: 0
        observationGeneration = Math.incrementExact(maxOf(observationGeneration, recorded))
        return observationGeneration
    }

    private fun discardSettledContext() {
        val pending = pendingContext ?: return
        val generation = (runtime.state as? RotationRuntimeState.Loaded)?.snapshot?.focusGeneration ?: return
        if (pending.focus.generation <= generation) pendingContext = null
    }

    private data class PendingNative(
        val change: StudioNativeChange,
    )

    private data class ClosingContext(
        val owner: RuntimeOwner,
        val accent: StudioAccentContinuation?,
    )
}
