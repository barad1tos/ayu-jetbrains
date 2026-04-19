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
     * Invoked after `ProjectLanguageDetector` settles a scan (successful or
     * non-cacheable transient failure) OR after a `rescan` call whose
     * `AccentResolver.projectKey` returned null (the scheduler is bypassed
     * but the listener still fires so one-shot subscribers can unblock).
     *
     * @param detectedId the dominant language id the detector settled on,
     *   or `null` when the scan was polyglot (definitive no-winner verdict),
     *   hit a non-cacheable transient failure (scanner threw, index read
     *   aborted), or the caller was a `rescan` with an unresolvable project
     *   key. Note: dumb mode and pre-scan disposal short-circuit inside
     *   `scheduleBackgroundDetection` and produce *no* publish at all;
     *   subscribers must not treat absence of `scanCompleted` as an error.
     *
     *   Callers rendering proportions MUST re-query
     *   [ProjectLanguageDetector.proportions] instead of relying on this id
     *   alone — the weights cache is the authoritative source for the
     *   proportions row and may be populated (or deliberately empty)
     *   independently of the winner id.
     */
    fun scanCompleted(detectedId: String?)

    companion object {
        val TOPIC: Topic<ProjectLanguageDetectionListener> =
            Topic.create(
                "Ayu Islands project language scan",
                ProjectLanguageDetectionListener::class.java,
            )
    }
}
