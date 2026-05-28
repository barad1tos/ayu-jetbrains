package dev.ayuislands.syntax

/**
 * I/O boundary DTO between persisted syntax-intensity state and preset detection.
 *
 * Introduced to decouple [SyntaxPreset]'s `detect()` from the persistent
 * `SyntaxIntensityState` class (declared in the follow-up state plan). Without
 * this DTO the two layers would form a compile cycle: `detect(state)` would
 * require the state class, while the state class's `selectedPreset` field is
 * the very input `detect` reads.
 *
 * The state class will implement `fun toPresetConfig(): SyntaxPresetConfig`
 * as the one-way bridge — preset detection consumes this DTO, state
 * persistence owns the on-disk shape.
 *
 * Schema:
 *  - [selectedPreset]: enum-name string (`"WHISPER"`, `"AMBIENT"`, …). Unknown
 *    or tampered values fall back to `AMBIENT` at the detection site.
 *  - [subordinatePreset]: enum-name string for the named preset whose curve
 *    fills untouched cells while `selectedPreset == "CUSTOM"`. Unknown or
 *    tampered values fall back to `AMBIENT` via `SyntaxPreset.fromName`.
 *  - [customOverrides]: nested `language -> category -> slider 0..100` map.
 *    The free tier never writes to this map; the premium Custom drill-down is
 *    the only writer.
 *  - [customStyles]: nested `language -> category -> fontType` map, where
 *    `fontType` is the `java.awt.Font` bitmask (`PLAIN=0`, `BOLD=1`,
 *    `ITALIC=2`, `BOLD_ITALIC=3`) decoded from the persisted
 *    `FontStyleOverride` name. Sparse and independent of [customOverrides];
 *    an absent cell inherits the source attribute's font style. Defaults to
 *    `emptyMap()` so callers predating the font-style feature compile
 *    unchanged.
 *  - [readabilityOptions]: global semantic modifiers layered on top of the
 *    selected preset. Defaults to [SyntaxReadabilityOptions.DEFAULT] so old
 *    callers and old XML preserve the previous byte-identical output.
 */
data class SyntaxPresetConfig(
    val selectedPreset: String,
    val customOverrides: Map<String, Map<String, Int>>,
    val subordinatePreset: String = "AMBIENT",
    val customStyles: Map<String, Map<String, Int>> = emptyMap(),
    val readabilityOptions: SyntaxReadabilityOptions = SyntaxReadabilityOptions.DEFAULT,
)

/**
 * Global readability modifiers layered on top of the selected syntax preset.
 *
 * These switches are intentionally separate from Custom's per-language sparse
 * cells: Custom answers "which language/category should I tune manually?",
 * while readability answers "which semantic noise should recede everywhere?".
 * Defaults are all false so existing installs keep byte-identical syntax until
 * a user opts in.
 */
data class SyntaxReadabilityOptions(
    val dimComments: Boolean = false,
    val softenDocumentation: Boolean = false,
    val quietOperators: Boolean = false,
    val emphasizeDeclarations: Boolean = false,
) {
    companion object {
        val DEFAULT: SyntaxReadabilityOptions = SyntaxReadabilityOptions()
    }
}
