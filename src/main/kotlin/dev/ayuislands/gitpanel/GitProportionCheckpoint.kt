package dev.ayuislands.gitpanel

import com.intellij.ide.util.PropertiesComponent
import dev.ayuislands.accent.AccentWriteState
import dev.ayuislands.accent.combineUiFailures
import javax.swing.SwingUtilities

/** One operation-local application property shared by all splitters using its observed key. */
internal class GitProportionCheckpoint private constructor(
    val key: String,
    private val properties: PropertiesComponent,
    private val before: String?,
    expectedOutcomes: Set<String?>?,
    private val isRecovery: Boolean,
) {
    constructor(
        key: String,
        properties: PropertiesComponent = PropertiesComponent.getInstance(),
    ) : this(key, properties, properties.getValue(key), null, false)

    private val plans = mutableListOf<GitProportionPlan>()
    private val observations = mutableListOf<GitProportionObservation>()
    private var expected = expectedOutcomes?.toSet() ?: setOf(before)
    private var isRestorePrepared = false
    private var state = if (isRecovery) AccentWriteState.OWNED else AccentWriteState.PREPARED
    val record: GitProportionRecord
        get() = GitProportionRecord(key, before, plans.toList(), observations.toList(), isRestorePrepared, state)

    init {
        require(key.isNotEmpty() && expected.isNotEmpty()) { "Git proportion evidence is incomplete" }
        checkEdt()
    }

    internal fun plan(
        beforeBits: Int,
        plannedBits: Int,
    ): GitProportionPlan {
        checkEdt()
        check(!isRecovery && state == AccentWriteState.PREPARED && !isRestorePrepared)
        return GitProportionPlan(plans.size, beforeBits, plannedBits).also(plans::add)
    }

    internal fun matchesCurrent(): Boolean =
        PropertiesComponent.getInstance() === properties && properties.getValue(key) in expected

    internal fun permitsWrite(): Boolean {
        if (state != AccentWriteState.PREPARED && state != AccentWriteState.OWNED) return false
        if (!matchesCurrent()) state = AccentWriteState.RELINQUISHED
        return state != AccentWriteState.RELINQUISHED
    }

    internal fun write(
        plan: GitProportionPlan,
        restoring: Boolean,
        action: () -> Unit,
    ): Boolean {
        checkEdt()
        check(plans.getOrNull(plan.index) === plan) { "Git proportion plan belongs to another capture" }
        check(!isRecovery && restoring == isRestorePrepared) { "Git proportion write has no prepared intent" }
        val outcomes = if (restoring) plan.rollbackOutcomes else plan.forwardOutcomes
        return perform(plan.index, restoring, outcomes, action)
    }

    /** The outer owner acknowledges the returned facts before any virtual rollback setter. */
    fun prepareRestore(): GitProportionRecord {
        checkEdt()
        isRestorePrepared = true
        return record
    }

    /** Run only after every dependent geometry has settled; never rebinds or resizes a native peer. */
    fun restore(blockedBy: List<GitProportionCheckpoint> = emptyList()) {
        checkEdt()
        if (state == AccentWriteState.RESTORED || state == AccentWriteState.RELINQUISHED) return
        check(isRestorePrepared) { "Git proportion restoration needs acknowledged preparation" }
        check(blockedBy.none { it !== this && it.properties === properties && it.key == key && it.isPending() }) {
            "Git proportion restoration is blocked by a newer write"
        }
        if (PropertiesComponent.getInstance() === properties && properties.getValue(key) == before) {
            state = AccentWriteState.RESTORED
            return
        }
        if (!permitsWrite()) return
        if (perform(null, true, setOf(before)) {
                if (before == null) properties.unsetValue(key) else properties.setValue(key, before)
            }
        ) {
            state = AccentWriteState.RESTORED
        }
    }

    fun commit() {
        checkEdt()
        if (state == AccentWriteState.RESTORED || state == AccentWriteState.RELINQUISHED) return
        if (permitsWrite()) state = AccentWriteState.RESTORED
    }

    private fun isPending(): Boolean = state == AccentWriteState.OWNED

    private fun perform(
        planIndex: Int?,
        restoring: Boolean,
        outcomes: Set<String?>,
        action: () -> Unit,
    ): Boolean {
        if (!permitsWrite()) return false
        state = AccentWriteState.OWNED
        expected = expected + outcomes
        var failure: Throwable? = null
        try {
            action()
        } catch (exception: RuntimeException) {
            failure = exception
        }
        try {
            observe(planIndex, restoring)
        } catch (exception: RuntimeException) {
            failure = combineUiFailures(failure, exception)
        }
        failure?.let { throw it }
        return state == AccentWriteState.OWNED
    }

    private fun observe(
        planIndex: Int?,
        restoring: Boolean,
    ) {
        if (PropertiesComponent.getInstance() !== properties) {
            state = AccentWriteState.RELINQUISHED
            return
        }
        val raw = properties.getValue(key)
        if (raw in expected) expected = setOf(raw) else state = AccentWriteState.RELINQUISHED
        observations += GitProportionObservation(planIndex, restoring, raw, state)
    }

    companion object {
        /** Restores only the receipt-qualified application property, without reacquiring a splitter. */
        fun recover(
            key: String,
            before: String?,
            expectedOutcomes: Set<String?>,
            properties: PropertiesComponent = PropertiesComponent.getInstance(),
        ): GitProportionCheckpoint = GitProportionCheckpoint(key, properties, before, expectedOutcomes, true)

        private fun checkEdt() =
            check(SwingUtilities.isEventDispatchThread()) { "Git proportion checkpoints require EDT" }
    }
}

/** Both encodings are public JBSplitter outcomes; the private default is never inspected or guessed. */
internal data class GitProportionPlan(
    val index: Int,
    val before: Int,
    val planned: Int,
) {
    val forwardOutcomes: Set<String?>
        get() = setOf(null, Float.fromBits(planned).toString())
    val rollbackOutcomes: Set<String?>
        get() = setOf(null, Float.fromBits(before).toString())
}

internal data class GitProportionObservation(
    val plan: Int?,
    val restoring: Boolean,
    val raw: String?,
    val state: AccentWriteState,
)

/** Earlier snapshots remain unchanged when later native writes append observations. */
internal data class GitProportionRecord(
    val key: String,
    val before: String?,
    val plans: List<GitProportionPlan>,
    val observations: List<GitProportionObservation>,
    val isRestorePrepared: Boolean,
    val state: AccentWriteState,
)
