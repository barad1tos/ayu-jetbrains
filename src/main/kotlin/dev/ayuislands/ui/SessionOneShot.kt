package dev.ayuislands.ui

import org.jetbrains.annotations.TestOnly
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JVM-session one-shot claim gate around a single compare-and-set.
 *
 * Shared by [FocusWinningTabOpener] callers: each tab-opening feature (the
 * What's New tab, the onboarding wizard) holds its own instance so claims
 * never cross-suppress between features. The gate resolves the multi-window
 * startup race — several project windows schedule the same open in parallel
 * and only the first to [tryAcquire] proceeds.
 */
internal class SessionOneShot {
    private val claimed = AtomicBoolean(false)

    /**
     * Atomically claims the one-shot slot for this JVM session. Returns `true`
     * only to the first caller; subsequent callers get `false` and must bail
     * without opening.
     */
    fun tryAcquire(): Boolean = claimed.compareAndSet(false, true)

    /**
     * Releases a held claim so a later caller can acquire again. Returns `true`
     * only when the call actually flipped a held claim — a defensive release
     * from a caller that never acquired (or ran after another thread already
     * released) is a no-op returning `false`, letting callers skip a
     * misleading "released" breadcrumb.
     */
    fun release(): Boolean = claimed.compareAndSet(true, false)

    /** Test-only hook: reset the one-shot flag between test cases. */
    @TestOnly
    fun resetForTesting() {
        claimed.set(false)
    }
}
