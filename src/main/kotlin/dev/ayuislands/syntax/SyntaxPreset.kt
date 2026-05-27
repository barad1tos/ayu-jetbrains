package dev.ayuislands.syntax

import dev.ayuislands.preset.ColorPreset
import dev.ayuislands.preset.PresetFamily

/**
 * Syntax-intensity preset franchise. Pill row in `Settings -> Ayu Islands ->
 * Syntax` exposes the 5 entries (Whisper / Ambient / Neon / Cyberpunk + the
 * Custom pill — disabled in the free tier, the entry point to the premium
 * Custom drill-down).
 *
 * Implements [ColorPreset] (marker per revised D-11). The companion ALSO
 * implements the optional [PresetFamily] adapter from the marker plan because
 * Syntax fits the single-`TConfig` `detect(state)` model — it reads
 * `state.selectedPreset` from a single field, captured here through the
 * [SyntaxPresetConfig] DTO so the franchise enum stays independent of the
 * persistent state class declared in the follow-up plan. Glow / VCS / Font do
 * NOT adopt PresetFamily because their detect / lookup signatures don't fit a
 * single-`TConfig` shape (see [PresetFamily] KDoc for the rationale).
 *
 * `AMBIENT` is the identity transform (D-23 default) so there's no visual
 * regression from the stock Ayu palette on a first install or after a
 * persisted preset goes missing.
 */
enum class SyntaxPreset(
    override val displayName: String,
) : ColorPreset {
    WHISPER("Whisper"),
    AMBIENT("Ambient"),
    NEON("Neon"),
    CYBERPUNK("Cyberpunk"),
    CUSTOM("Custom"),
    ;

    companion object : PresetFamily<SyntaxPreset, SyntaxPresetConfig> {
        override val entries: List<SyntaxPreset>
            get() = SyntaxPreset.entries.toList()

        /**
         * Parse a persisted preset-name string back to the matching enum entry.
         * Tampered, unknown, or `null` values fall back to [AMBIENT] per the
         * D-23 safety net — the identity transform never tints surfaces a user
         * never opted into.
         */
        override fun fromName(name: String?): SyntaxPreset = entries.firstOrNull { it.name == name } ?: AMBIENT

        /**
         * Resolve the currently-selected preset from a persisted
         * [SyntaxPresetConfig]. Delegates to [fromName] against
         * `config.selectedPreset` so the null / unknown / tampered fallback
         * matches across both lookup paths.
         *
         * The DTO indirection (instead of taking the state class directly) is
         * the boundary that breaks the would-be compile cycle between the
         * preset franchise and the persistent state class; the state class
         * implements `toPresetConfig(): SyntaxPresetConfig` as the one-way
         * bridge.
         */
        override fun detect(config: SyntaxPresetConfig): SyntaxPreset = fromName(config.selectedPreset)
    }
}
