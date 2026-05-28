package dev.ayuislands.syntax

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.awt.Font

internal const val SYNTAX_INTENSITY_SCHEMA_VERSION = 3

/**
 * Per-category font style for the Custom drill-down (Part A backend).
 *
 * [fontType] is the `java.awt.Font` bitmask the applicator writes verbatim
 * into `TextAttributes.fontType` (`PLAIN=0`, `BOLD=1`, `ITALIC=2`,
 * `BOLD_ITALIC=3`). The enum [name] is the on-disk token persisted in
 * [SyntaxIntensityBaseState.customStyles]; bold and italic are combinable via
 * the single [BOLD_ITALIC] entry rather than two independent flags so the
 * stored value stays a closed catalog that [fromName] can validate.
 */
enum class FontStyleOverride(
    val fontType: Int,
) {
    PLAIN(Font.PLAIN),
    BOLD(Font.BOLD),
    ITALIC(Font.ITALIC),
    BOLD_ITALIC(Font.BOLD or Font.ITALIC),
    ;

    companion object {
        /**
         * Tamper-safe decode: an unknown / tampered string yields `null` so the
         * caller skips the cell (mirrors the `toIntOrNull` discipline used for
         * the intensity slider in [SyntaxIntensityState.toPresetConfig]). Never
         * throws — a bad XML value degrades to "inherit the source style".
         */
        fun fromName(raw: String?): FontStyleOverride? = entries.firstOrNull { it.name == raw }
    }
}

/**
 * Application-level persistence for Phase 50 syntax-intensity state.
 *
 * Storage file: `ayu-islands-syntax-intensity.xml` — distinct from Phase 49's
 * `ayu-islands-syntax-mode.xml` per D-13 so the read-and-discard migration is
 * unambiguous. The `schemaVersion` field on [SyntaxIntensityBaseState] is the
 * forward-compatibility lever for Phase 50B Custom drill-down changes; the
 * cross-phase (Phase 49 -> Phase 50) migration is governed by the filename
 * swap, not the field.
 *
 * Round-2 review fixes locked here:
 *  - Gemini + OpenCode MEDIUM consensus: `customOverrides` is a FLAT
 *    composite-key `Map<String, String>` (key = `"language|category"`,
 *    value = `"0..100"`), matching the proven
 *    [dev.ayuislands.settings.mappings.AccentMappingsState] BaseState shape.
 *    The nested `Map<String, MutableMap<String, Int>>` spike from round 1
 *    is skipped — both reviewers independently flagged BaseState's nested-map
 *    delegate as unreliable for XML round-trip.
 *  - OpenCode suggestion: `schemaVersion` field added for forward migration
 *    safety; now `3` since the global readability booleans were added. A v2
 *    config (no readability elements) reads as all false, so the bump needs no
 *    read-time migration.
 *  - Codex HIGH #1 continuation: [toPresetConfig] is the one-way bridge
 *    that adapts the flat composite-key map back into the nested
 *    `Map<String, Map<String, Int>>` shape consumed by
 *    [SyntaxIntensityApplicator.compute] and [SyntaxPreset.detect]. Plan
 *    50-03's [SyntaxPresetConfig] DTO is the boundary type — neither the
 *    state class nor the preset enum references the other directly.
 */
@Service
@State(
    name = "AyuIslandsSyntaxIntensityState",
    storages = [Storage(value = "ayu-islands-syntax-intensity.xml")],
)
class SyntaxIntensityState : SimplePersistentStateComponent<SyntaxIntensityBaseState>(SyntaxIntensityBaseState()) {
    /**
     * Adapt the persisted flat `customOverrides: Map<String, String>` shape
     * back to the nested `Map<String, Map<String, Int>>` shape consumed by
     * [SyntaxIntensityApplicator.compute] and [SyntaxPreset.detect].
     *
     * The `customStyles` flat map is reshaped in parallel into a nested
     * `language -> category -> fontType Int` map via the same `|`-split guard,
     * decoding each value through [FontStyleOverride.fromName] so a tampered
     * style token is skipped (the `fontType` bitmask is what the applicator
     * writes into `TextAttributes.fontType`).
     *
     * Tamper resistance: keys missing the `|` separator, keys with an empty
     * language ("|MISSING_LANG"), keys with an empty category ("Java|"),
     * intensity values that don't parse as `Int`, and style values that don't
     * decode to a [FontStyleOverride] are silently skipped — the BaseState
     * round-trip preserves whatever XML contains, and this bridge is the
     * place that normalises the surface presented to the applicator. No
     * `runCatching` / `catch (Throwable)` — guarded with explicit conditionals
     * + [String.toIntOrNull] / [FontStyleOverride.fromName] (Pattern B compliance).
     *
     * The default `selectedPreset` is `"AMBIENT"` (D-23 safety net) — even
     * if a future XML schema bug nulls the field out, the DTO consumer
     * passes the literal through [SyntaxPreset.fromName] which falls back
     * to [SyntaxPreset.AMBIENT].
     */
    fun toPresetConfig(): SyntaxPresetConfig =
        SyntaxPresetConfig(
            selectedPreset = state.selectedPreset ?: "AMBIENT",
            subordinatePreset = state.subordinatePreset ?: "AMBIENT",
            // Intensity slider cells: flat "language|category" -> "0..100".
            customOverrides = reshapeFlatMap(state.customOverrides) { it.toIntOrNull() },
            // Font-style cells: flat "language|category" -> FontStyleOverride
            // name. Decoded to the java.awt.Font bitmask the applicator writes
            // into TextAttributes.fontType; a tampered token decodes to null
            // and the cell is dropped, mirroring the toIntOrNull discipline.
            customStyles = reshapeFlatMap(state.customStyles) { FontStyleOverride.fromName(it)?.fontType },
            readabilityOptions =
                SyntaxReadabilityOptions(
                    dimComments = state.dimComments,
                    softenDocumentation = state.softenDocumentation,
                    quietOperators = state.quietOperators,
                    emphasizeDeclarations = state.emphasizeDeclarations,
                ),
        )

    /**
     * Reshape a persisted flat composite-key `Map<String, String>` into the
     * nested `language -> category -> Int` map the applicator consumes.
     *
     * Shared by the intensity-slider and font-style bridges so both apply the
     * SAME `|`-split and skip-on-bad-key guard. [decodeValue] returns `null`
     * for a tampered / unparseable value, in which case the cell is dropped
     * (tamper-safe — no `runCatching`, no broad `catch`). Keys missing the
     * separator, with an empty language ("|X"), or an empty category ("X|")
     * are skipped before [decodeValue] is consulted.
     */
    private fun reshapeFlatMap(
        flat: Map<String, String>,
        decodeValue: (String) -> Int?,
    ): Map<String, Map<String, Int>> {
        val nested = mutableMapOf<String, MutableMap<String, Int>>()
        for ((compositeKey, valueStr) in flat) {
            val pipeIdx = compositeKey.indexOf('|')
            if (pipeIdx <= 0 || pipeIdx == compositeKey.length - 1) continue
            val language = compositeKey.substring(0, pipeIdx)
            val category = compositeKey.substring(pipeIdx + 1)
            val decoded = decodeValue(valueStr) ?: continue
            nested.getOrPut(language) { mutableMapOf() }[category] = decoded
        }
        return nested
    }

    companion object {
        fun getInstance(): SyntaxIntensityState {
            val app = ApplicationManager.getApplication()
            return app.getService(SyntaxIntensityState::class.java)
        }
    }
}

/**
 * BaseState backing for [SyntaxIntensityState].
 *
 * Schema (D-16 — round-2 revised per Gemini + OpenCode consensus):
 *  - [selectedPreset]: enum name string, default `"AMBIENT"` (D-23).
 *  - [subordinatePreset]: enum name string, default `"AMBIENT"`. The named
 *    preset whose curve fills the untouched (sparse) cells while the user is
 *    in the Custom drill-down. Legacy / absent XML deserialises to `"AMBIENT"`
 *    via the `string("AMBIENT")` delegate, so old state stays valid with no
 *    read-time migration.
 *  - [customOverrides]: FLAT composite-key map. Key = `"language|category"`,
 *    value = slider position `"0..100"` as string. Matches the proven
 *    [dev.ayuislands.settings.mappings.AccentMappingsState] flat-map BaseState
 *    shape; the nested `Map<String, Map<String, Int>>` shape consumed by the
 *    applicator and preset detector is reconstructed by
 *    [SyntaxIntensityState.toPresetConfig].
 *  - [customStyles]: sibling FLAT composite-key map for per-category font
 *    style. Key = `"language|category"` (identical shape to [customOverrides]),
 *    value = [FontStyleOverride] enum `name` (`"BOLD"` / `"ITALIC"` /
 *    `"BOLD_ITALIC"` / `"PLAIN"`). Sparse: only cells the user styles
 *    materialise; an absent cell inherits the source attribute's font style.
 *    Independent of the intensity slider — a cell may carry a style override
 *    with no slider, or a slider with no style.
 *  - [dimComments], [softenDocumentation], [quietOperators], and
 *    [emphasizeDeclarations]: global readability modifiers layered on top of
 *    any selected preset. Defaults are false, so existing XML with no elements
 *    stays byte-identical until the user opts in.
 *  - [schemaVersion]: forward-compat sentinel; default `3` since readability
 *    modifiers were introduced. A v2 config (no readability elements)
 *    deserialises with all booleans false through the BaseState delegates, so
 *    the bump needs NO read-time migration.
 *
 * The free tier never writes to [customOverrides] / [customStyles] — the
 * premium Custom drill-down is the only writer.
 */
class SyntaxIntensityBaseState : BaseState() {
    var selectedPreset by string("AMBIENT")
    var subordinatePreset by string("AMBIENT")
    var customOverrides by map<String, String>()
    var customStyles by map<String, String>()
    var dimComments by property(false)
    var softenDocumentation by property(false)
    var quietOperators by property(false)
    var emphasizeDeclarations by property(false)
    var schemaVersion by property(SYNTAX_INTENSITY_SCHEMA_VERSION)
}
