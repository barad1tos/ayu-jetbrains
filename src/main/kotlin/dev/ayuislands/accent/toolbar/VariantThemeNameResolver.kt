package dev.ayuislands.accent.toolbar

import dev.ayuislands.accent.AyuVariant

/**
 * Resolves the exact theme `getName()` string that [com.intellij.ide.ui.LafManager.findLaf]
 * expects for a `(variant, islandsUi)` pair. Pulls the name from
 * [AyuVariant.themeNames] via set membership instead of string concatenation
 * (Pitfall 7 from `48-RESEARCH.md` §8) — that lets the enum stay the single
 * source of truth for theme-name spelling and prevents drift between
 * `"Ayu Mirage (Islands UI)"` and any future renaming.
 *
 * Consumed by [VariantSwitcherRow] when the user picks a radio / toggles the
 * Islands UI checkbox in the Quick Switcher popup.
 */
internal object VariantThemeNameResolver {
    private const val ISLANDS_UI_MARKER = "(Islands UI)"

    /**
     * Pick the theme name from [AyuVariant.themeNames] matching the requested
     * Islands-UI flavour by set membership (not string concatenation —
     * Pitfall 7 from `48-RESEARCH.md` §8). Returns the exact `getName()`
     * string that [com.intellij.ide.ui.LafManager.findLaf] expects.
     *
     * @throws IllegalStateException if the enum's [AyuVariant.themeNames] set
     *   drifts and no name matches the requested flavour — surfaces the
     *   regression at the call site rather than silently passing the wrong
     *   name to `LafManager.findLaf` (which would return `null`).
     */
    fun resolveThemeName(
        variant: AyuVariant,
        islandsUi: Boolean,
    ): String =
        variant.themeNames.firstOrNull { it.contains(ISLANDS_UI_MARKER) == islandsUi }
            ?: error(
                "AyuVariant.$variant.themeNames missing islandsUi=$islandsUi entry: " +
                    "${variant.themeNames}",
            )
}
