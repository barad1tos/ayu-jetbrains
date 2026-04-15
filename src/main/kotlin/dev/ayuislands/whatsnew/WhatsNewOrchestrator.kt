package dev.ayuislands.whatsnew

import java.util.concurrent.atomic.AtomicBoolean

/**
 * JVM-session one-shot pick gate for the What's New tab.
 *
 * No platform dependencies — pure logic, mirror of
 * [dev.ayuislands.onboarding.OnboardingOrchestrator]. Independent atomic so a
 * fresh installer who finishes the onboarding wizard doesn't accidentally
 * suppress the next-version What's New tab in the same session.
 *
 * Two-layer dedup: this in-session atomic handles the multi-window startup
 * race (three project windows opening in parallel before any has written
 * persistent state); `AyuIslandsState.lastWhatsNewShownVersion` handles the
 * cross-restart "already shown" gate.
 */
internal object WhatsNewOrchestrator {
    private val shown = AtomicBoolean(false)

    /**
     * Atomically claims the right to open the tab in this JVM session.
     * Returns `true` only to the first caller; subsequent callers get `false`
     * and must bail without opening.
     */
    fun tryPick(): Boolean = shown.compareAndSet(false, true)

    /**
     * Releases the in-session claim so a later caller can pick again. Called by
     * the launcher when an open attempt fails after `tryPick` returned true —
     * without this, a single failed `openFile` call would deadlock the whole
     * JVM session: no other window's auto-trigger could fire and the manual
     * "Show What's New…" menu item would also be stuck.
     */
    fun release() {
        shown.set(false)
    }

    /** Test-only hook: reset the one-shot flag between test cases. */
    @org.jetbrains.annotations.TestOnly
    fun resetForTesting() {
        shown.set(false)
    }
}
