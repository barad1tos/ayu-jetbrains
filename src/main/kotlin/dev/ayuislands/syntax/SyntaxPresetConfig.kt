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
 */
data class SyntaxPresetConfig(
    val selectedPreset: String,
    val customOverrides: Map<String, Map<String, Int>>,
    val subordinatePreset: String = "AMBIENT",
)
