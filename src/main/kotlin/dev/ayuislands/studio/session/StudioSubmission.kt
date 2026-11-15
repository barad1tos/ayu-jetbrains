package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.AcceptanceBlock
import dev.ayuislands.studio.model.AcceptanceReview
import dev.ayuislands.studio.model.ApplyContext
import dev.ayuislands.studio.model.EffectObservation
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.StoreIssue

internal data class ApplyInputs(
    val context: ApplyContext,
    val effects: EffectObservation,
)

internal sealed interface Submission<out T> {
    data object NoChanges : Submission<Nothing>

    data class Blocked(
        val reason: AcceptanceBlock,
    ) : Submission<Nothing>

    data class Review(
        val items: StudioList<AcceptanceReview>,
    ) : Submission<Nothing>

    data class Recovery(
        val transactionId: String,
    ) : Submission<Nothing>

    data class FlushFailed(
        val code: String,
    ) : Submission<Nothing>

    data class StoreUnavailable(
        val issue: StoreIssue,
    ) : Submission<Nothing>

    data class Admitted<T>(
        val result: T,
    ) : Submission<T>
}
