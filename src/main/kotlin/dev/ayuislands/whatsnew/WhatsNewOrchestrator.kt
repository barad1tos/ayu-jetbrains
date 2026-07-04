package dev.ayuislands.whatsnew

import dev.ayuislands.ui.SessionOneShot

/**
 * Named holder of the What's New tab's in-session claim gate.
 *
 * Independent instance, mirror of
 * [dev.ayuislands.onboarding.OnboardingOrchestrator]'s gate, so a fresh
 * installer who finishes the onboarding wizard doesn't accidentally suppress
 * the next-version What's New tab in the same session.
 *
 * Two-layer dedup: this in-session gate handles the multi-window startup race
 * (three project windows opening in parallel before any has written persistent
 * state); `AyuIslandsState.lastWhatsNewShownVersion` handles the cross-restart
 * "already shown" gate.
 */
internal object WhatsNewOrchestrator {
    /** In-session one-shot claim for the What's New tab — see [SessionOneShot]. */
    val gate: SessionOneShot = SessionOneShot()
}
