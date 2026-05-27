package dev.ayuislands.preset

/**
 * Marker interface adopted by the four preset franchises in the codebase:
 *
 *  - [dev.ayuislands.glow.GlowPreset]
 *  - [dev.ayuislands.vcs.VcsColorPreset]
 *  - [dev.ayuislands.font.FontPreset]
 *  - `dev.ayuislands.syntax.SyntaxPreset` (added later in the syntax-intensity batch)
 *
 * Pure marker — only [displayName] is required. The four franchises have
 * structurally incompatible `detect()` signatures (Glow takes 4 separate
 * parameters; VCS reads `byName(String?)`; Font reads `fromName(String?)`;
 * Syntax keys off a single state class), so no shared companion contract
 * is enforced through this interface.
 *
 * Franchises that DO fit a single-`TConfig` `detect(state): P` model
 * (currently only Syntax) opt in by also implementing [PresetFamily];
 * adoption is per-need and does not propagate back to the marker.
 *
 * A generic UI builder bound on `P : ColorPreset` (e.g., `PresetPillRow<P>`)
 * can render any franchise's pill row using [displayName] alone — that is
 * the only call site this interface needs to support.
 */
interface ColorPreset {
    /** User-visible label rendered in pill rows and summaries. */
    val displayName: String
}
