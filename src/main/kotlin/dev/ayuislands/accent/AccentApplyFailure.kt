package dev.ayuislands.accent

import com.intellij.openapi.progress.ProcessCanceledException
import dev.ayuislands.accent.elements.BracketMatchElement
import dev.ayuislands.accent.elements.CaretRowElement
import dev.ayuislands.accent.elements.InlayHintsElement
import dev.ayuislands.accent.elements.LinksElement
import dev.ayuislands.accent.elements.MainToolbarElement
import dev.ayuislands.accent.elements.MatchingTagElement
import dev.ayuislands.accent.elements.NavBarElement
import dev.ayuislands.accent.elements.PanelBorderElement
import dev.ayuislands.accent.elements.ProgressBarElement
import dev.ayuislands.accent.elements.ScrollbarElement
import dev.ayuislands.accent.elements.SearchResultsElement
import dev.ayuislands.accent.elements.StatusBarElement
import dev.ayuislands.accent.elements.ToolWindowStripeElement
import java.util.concurrent.CancellationException

internal fun captureAccentFailure(
    step: AccentApplyStep,
    operation: String,
    rollback: () -> Unit = {},
    action: () -> Unit,
): AccentApplyStepFailure? =
    try {
        action()
        null
    } catch (failure: RuntimeException) {
        try {
            rollback()
        } catch (recovery: RuntimeException) {
            if (recovery !== failure) {
                if (recovery.isAccentCancellation() && !failure.isAccentCancellation()) {
                    recovery.addSuppressed(failure)
                    throw recovery
                }
                failure.addSuppressed(recovery)
            }
        }
        if (failure.isAccentCancellation()) throw failure
        AccentApplyStepFailure(step, IllegalStateException(operation, failure))
    }

internal fun RuntimeException.rethrowIfCancelled() {
    if (isAccentCancellation()) throw this
}

internal fun RuntimeException.isAccentCancellation(): Boolean =
    this is ProcessCanceledException || this is CancellationException

internal fun neutralizeOrRevert(
    element: AccentElement,
    variant: AyuVariant?,
    rollback: () -> Unit = {},
): AccentApplyStepFailure? =
    captureAccentFailure(AccentApplyStep.ApplyElements, "Neutralize ${element.displayName}", rollback) {
        if (variant != null) element.applyNeutral(variant) else element.revert()
    }

/** Bundled adapters with complete child recovery; other extensions keep their cleanup contract. */
internal fun AccentElement.checkpointRollback(): (() -> Unit)? =
    when (this) {
        is SearchResultsElement, is BracketMatchElement, is CaretRowElement, is InlayHintsElement,
        is MatchingTagElement, is ProgressBarElement, is LinksElement, is ScrollbarElement,
        is MainToolbarElement, is StatusBarElement, is ToolWindowStripeElement,
        is NavBarElement, is PanelBorderElement,
        -> AccentApplyScope.active?.elementRollback()
        else -> null
    }
