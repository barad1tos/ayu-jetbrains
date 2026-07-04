package dev.ayuislands.accent

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.ayuislands.settings.mappings.ProjectAccentSwapService

/**
 * Sole accent-owning subscriber of [ProjectLanguageDetectionListener.TOPIC].
 *
 * Keeps language detection a one-way event: [ProjectLanguageDetector] only
 * publishes `scanCompleted`, and this project service reacts by re-applying
 * the current resolver result. Before this class existed the detector
 * re-entered the resolve+apply pipeline from inside its own scan-completion
 * body, which coupled the scanner to the applicator and duplicated the
 * disposal-race defenses on both sides.
 *
 * After a background scan reaches a cacheable verdict ([ScanOutcome.Detected]
 * or [ScanOutcome.Polyglot]) the current resolver result must be re-applied:
 * a hit may enable a language override, while a fallback verdict may remove
 * one. [ScanOutcome.Unavailable] is transient — the cache was not populated,
 * so there is nothing new to apply and the previous accent stays put.
 *
 * Threading: [ProjectLanguageDetectionListener] handlers run on the EDT
 * (`publishScanCompleted` dispatches the publish via `invokeLater`), so the
 * resolver + apply chain here needs no additional dispatch. The subscription
 * is anchored to this service's own [Disposable], so it is torn down with
 * the project (the service container disposes project services on close).
 */
@Service(Service.Level.PROJECT)
internal class ScanCompletionAccentRefresher(
    private val project: Project,
) : Disposable {
    init {
        project.messageBus.connect(this).subscribe(
            ProjectLanguageDetectionListener.TOPIC,
            ProjectLanguageDetectionListener { outcome -> onScanCompleted(outcome) },
        )
    }

    /**
     * Mirrors the cacheable-verdict gate the detector used before the refresh
     * reaction moved here: `Detected` / `NoWinner` / `Empty` verdicts (which
     * publish as [ScanOutcome.Detected] / [ScanOutcome.Polyglot]) refreshed
     * the accent; `Cold` / `Unavailable` (publishing as
     * [ScanOutcome.Unavailable]) did not.
     */
    internal fun onScanCompleted(outcome: ScanOutcome) {
        when (outcome) {
            is ScanOutcome.Detected,
            ScanOutcome.Polyglot,
            -> refreshAccent()

            ScanOutcome.Unavailable -> Unit
        }
    }

    /**
     * EDT body of the post-scan refresh. Returns early on disposal, logs and
     * swallows any downstream apply failure.
     */
    private fun refreshAccent() {
        if (project.isDisposed) return
        runCatchingPreservingCancellation {
            // Best-effort refresh: the cache already has the cacheable scan
            // verdict (the detector publishes only after `detectAndCache`), so
            // `dominant()` behavior is unaffected by failures here. Containing
            // exceptions keeps a regression in any of the downstream apply
            // paths (variant detection, UIManager writes, focus-swap
            // notification) from surfacing as an uncaught EDT exception and
            // risking the UI.
            if (AccentApplicator.resolveFocusedProject() !== project) {
                LOG.debug("Post-scan accent refresh skipped because focused project changed")
                return@runCatchingPreservingCancellation
            }
            val variant = AyuVariant.detect() ?: return@runCatchingPreservingCancellation
            val hex = AccentResolver.resolve(project, variant)
            val applied = AccentApplicator.applyFromHexString(hex)
            if (applied) {
                ProjectAccentSwapService.getInstance().notifyExternalApply(hex)
            } else {
                LOG.warn("Skipping swap publish: applyFromHexString rejected '$hex'")
            }
        }.onFailure { exception ->
            LOG.warn("Post-scan accent refresh failed; cache is still warm", exception)
        }
    }

    override fun dispose() = Unit

    companion object {
        private val LOG = logger<ScanCompletionAccentRefresher>()

        fun getInstance(project: Project): ScanCompletionAccentRefresher =
            project.getService(ScanCompletionAccentRefresher::class.java)
    }
}
