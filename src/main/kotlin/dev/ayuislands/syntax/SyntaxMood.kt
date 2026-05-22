package dev.ayuislands.syntax

/**
 * Phase 49 mood tiers. Applied additively: [MINIMAL] is the empty subset of the
 * extended overlay; subsequent tiers expand the whitelist. Default fallback is
 * [MAXIMUM] so first-launch users see zero visual regression vs the pre-Phase-49
 * delta (D-02).
 *
 * **approximateKeyCount semantics (read before editing the numbers):**
 * These values are intentional UI rounding to the nearest 100 for label display
 * in the Syntax tab ("Minimal — ~1500 keys"). They MAY differ from the actual
 * curated tier sizes by ±100. The canonical, exact count for a mood is:
 *
 *     SyntaxOverlayLoader.tierKeys(mood).size + 1488
 *
 * where 1488 is the per-variant baseline scheme attribute count (commit 5673357).
 * The current tier sizes from Plan 49-01 curation are:
 * STANDARD = 52, RICH = 153, MAXIMUM = 365 keys (cumulative through MAXIMUM = 570).
 *
 * If curation drift in `MoodTierAssignmentSnapshotTest` (Plan 49-04) reports a
 * tolerance breach (|actual - (approximateKeyCount - 1488)| > 150), update these
 * enum values to fresh UI-rounded numbers — the test acts as the regression lock.
 */
enum class SyntaxMood(
    val displayName: String,
    val approximateKeyCount: Int,
) {
    MINIMAL("Minimal", APPROX_MINIMAL_KEYS),
    STANDARD("Standard", APPROX_STANDARD_KEYS),
    RICH("Rich", APPROX_RICH_KEYS),
    MAXIMUM("Maximum", APPROX_MAXIMUM_KEYS),
    ;

    companion object {
        // Approximate key counts shown in the Syntax tab radio labels.
        // See class KDoc — these are UI-rounded; canonical counts live in
        // SyntaxOverlayLoader.tierKeys(mood).size + 1488 baseline.
        internal const val APPROX_MINIMAL_KEYS = 1500
        internal const val APPROX_STANDARD_KEYS = 1550
        internal const val APPROX_RICH_KEYS = 1700
        internal const val APPROX_MAXIMUM_KEYS = 2100

        fun fromName(name: String?): SyntaxMood = entries.firstOrNull { it.name == name } ?: MAXIMUM
    }
}
