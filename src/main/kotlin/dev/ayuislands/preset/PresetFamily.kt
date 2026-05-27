package dev.ayuislands.preset

/**
 * Optional generic adapter for preset franchises that fit a single-`TConfig`
 * `detect(state): P` model. Franchises adopt this on a per-need basis; the
 * marker [ColorPreset] does NOT require it.
 *
 * Used by `dev.ayuislands.syntax.SyntaxPreset`'s companion (added in the
 * syntax-intensity batch) because the syntax preset is resolvable directly
 * from `SyntaxIntensityState.selectedPreset` — a single field. Glow / VCS /
 * Font do NOT adopt this because their detect/lookup signatures don't fit:
 *  - [dev.ayuislands.glow.GlowPreset.Companion.detect] takes 4 separate parameters
 *  - [dev.ayuislands.vcs.VcsColorPreset.Companion.byName] reads against per-surface state fields
 *  - [dev.ayuislands.font.FontPreset.Companion.fromName] runs `LEGACY_NAMES` migration logic
 *
 * Companion-object adoption pattern (used by `SyntaxPreset` once it lands):
 *
 * ```kotlin
 * enum class SyntaxPreset(override val displayName: String) : ColorPreset {
 *     // entries...
 *     companion object : PresetFamily<SyntaxPreset, SyntaxIntensityState> {
 *         override val entries get() = SyntaxPreset.entries.toList()
 *         override fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: AMBIENT
 *         override fun detect(config: SyntaxIntensityState) = fromName(config.selectedPreset)
 *     }
 * }
 * ```
 *
 * Future generic UI work (e.g., a `PresetPillRow<P>` builder that needs both
 * [entries] and `displayName`) can take a `PresetFamily<P, *>` parameter and
 * stay agnostic to the specific franchise.
 */
interface PresetFamily<P : ColorPreset, TConfig> {
    /** All preset entries for this franchise (typically `EnumClass.values().toList()`). */
    val entries: List<P>

    /** Resolve a persisted preset name back to its entry; nulls/unknowns fall back to a franchise-specific default. */
    fun fromName(name: String?): P

    /** Detect the preset best matching a persisted [TConfig]; fallback semantics same as [fromName]. */
    fun detect(config: TConfig): P
}
