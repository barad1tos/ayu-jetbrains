package dev.ayuislands.accent.toolbar

import dev.ayuislands.accent.AyuVariant

/**
 * Resolves the exact theme `getName()` string expected by the installed-themes\
 * sequence for a `(variant, islandsUi)` pair. Pulls the name from
 * [AyuVariant.themeNames] via set membership instead of string concatenation —
 * the enum stays the single source of truth for theme-name spelling and
 * prevents drift between `"Ayu Mirage (Islands UI)"` and any future renaming.
 *
 * Consumed by [VariantSwitcherRow] when the user picks a segment / toggles
 * the Islands UI pill in the Quick Switcher popup. The caller matches the
 * returned name against the platform's installed-themes sequence (the
 * stable public lookup path) rather than the internal name-keyed overload.
 */
internal object VariantThemeNameResolver {
    private const val ISLANDS_UI_MARKER = "(Islands UI)"

    /**
     * Pick the theme name from [AyuVariant.themeNames] matching the requested
     * Islands-UI flavour by set membership (not string concatenation — the
     * enum owns spelling). The caller looks the name up against the installed
     * themes sequence; this object stays decoupled from the platform's
     * theme-lookup API.
     *
     * @throws IllegalStateException if the enum's [AyuVariant.themeNames] set
     *   drifts and no name matches the requested flavour — surfaces the
     *   regression at the call site rather than silently passing the wrong
     *   name through the installed-themes filter (which would return `null`).
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
