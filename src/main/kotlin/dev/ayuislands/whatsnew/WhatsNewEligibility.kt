package dev.ayuislands.whatsnew

/**
 * Pure eligibility gate — extracted from the platform-coupled [WhatsNewLauncher]
 * so unit tests can exercise every combination of "already shown" × "manifest
 * present" × version-string shape without bringing up a real IDE. Used by
 * [WhatsNewLauncher.openIfEligible] to decide whether the update should open
 * the showcase tab or fall through to the balloon notification path.
 *
 * Both versions are normalized via [WhatsNewManifestLoader.normalizeVersion]
 * before comparison so a dev-sandbox `2.5.0-SNAPSHOT` doesn't re-trigger after
 * a stable `2.5.0` upgrade (and vice versa).
 *
 * Returns true iff: no record of this version having been shown, AND a
 * manifest resource exists. Either gate failing means "skip the tab, fall
 * through to balloon".
 */
internal fun isWhatsNewEligible(
    lastShownVersion: String?,
    currentVersion: String,
    manifestPresent: Boolean,
): Boolean {
    if (!manifestPresent) return false
    val lastNormalized = lastShownVersion?.let { WhatsNewManifestLoader.normalizeVersion(it) }
    val currentNormalized = WhatsNewManifestLoader.normalizeVersion(currentVersion)
    return lastNormalized != currentNormalized
}
