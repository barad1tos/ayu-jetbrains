package dev.ayuislands.syntax

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Color
import java.awt.Font

/**
 * Pure computation of `(mood, axes) → Map<TextAttributesKey, TextAttributes?>`.
 *
 * No platform deps in the core: no [com.intellij.openapi.editor.colors.EditorColorsManager],
 * no `setAttributes`, no `messageBus`. Only consumes a [SyntaxOverlayLoader] for
 * tier/axis data. [SyntaxModeService] does the per-scheme write + publish.
 *
 * Output contract:
 * - Keys in the active mood's whitelist → cloned overlay TextAttributes,
 *   possibly mutated by axes. Caller writes these via `scheme.setAttributes(key, attrs)`.
 * - Keys present in the overlay but NOT in the active mood's whitelist → `null`.
 *   Caller writes these via `scheme.setAttributes(key, null)` which clears the
 *   per-scheme override and falls back to parent scheme inheritance.
 * - Keys not in the overlay → not in the returned map (don't touch unrelated keys).
 *
 * Tier semantics (D-04 — additive whitelists):
 * - MINIMAL = empty subset (every overlay key is cleared)
 * - STANDARD = tier-keys(STANDARD)
 * - RICH = tier-keys(STANDARD) ∪ tier-keys(RICH)
 * - MAXIMUM = tier-keys(STANDARD) ∪ tier-keys(RICH) ∪ tier-keys(MAXIMUM)
 *
 * Axis transforms are applied AFTER the mood whitelist is materialized.
 * Toggle-off revertibility (D-07) is delivered by re-compute: a second call
 * without the axis produces a clean clone of the overlay baseline; no snapshot
 * tracking required.
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
        if (overlay.isEmpty()) return emptyMap()

        val whitelist = effectiveWhitelist(mood, loader)
        val result = mutableMapOf<TextAttributesKey, TextAttributes?>()
        for ((key, attrs) in overlay) {
            if (key !in whitelist) {
                // Out of active mood — clear via null sentinel (caller translates
                // to scheme.setAttributes(key, null) → parent scheme inheritance).
                result[key] = null
                continue
            }
            result[key] = applyAxes(attrs.clone(), key, axes, loader)
        }
        return result
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
