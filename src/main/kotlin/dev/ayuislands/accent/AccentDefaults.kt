package dev.ayuislands.accent

/**
 * Canonical default accent hex strings used as failure-mode fallbacks when
 * accent resolution throws. These are NOT semantic UI tokens — they are the
 * classic per-variant defaults that ship with the plugin's bundled themes
 * (see the `themes/` directory). Use `JBColor.namedColor` for chrome and
 * [AccentColor] presets for the swatch grid; this object exists only so the
 * three toolbar-popup fallback sites share one source of truth.
 */
internal object AccentDefaults {
    /** Ayu Mirage classic accent — the orange/amber used in the bundled Mirage theme. */
    const val MIRAGE_HEX: String = "#FFB454"
}
