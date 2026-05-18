package dev.ayuislands.accent.toolbar.popup

/**
 * Single source of truth for all popup spacing constants. Every JBUI-scaled spacing
 * literal in the Wave-7 redesigned popup body MUST read from here — no bespoke `4`
 * / `6` / `8` / `18` ints scattered through the builders.
 *
 * `Int` constants are pre-scale; the consumer wraps in `JBUI.scale(...)` at the
 * use site. [CARD_ARC] stays a `Float` because it feeds `RoundRectangle2D.Float`
 * directly, where the shape constructor takes raw float corner radii.
 *
 * See `48-REDESIGN-SPEC.md` §5 for the rationale and the per-constant target visual
 * outcome.
 */
internal object Density {
    /** Outer padding inside the platform's `JBPopupFactory` chrome. */
    const val POPUP_PAD: Int = 8

    /** Vertical gap between adjacent [SectionCard]s. */
    const val CARD_GAP: Int = 6

    /** Inner padding inside each [SectionCard]'s content area. */
    const val CARD_CONTENT_PAD: Int = 8

    /** Corner radius for [SectionCard] rounded body. Raw float — fed to `RoundRectangle2D.Float`. */
    const val CARD_ARC: Float = 6f

    /** Gap between adjacent swatches in the accent grid. */
    const val SWATCH_GAP: Int = 3

    /** Swatch corner radius (smaller than Settings' 8 — cells are smaller). */
    const val SWATCH_ARC: Int = 6

    /** Gap between toggle tiles in the 2x2 grid. */
    const val TILE_GAP: Int = 6

    /** Gap between quick-action pills. */
    const val ACTION_GAP: Int = 4

    /** Caps-header strip height inside a [SectionCard]. */
    const val SECTION_HEADER_H: Int = 18

    /** Horizontal inset on each side of the FREE/PREMIUM hairline. */
    const val BLOCK_SEPARATOR_PAD: Int = 8
}
