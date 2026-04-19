package dev.ayuislands.accent

import com.intellij.util.messages.Topic

/**
 * Project-scoped notification emitted by [ProjectLanguageDetector] after a
 * background scan finishes — whether the scan produced a dominant id, a
 * polyglot null verdict, or hit a transient failure path.
 *
 * Subscribers refresh UI projections over the detector cache (the Settings
 * proportions status row, the Tools-menu Rescan balloon). Listener runs on
 * the EDT so handlers can touch Swing directly without their own
 * `invokeLater` wrap; [ProjectLanguageDetector.publishScanCompleted] is the
 * sole producer and already dispatches the publish onto EDT.
 *
 * Project-scoped (not application-scoped) so the subscription list is
 * automatically cleared when the project closes and the `Project`-keyed
 * `MessageBus` instance is disposed. Application-level would leak stale
 * subscribers across project close/reopen cycles and force every subscriber
 * to implement its own project-disposed defense.
 */
internal fun interface ProjectLanguageDetectionListener {
    /**
     * Invoked after `ProjectLanguageDetector` settles a scan (successful,
     * polyglot, or non-cacheable transient failure) OR after a `rescan`
     * call whose `AccentResolver.projectKey` returned null.
     *
     * @param outcome structurally distinguishes the three end states;
     *   see [ScanOutcome] for the discriminator contract. Dumb mode and
     *   pre-scan disposal short-circuit inside
     *   `scheduleBackgroundDetection` and produce *no* publish at all —
     *   subscribers must not treat absence of `scanCompleted` as an error.
     *
     *   Callers rendering proportions MUST re-query
     *   [ProjectLanguageDetector.proportions] instead of relying on the
     *   outcome alone — the weights cache is the authoritative source for
     *   the proportions row and may be populated (or deliberately empty)
     *   independently of the outcome shape.
     */
    fun scanCompleted(outcome: ScanOutcome)

    companion object {
        val TOPIC: Topic<ProjectLanguageDetectionListener> =
            Topic.create(
                "Ayu Islands project language scan",
                ProjectLanguageDetectionListener::class.java,
            )
    }
}
