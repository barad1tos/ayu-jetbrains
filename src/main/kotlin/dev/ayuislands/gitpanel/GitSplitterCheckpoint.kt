package dev.ayuislands.gitpanel

import com.intellij.openapi.ui.Splitter
import com.intellij.ui.JBSplitter
import dev.ayuislands.accent.AccentCheckpoint
import dev.ayuislands.accent.AccentWriteCheckpoint
import dev.ayuislands.accent.AccentWriteState
import javax.swing.JComponent

/** Retains one exact splitter write until its owning Git manager settles the operation. */
internal class GitSplitterCheckpoint(
    private val splitter: Splitter,
    planned: Float,
    private val isLogCurrent: () -> Boolean,
    private val property: GitProportionCheckpoint? = null,
) : AccentWriteCheckpoint {
    private val first = splitter.firstComponent
    private val second = splitter.secondComponent
    private val before = splitter.proportion.toRawBits()
    private val plannedBits = planned.toRawBits()
    private val propertyKey = (splitter as? JBSplitter)?.splitterProportionKey
    private val propertyPlan = property?.plan(before, plannedBits)
    private var expected = before
    override var state = AccentWriteState.PREPARED
        private set
    override val hasPending: Boolean
        get() = state == AccentWriteState.OWNED
    val record: GitSplitterRecord
        get() = GitSplitterRecord(splitter, first, second, before, plannedBits, expected, state)

    init {
        require(property == null || propertyKey == property.key) { "Git proportion key belongs to another splitter" }
    }

    fun matchesBefore(): Boolean =
        isCurrent() && splitter.proportion.toRawBits() == before && property?.matchesCurrent() != false

    override fun apply(): Boolean {
        check(state == AccentWriteState.PREPARED) { "Git splitter change was already started" }
        if (!matchesBefore()) {
            state = AccentWriteState.RELINQUISHED
            return false
        }
        state = AccentWriteState.OWNED
        write(plannedBits)
        return permitsWrite() && expected == plannedBits
    }

    override fun restore(blockedBy: List<AccentCheckpoint>) {
        if (state == AccentWriteState.PREPARED) {
            state = AccentWriteState.RESTORED
            return
        }
        if (!hasPending) return
        check(blockedBy.none { it is GitSplitterCheckpoint && it.splitter === splitter && it.hasPending }) {
            "Git splitter restoration is blocked by a newer write"
        }
        if (!permitsWrite()) return
        write(before, restoring = true)
        if (permitsWrite()) {
            check(expected == before) { "Git splitter restoration did not settle" }
            state = AccentWriteState.RESTORED
        }
    }

    override fun commit() {
        if (state == AccentWriteState.OWNED && !permitsWrite()) return
        if (state != AccentWriteState.RELINQUISHED) state = AccentWriteState.RESTORED
    }

    override fun retain() = Unit

    fun relinquish() {
        state = AccentWriteState.RELINQUISHED
    }

    private fun permitsWrite(): Boolean {
        if (!isCurrent() ||
            splitter.proportion.toRawBits() != expected ||
            property?.permitsWrite() == false
        ) {
            relinquish()
        }
        return state == AccentWriteState.OWNED
    }

    private fun isCurrent(): Boolean =
        isLogCurrent() &&
            splitter.firstComponent === first &&
            splitter.secondComponent === second &&
            (splitter as? JBSplitter)?.splitterProportionKey == propertyKey

    private fun write(
        bits: Int,
        restoring: Boolean = false,
    ) {
        if (splitter.proportion.toRawBits() == bits) {
            expected = bits
            return
        }
        val previous = expected
        expected = bits
        try {
            val persisted = property
            if (persisted == null) {
                splitter.proportion = Float.fromBits(bits)
            } else if (!persisted.write(checkNotNull(propertyPlan), restoring) {
                    splitter.proportion =
                        Float.fromBits(bits)
                }
            ) {
                relinquish()
            }
        } finally {
            when (splitter.proportion.toRawBits()) {
                expected -> Unit
                previous -> expected = previous
                else -> relinquish()
            }
        }
    }
}

/** Native identities stay live; proportions preserve their exact portable Float representation. */
internal data class GitSplitterRecord(
    val splitter: Splitter,
    val first: JComponent?,
    val second: JComponent?,
    val before: Int,
    val planned: Int,
    val lastWritten: Int,
    val state: AccentWriteState,
)
