package dev.ayuislands.theme

/** A recovery seed may retry an unwritten payload; normal Apply remains one-shot. */
internal class SchemeSeedProgress(
    private val before: SchemeCellFacts,
    private val planned: SchemeCellFacts,
) {
    var state = SchemeSeedState.PENDING
        private set
    var expected = before
        private set
    private var setterReturnedNormally = false

    val observation: SchemeSeedObservation
        get() = SchemeSeedObservation(state, expected, setterReturnedNormally)

    val hasPending: Boolean
        get() = state == SchemeSeedState.PENDING || state == SchemeSeedState.WRITING

    fun prepare(observed: SchemeCellFacts?): Boolean {
        if (state == SchemeSeedState.WRITING) observe(observed)
        if (state != SchemeSeedState.PENDING) return false
        if (observed != null && SchemeAdmission.matchesCell(before, observed)) return true
        preserve()
        return false
    }

    fun begin() {
        check(state == SchemeSeedState.PENDING) { "Editor seed is not ready to write" }
        setterReturnedNormally = false
        state = SchemeSeedState.WRITING
    }

    fun returned() {
        check(state == SchemeSeedState.WRITING)
        setterReturnedNormally = true
    }

    fun observe(observed: SchemeCellFacts?) {
        check(state == SchemeSeedState.WRITING)
        when {
            observed == null -> preserve()
            SchemeAdmission.matchesCell(planned, observed) -> {
                expected = planned
                state = SchemeSeedState.SETTLED
            }

            SchemeAdmission.matchesCell(before, observed) -> {
                expected = before
                state = if (setterReturnedNormally) SchemeSeedState.SETTLED else SchemeSeedState.PENDING
            }

            else -> preserve()
        }
    }

    fun preserve() {
        state = SchemeSeedState.FOREIGN
    }
}
