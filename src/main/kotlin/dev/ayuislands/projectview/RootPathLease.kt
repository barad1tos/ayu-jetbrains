package dev.ayuislands.projectview

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.RegistryValue
import com.intellij.util.concurrency.annotations.RequiresEdt
import dev.ayuislands.accent.AccentApplyPlanRunner
import dev.ayuislands.accent.AccentCheckpoint
import dev.ayuislands.accent.AccentWriteCheckpoint
import dev.ayuislands.accent.AccentWriteState
import dev.ayuislands.settings.AyuIslandsState
import javax.swing.SwingUtilities

@Service(Service.Level.APP)
internal class RootPathLease {
    private var ownership: RootPathOwner? = null
    private var hold: RootPathHold? = null

    /** Defers a producer's refresh so it can reevaluate current settings after settlement. */
    @RequiresEdt
    fun deferIfHeld(action: () -> Unit): Boolean {
        val checkpoint = hold ?: return false
        checkpoint.defer(action)
        return true
    }

    private fun execute(
        plan: RootPathPlan,
        hidePath: Boolean,
        settings: AyuIslandsState,
        write: (RootPathWrite) -> Unit,
    ) {
        if (plan.foundDrift) LOG.warn("Project view Registry value changed outside Ayu; preserving the manual value")
        // Acquisition retains its baseline before the first setter; release clears it only after success.
        if (hidePath) restoreState(plan.after, settings)
        plan.write?.let(write)
        if (!hidePath) restoreState(plan.after, settings)
    }

    private fun observe(
        registryValue: RegistryValue,
        settings: AyuIslandsState,
    ): RootPathState = RootPathState(nativeValue(registryValue), ownership, metadata(settings))

    private fun nativeValue(registryValue: RegistryValue): RootPathValue =
        RootPathValue(registryValue.asString(), registryValue.isChangedFromDefault())

    private fun metadata(settings: AyuIslandsState): RootPathMetadata =
        RootPathMetadata(settings.hasRootPathLease, settings.wasRootPathShown, settings.wasRootPathChanged)

    private fun restoreState(
        snapshot: RootPathState,
        settings: AyuIslandsState,
    ) {
        ownership = snapshot.owner
        restoreMetadata(snapshot.metadata, settings)
    }

    private fun restoreMetadata(
        snapshot: RootPathMetadata,
        settings: AyuIslandsState,
    ) {
        settings.hasRootPathLease = snapshot.hasLease
        settings.wasRootPathShown = snapshot.wasShown
        settings.wasRootPathChanged = snapshot.wasChanged
    }

    /** Captures immutable native and lease evidence without acquiring a hold or changing settings. */
    @RequiresEdt
    fun prepare(
        projects: Set<Project>,
        registryValue: RegistryValue,
        hidePath: Boolean,
        detachedState: AyuIslandsState,
    ): RootPathCheckpoint {
        check(SwingUtilities.isEventDispatchThread()) { "Root path capture must run on EDT" }
        check(hold == null) { "Root path recovery must settle before capture" }
        return RootPathCheckpoint(projects.toSet(), registryValue, hidePath, detachedState)
    }

    /** Rebinds an outer receipt after its process/key qualification, without replaying candidate settings. */
    @RequiresEdt
    fun recoverReceipt(
        registryValue: RegistryValue,
        before: RootPathValue,
        lastWritten: RootPathValue,
    ): RootPathRecovery? {
        check(SwingUtilities.isEventDispatchThread()) { "Root path recovery capture must run on EDT" }
        if (!isRecoveryAvailable()) return null
        return RootPathRecovery(registryValue, before, lastWritten)
    }

    private fun isRecoveryAvailable(checkpoint: RootPathHold? = null): Boolean =
        ownership == null && (hold == null || hold === checkpoint)

    /** Holds native and membership rollback until the parent accepts the detached runtime fields. */
    internal inner class RootPathCheckpoint(
        projects: Set<Project>,
        registry: RegistryValue,
        private val hidePath: Boolean,
        private val detachedState: AyuIslandsState,
    ) : RootPathHold(registry),
        AccentWriteCheckpoint {
        private val baseline = observe(registryValue, detachedState)
        private val plan = RootPathPolicy.plan(baseline, projects, hidePath)
        private var expected = baseline
        val before: RootPathValue = baseline.native
        val planned: RootPathWrite? = plan.write
        val lastWritten: RootPathValue
            get() = expected.native
        override var state: AccentWriteState = AccentWriteState.PREPARED
            private set

        override val hasPending: Boolean
            get() = state == AccentWriteState.OWNED

        override fun apply(): Boolean {
            check(SwingUtilities.isEventDispatchThread()) { "Root path writes must run on EDT" }
            check(state == AccentWriteState.PREPARED) { "Root path application was already started" }
            if (hold != null || !matches()) {
                state = AccentWriteState.RELINQUISHED
                return false
            }
            hold = this
            state = AccentWriteState.OWNED
            try {
                execute(plan, hidePath, detachedState, ::write)
            } finally {
                expected = expected.copy(owner = ownership, metadata = metadata(detachedState))
            }
            return permitsWrite()
        }

        override fun restore(blockedBy: List<AccentCheckpoint>) {
            check(SwingUtilities.isEventDispatchThread()) { "Root path restoration must run on EDT" }
            if (state == AccentWriteState.PREPARED) {
                state = AccentWriteState.RESTORED
                return
            }
            if (!hasPending) return
            checkBlockers(blockedBy)
            if (!permitsWrite()) return
            restoreState(baseline, detachedState)
            expected = expected.copy(owner = ownership, metadata = baseline.metadata)
            write(if (before.wasChanged) RootPathWrite.Set(before.raw) else RootPathWrite.Reset)
            if (permitsWrite()) {
                check(expected.native == before) { "Root path restoration did not settle" }
                state = AccentWriteState.RESTORED
                releaseResources()
            }
        }

        override fun commit() {
            check(SwingUtilities.isEventDispatchThread()) { "Root path acceptance must run on EDT" }
            if (hasPending && !permitsWrite()) return
            if (state != AccentWriteState.RELINQUISHED) state = AccentWriteState.RESTORED
            releaseResources()
        }

        private fun write(change: RootPathWrite) {
            if (RootPathPolicy.matches(change, nativeValue(registryValue))) return
            val previous = expected.native
            try {
                when (change) {
                    is RootPathWrite.Set -> registryValue.setValue(change.raw)
                    RootPathWrite.Reset -> registryValue.resetToDefault()
                }
            } finally {
                val observed = nativeValue(registryValue)
                if (RootPathPolicy.matches(change, observed) || observed == previous) {
                    expected = expected.copy(native = observed)
                }
            }
        }

        private fun matches(): Boolean = observe(registryValue, detachedState) == expected

        private fun permitsWrite(): Boolean {
            if (!hasPending) return false
            if (hold !== this || !matches()) {
                if (ownership == expected.owner) ownership = baseline.owner?.copy(isDrifted = true)
                if (metadata(detachedState) == expected.metadata) restoreMetadata(baseline.metadata, detachedState)
                state = AccentWriteState.RELINQUISHED
                releaseResources()
            }
            return hasPending
        }
    }

    /** A recovered receipt restores only its exact native cell; project membership belongs to the new process. */
    internal inner class RootPathRecovery(
        registry: RegistryValue,
        val before: RootPathValue,
        initialWritten: RootPathValue,
    ) : RootPathHold(registry) {
        var lastWritten: RootPathValue = initialWritten
            private set
        override var state: AccentWriteState = AccentWriteState.PREPARED
            private set

        override val hasPending: Boolean
            get() = state == AccentWriteState.PREPARED || state == AccentWriteState.OWNED

        override fun restore(blockedBy: List<AccentCheckpoint>) {
            check(SwingUtilities.isEventDispatchThread()) { "Root path receipt restoration must run on EDT" }
            if (!hasPending) return
            checkBlockers(blockedBy)
            check(isRecoveryAvailable(this)) { "Root path receipt recovery is blocked by a current lease" }
            when (
                RootPathPolicy.resolveRecovery(
                    before,
                    lastWritten,
                    nativeValue(registryValue),
                )
            ) {
                RootPathRestore.FOREIGN -> {
                    state = AccentWriteState.RELINQUISHED
                    releaseResources()
                }
                RootPathRestore.RESTORED -> {
                    lastWritten = before
                    state = AccentWriteState.RESTORED
                    releaseResources()
                }
                RootPathRestore.WRITE -> {
                    hold = this
                    state = AccentWriteState.OWNED
                    restoreNative()
                }
            }
        }

        override fun commit() {
            check(SwingUtilities.isEventDispatchThread()) { "Root path receipt acceptance must run on EDT" }
            if (state != AccentWriteState.RELINQUISHED) state = AccentWriteState.RESTORED
            releaseResources()
        }

        private fun restoreNative() {
            val previous = lastWritten
            try {
                if (before.wasChanged) registryValue.setValue(before.raw) else registryValue.resetToDefault()
            } finally {
                val observed = nativeValue(registryValue)
                if (observed == before) {
                    lastWritten = before
                } else if (observed != previous) {
                    state = AccentWriteState.RELINQUISHED
                    releaseResources()
                }
            }
            if (state == AccentWriteState.RELINQUISHED) return
            check(lastWritten == before) { "Root path receipt restoration did not settle" }
            state = AccentWriteState.RESTORED
            releaseResources()
        }
    }

    /** The one lease hold and ordered lifecycle queue are shared by live writes and receipt recovery. */
    internal abstract inner class RootPathHold(
        protected val registryValue: RegistryValue,
    ) : AccentCheckpoint {
        private val deferred = mutableListOf<() -> Unit>()
        val registryKey: String = registryValue.key
        abstract val state: AccentWriteState

        override fun releaseResources() {
            if (hasPending) return
            for (action in deferred.toList()) {
                AccentApplyPlanRunner.post { action() }
                deferred.remove(action)
            }
            if (hold === this) hold = null
        }

        override fun retain() {
            check(state != AccentWriteState.OWNED || hold === this) { "Root path recovery lost its hold" }
        }

        internal fun defer(action: () -> Unit) {
            deferred += action
        }

        protected fun checkBlockers(blockedBy: List<AccentCheckpoint>) {
            check(blockedBy.none { it is RootPathHold && it.registryKey == registryKey && it.hasPending }) {
                "Root path restoration is blocked by a newer write"
            }
        }
    }

    companion object {
        fun getInstance(): RootPathLease = service()

        private val LOG = logger<RootPathLease>()
    }
}
