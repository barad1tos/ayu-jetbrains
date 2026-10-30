package dev.ayuislands.accent

/**
 * A synchronous parent's preference receipt and decision before visual checkpoints
 * are committed. The receipt remains reversible until the scope commits after [beforeAcceptance].
 * [isAccepted] remains true if subsequent resource retirement or publication fails.
 */
internal class AccentApplyParticipation(
    val checkpoint: AccentCheckpoint,
    val beforeAcceptance: (AccentApplyOutcome) -> Unit,
) {
    var isAccepted: Boolean = false
        private set

    fun recordAcceptance(scope: AccentApplyScope) {
        isAccepted = scope.isCommitted
    }
}
