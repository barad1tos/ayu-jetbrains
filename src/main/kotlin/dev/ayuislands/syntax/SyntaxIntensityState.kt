package dev.ayuislands.syntax

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

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
 *  - OpenCode suggestion: `schemaVersion: Int = 1` field added for forward
 *    migration safety. Phase 50A never writes; Phase 50B+ can bump and
 *    migrate at read time if the slider semantics change.
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
     * Tamper resistance: keys missing the `|` separator, keys with an empty
     * language ("|MISSING_LANG"), keys with an empty category ("Java|"),
     * and values that don't parse as `Int` are silently skipped — the
     * BaseState round-trip preserves whatever XML contains, and this
     * bridge is the place that normalises the surface presented to the
     * applicator. No `runCatching` / `catch (Throwable)` — guarded with
     * explicit conditionals + [String.toIntOrNull] (Pattern B compliance).
     *
     * The default `selectedPreset` is `"AMBIENT"` (D-23 safety net) — even
     * if a future XML schema bug nulls the field out, the DTO consumer
     * passes the literal through [SyntaxPreset.fromName] which falls back
     * to [SyntaxPreset.AMBIENT].
     */
    fun toPresetConfig(): SyntaxPresetConfig {
        val nested = mutableMapOf<String, MutableMap<String, Int>>()
        for ((compositeKey, valueStr) in state.customOverrides) {
            val pipeIdx = compositeKey.indexOf('|')
            if (pipeIdx <= 0 || pipeIdx == compositeKey.length - 1) continue
            val language = compositeKey.substring(0, pipeIdx)
            val category = compositeKey.substring(pipeIdx + 1)
            val slider = valueStr.toIntOrNull() ?: continue
            nested.getOrPut(language) { mutableMapOf() }[category] = slider
        }
        return SyntaxPresetConfig(
            selectedPreset = state.selectedPreset ?: "AMBIENT",
            customOverrides = nested,
        )
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
 *  - [customOverrides]: FLAT composite-key map. Key = `"language|category"`,
 *    value = slider position `"0..100"` as string. Matches the proven
 *    [dev.ayuislands.settings.mappings.AccentMappingsState] flat-map BaseState
 *    shape; the nested `Map<String, Map<String, Int>>` shape consumed by the
 *    applicator and preset detector is reconstructed by
 *    [SyntaxIntensityState.toPresetConfig].
 *  - [schemaVersion]: forward-compat sentinel; default `1` in Phase 50A.
 *    Phase 50B Custom drill-down activates writes; if the slider semantics
 *    change in a later iteration, bump this and migrate at read time.
 *
 * Free Phase 50A never writes to [customOverrides] — the field exists so
 * Phase 50B Custom drill-down can populate it without a schema migration.
 */
class SyntaxIntensityBaseState : BaseState() {
    var selectedPreset by string("AMBIENT")
    var customOverrides by map<String, String>()
    var schemaVersion by property(1)
}
