package dev.ayuislands.syntax

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Color
import java.awt.Font

/**
 * Pure computation of `(mood, axes) → Map<TextAttributesKey, TextAttributes?>`.
 *
 * Zero platform dependencies in the core. Only consumes a [SyntaxOverlayLoader]
 * for tier/axis/overlay/baseline data. [SyntaxModeService] owns the per-scheme
 * write + publish.
 *
 * Output contract (two merged sources, Option C — axes orthogonal to mood):
 *
 * Source 1 — overlay-driven (mood path):
 * - Keys in the active mood's whitelist → cloned overlay TextAttributes,
 *   possibly mutated by axes. Caller writes them to the per-variant scheme.
 * - Keys present in the overlay but NOT in the active mood's whitelist → `null`.
 *   Caller clears them on the scheme so JetBrains' parent inheritance walks
 *   to the baseline value.
 *
 * Source 2 — baseline-driven (axis path; Option C fix for
 * `syntax-mood-noop-on-editor` Bug #3):
 * - Keys listed in any ACTIVE axis-keys set that are NOT in the overlay →
 *   cloned baseline TextAttributes mutated by the matching axis transforms.
 *   Independent of mood — this is what makes axes orthogonal to the mood
 *   whitelist. DIMMED_COMMENTS now dims `DEFAULT_LINE_COMMENT` even at
 *   MINIMAL mood, because the transform operates on a baseline clone.
 * - Baseline-only key whose axis was just toggled OFF on a re-compute → still
 *   emitted, but as a pristine baseline clone (D-07 revert by re-read; the
 *   writeback restores the original baseline value).
 * - Baseline keys whose axis is NEVER active and that are not in the overlay
 *   → not emitted (don't touch unrelated keys).
 *
 * Tier semantics (D-04 — additive whitelists):
 * - MINIMAL = empty subset (every overlay key is cleared)
 * - STANDARD = tier-keys(STANDARD)
 * - RICH = tier-keys(STANDARD) ∪ tier-keys(RICH)
 * - MAXIMUM = tier-keys(STANDARD) ∪ tier-keys(RICH) ∪ tier-keys(MAXIMUM)
 *
 * Overlay-vs-baseline precedence: when a key is present in BOTH overlay and an
 * axis target set, the overlay clone wins (axes mutate the overlay clone in
 * place). The baseline-driven Source 2 only fires for keys absent from the
 * overlay.
 *
 * Axis transforms are applied AFTER the source clone is materialized.
 * Toggle-off revertibility (D-07) is delivered by re-compute: a second call
 * without the axis produces a clean clone of the overlay OR baseline; no
 * snapshot tracking required.
 */
object SyntaxModeApplicator {
    private const val DIM_FACTOR = 0.6

    fun compute(
        mood: SyntaxMood,
        axes: Set<StyleAxis>,
        variantName: String,
        loader: SyntaxOverlayLoader,
    ): Map<TextAttributesKey, TextAttributes?> {
        val overlay = loader.loadOverlayForVariant(variantName)
        val result = mutableMapOf<TextAttributesKey, TextAttributes?>()
        appendOverlayEntries(result, overlay, mood, axes, loader)
        appendBaselineAxisEntries(result, overlay, axes, variantName, loader)
        return result
    }

    /**
     * Source 1 — overlay-driven mood path. Iterates every overlay key and
     * emits either a cloned + axis-transformed value (whitelisted) or `null`
     * (cleared on the active scheme so inheritance walks to baseline).
     */
    private fun appendOverlayEntries(
        result: MutableMap<TextAttributesKey, TextAttributes?>,
        overlay: Map<TextAttributesKey, TextAttributes>,
        mood: SyntaxMood,
        axes: Set<StyleAxis>,
        loader: SyntaxOverlayLoader,
    ) {
        if (overlay.isEmpty()) return
        val whitelist = effectiveWhitelist(mood, loader)
        for ((key, attrs) in overlay) {
            if (key !in whitelist) {
                result[key] = null
                continue
            }
            result[key] = applyAxes(attrs.clone(), key, axes, loader)
        }
    }

    /**
     * Source 2 — baseline-driven axis path (Option C). For every key listed in
     * the loader's axis-keys map that is NOT in the overlay, emit a cloned
     * baseline value transformed by the matching axis. When no axis is active
     * for a baseline-only key the key is NOT emitted — the writeback only
     * touches keys the user has explicitly opted into via an axis.
     *
     * The single exception: when an axis is toggled OFF on a re-compute, the
     * loader still resolves the key (it remains in the axis-keys file), so we
     * emit a pristine baseline clone to restore the original value. This
     * delivers the D-07 revert-by-re-read contract without snapshot tracking.
     *
     * In practice this means: any key listed in axis-keys.txt under ANY axis
     * section (regardless of whether that axis is currently active) gets a
     * baseline writeback. Active axes transform the clone; inactive axes
     * still emit the pristine clone so the previous transform is reverted.
     */
    private fun appendBaselineAxisEntries(
        result: MutableMap<TextAttributesKey, TextAttributes?>,
        overlay: Map<TextAttributesKey, TextAttributes>,
        axes: Set<StyleAxis>,
        variantName: String,
        loader: SyntaxOverlayLoader,
    ) {
        val baselineAxisTargets = collectAllAxisTargets(loader, overlay)
        if (baselineAxisTargets.isEmpty()) return
        val baseline = loader.loadBaselineForVariant(variantName)
        if (baseline.isEmpty()) return
        for (key in baselineAxisTargets) {
            val baselineAttrs = baseline[key] ?: continue
            result[key] = applyAxes(baselineAttrs.clone(), key, axes, loader)
        }
    }

    /**
     * Returns the set of all keys listed in any axis-keys section that are NOT
     * shadowed by an overlay entry. Used by [appendBaselineAxisEntries] to
     * decide which baseline keys participate in the axis-orthogonal path.
     */
    private fun collectAllAxisTargets(
        loader: SyntaxOverlayLoader,
        overlay: Map<TextAttributesKey, TextAttributes>,
    ): Set<TextAttributesKey> {
        val combined = mutableSetOf<TextAttributesKey>()
        for (axis in StyleAxis.entries) {
            combined.addAll(loader.axisKeys(axis))
        }
        combined.removeAll(overlay.keys)
        return combined
    }

    private fun effectiveWhitelist(
        mood: SyntaxMood,
        loader: SyntaxOverlayLoader,
    ): Set<TextAttributesKey> {
        if (mood == SyntaxMood.MINIMAL) return emptySet()
        // Cumulative — every tier up to and including the active mood contributes.
        val accumulator = HashSet<TextAttributesKey>()
        val moodOrdinal = mood.ordinal
        for (tier in SyntaxMood.entries) {
            if (tier == SyntaxMood.MINIMAL) continue
            if (tier.ordinal > moodOrdinal) break
            accumulator.addAll(loader.tierKeys(tier))
        }
        return accumulator
    }

    private fun applyAxes(
        clone: TextAttributes,
        key: TextAttributesKey,
        axes: Set<StyleAxis>,
        loader: SyntaxOverlayLoader,
    ): TextAttributes {
        for (axis in axes) {
            if (key !in loader.axisKeys(axis)) continue
            applyAxis(clone, axis)
        }
        return clone
    }

    private fun applyAxis(
        clone: TextAttributes,
        axis: StyleAxis,
    ) {
        when (axis) {
            StyleAxis.ITALIC_DECLARATIONS, StyleAxis.ITALIC_DOC_TAGS -> {
                clone.fontType = clone.fontType or Font.ITALIC
            }
            StyleAxis.BOLD_TYPE_REFERENCES -> {
                clone.fontType = clone.fontType or Font.BOLD
            }
            StyleAxis.DIMMED_COMMENTS -> {
                val fg = clone.foregroundColor ?: return
                clone.foregroundColor =
                    Color(
                        (fg.red * DIM_FACTOR).toInt(),
                        (fg.green * DIM_FACTOR).toInt(),
                        (fg.blue * DIM_FACTOR).toInt(),
                        fg.alpha,
                    )
            }
        }
    }
}
