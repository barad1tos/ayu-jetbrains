---
phase: 50
plan: 01
subsystem: preset-franchise-unification
tags:
  - intellij-plugin
  - color-preset
  - refactor
  - franchise-unification
  - tdd
requirements: [INTENSITY-18]
dependency_graph:
  requires:
    - GlowPreset (existing — Plan series 26+)
    - VcsColorPreset (existing — Plan 41+)
    - FontPreset (existing — Plan series 36+)
  provides:
    - dev.ayuislands.preset.ColorPreset (marker interface)
    - dev.ayuislands.preset.PresetFamily (optional generic adapter)
  affects:
    - SyntaxPreset (future — Plan 50-03 will adopt the marker + opt into PresetFamily)
    - Future generic UI builders bound on `P : ColorPreset`
tech-stack:
  added:
    - "kotlin marker interface (no runtime reflection)"
  patterns:
    - "Pure marker interface — type discriminator only"
    - "Opt-in generic adapter — franchises adopt per-need, not by mandate"
key-files:
  created:
    - src/main/kotlin/dev/ayuislands/preset/ColorPreset.kt
    - src/main/kotlin/dev/ayuislands/preset/PresetFamily.kt
    - src/test/kotlin/dev/ayuislands/preset/ColorPresetMarkerTest.kt
  modified:
    - src/main/kotlin/dev/ayuislands/glow/GlowPreset.kt
    - src/main/kotlin/dev/ayuislands/vcs/VcsColorPreset.kt
    - src/main/kotlin/dev/ayuislands/font/FontPreset.kt
decisions:
  - "Marker-only ColorPreset (no <TConfig>) — per revised D-11"
  - "PresetFamily is opt-in — Glow/VCS/Font do NOT adopt; only Syntax will (Plan 50-03)"
  - "GlowPreset.detect 4-param signature preserved verbatim — no wrapper class introduced"
  - "VcsColorPreset.byName preserved — no synthetic detect() that would arbitrarily pick one of 3 VCS state fields"
  - "FontPreset.fromName preserved — LEGACY_NAMES migration logic untouched"
metrics:
  tasks_completed: 3
  files_created: 3
  files_modified: 3
  red_commits: 1
  green_commits: 2
  duration_minutes: ~10
  completed_date: 2026-05-24
---

# Phase 50 Plan 01: ColorPreset Marker Extraction Summary

Pure marker interface `ColorPreset` + opt-in `PresetFamily<P, TConfig>` adapter extracted in `dev.ayuislands.preset`; three franchise enums (Glow / VCS / Font) retrofitted with one-line marker adoption — zero behavioral change, zero signature change, zero caller updates.

## What Changed

**New package `dev.ayuislands.preset`** — first home for cross-franchise abstractions:

- `ColorPreset` — marker interface declaring only `val displayName: String`. No type parameter, no companion contract. Locked against regression by `ColorPresetMarkerTest` (`typeParameters.size == 0` assertion).
- `PresetFamily<P : ColorPreset, TConfig>` — optional generic adapter declaring `entries: List<P>`, `fromName(name: String?): P`, `detect(config: TConfig): P`. Franchises adopt **per need**; the marker does not require it.

**Three enum retrofits (one atomic commit)** — each touched in exactly two ways:
1. Added `import dev.ayuislands.preset.ColorPreset`
2. Added `override` on the `displayName` ctor param and `: ColorPreset` after the closing constructor paren

`GlowPreset.detect(style, intensity, width, animation)`, `VcsColorPreset.byName(name: String?)`, `FontPreset.fromName(name: String?)` signatures are byte-for-byte unchanged. Zero callers needed updating.

## Why It Matters

The four preset franchises (Glow / VCS / Font / Syntax-incoming) share the structural shape `5 entries (WHISPER/AMBIENT/NEON/CYBERPUNK/CUSTOM) + displayName ctor param + companion lookup`. Phase 50 introduces a fourth duplicate via `SyntaxPreset`; refusing to extract the common marker now would leave four parallel franchise enums with no shared type bound.

The marker enables future generic UI work — e.g., a `PresetPillRow<P : ColorPreset>` builder reading `displayName` only — without forcing structurally incompatible franchises (Glow takes 4 detect params; VCS uses `byName(String?)`; Font uses `fromName(String?)` with LEGACY_NAMES migration) to share a synthetic detect signature.

## Why the Round-1 Plan Was Rejected

The initial Plan 50-01 design (`ColorPreset<TConfig>` + companion-reflection contract) was rejected by 5-of-5 reviewer consensus (researcher + pattern-mapper + codex + gemini + opencode — see `.planning/phases/50-syntax-intensity/50-REVIEWS.md`) as over-engineered:

- The four franchises have structurally incompatible `detect()` signatures.
- Forcing a shared companion contract via reflection produces only test-time enforcement — no compile-time guarantee.
- Codex HIGH #3: VCS state has three preset fields (`vcsDiffPreset` / `vcsMergePreset` / `vcsBlamePreset` per `AyuIslandsState.kt`); a synthetic `detect()` picking only one is "especially suspect."
- The proposed `GlowDetectionContext` wrapper class would force a caller-fix cascade in `AyuIslandsEffectsPanel.kt` and `GlowPresetTest.kt` — pure plumbing churn for no semantic gain.

The revised D-11 path (marker + opt-in adapter) achieves the unification goal with **6 files touched instead of 9**, **zero caller files modified**, and **no reflection**. `kotlin-reflect` is not added to the test classpath.

## TDD Gate Compliance

| Gate | Commit | Verification |
|------|--------|--------------|
| RED  | `d3f0508 test(50-01): add failing ColorPresetMarkerTest RED gate` | 11 unresolved references on `compileTestKotlin --rerun-tasks` — `ColorPreset` and `PresetFamily` symbols absent |
| GREEN (interfaces) | `3eda5b7 feat(50-01): introduce ColorPreset marker + optional PresetFamily adapter` | `compileKotlin` passes; test file still partially RED on `is ColorPreset` checks against unretrofitted enums |
| GREEN (retrofit) | `6415c5a feat(50-01): retrofit Glow/Vcs/Font enums with ColorPreset marker` | 9/9 `@Test` in `ColorPresetMarkerTest` PASS; `GlowPresetTest` + `VcsColorPresetTest` + `FontPresetTest` regression-free |

Gate sequence: `test(...)` → `feat(...)` (interfaces) → `feat(...)` (retrofit) — all three commits land before SUMMARY.md.

## Hand-off to Plan 50-03

Plan 50-03 introduces `SyntaxPreset` as the **fourth marker adopter**:

```kotlin
enum class SyntaxPreset(override val displayName: String) : ColorPreset {
    WHISPER("Whisper"), AMBIENT("Ambient"), NEON("Neon"), CYBERPUNK("Cyberpunk"), CUSTOM("Custom");
    companion object : PresetFamily<SyntaxPreset, SyntaxIntensityState> {
        override val entries get() = SyntaxPreset.entries.toList()
        override fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: AMBIENT
        override fun detect(config: SyntaxIntensityState) = fromName(config.selectedPreset)
    }
}
```

Syntax fits the single-`TConfig`-detect model (resolvable from `SyntaxIntensityState.selectedPreset` alone), so it opts in to both `ColorPreset` and `PresetFamily`. INTENSITY-18 fully satisfied once Plan 50-03 lands.

## Acceptance Criteria Status

All Task 1, Task 2, Task 3 acceptance bullets pass. Two acceptance-criterion wordings in the PLAN flagged below as **plan-internal wording bugs** (Rule 1 in the deviation rules) — actual intent is met:

1. **Task 1 criterion**: `grep -c 'GlowDetectionContext' == 0`. The plan's own provided test source at line 250 of the PLAN includes the literal `GlowDetectionContext` in the test name itself (`fun \`GlowPreset detect keeps the 4-parameter signature (no GlowDetectionContext wrapper)\`()`) — the test name IS the regression lock. Implemented as the plan specified; actual class-symbol references remain at 0 (verified via `grep -cE '\bclass [A-Z]*GlowDetectionContext|new GlowDetectionContext|GlowDetectionContext\('`).
2. **Task 1 criterion**: `grep -c 'kotlin.reflect' == 0`. The test KDoc contains the substring `kotlin-reflect` (with hyphen) documenting that reflection is NOT used; `grep -c 'kotlin.reflect'` matches because `.` in BRE matches any char. Actual `import kotlin.reflect.*` imports stay at 0 (verified).

Both deviations are documentation-only — intent satisfied, no symbol references. No action needed beyond this note.

## Deviations from Plan

### Auto-fixed Issues

None — no Rule 1/2/3 deviations during implementation.

### Plan-wording Notes (NOT deviations)

See "Acceptance Criteria Status" above for the two acceptance-criterion wordings where the literal grep pattern contradicts the plan's own provided source code. Implemented per the plan's source and intent; flagged here for any future planner refining acceptance-grep syntax to use word-boundary regex or explicit import scoping.

## Self-Check: PASSED

Verified:
- `src/main/kotlin/dev/ayuislands/preset/ColorPreset.kt` — FOUND
- `src/main/kotlin/dev/ayuislands/preset/PresetFamily.kt` — FOUND
- `src/test/kotlin/dev/ayuislands/preset/ColorPresetMarkerTest.kt` — FOUND (9 @Test methods)
- `src/main/kotlin/dev/ayuislands/glow/GlowPreset.kt` — modified (import + marker + override; 4-param `detect` preserved)
- `src/main/kotlin/dev/ayuislands/vcs/VcsColorPreset.kt` — modified (import + marker + override; `byName(String?)` preserved)
- `src/main/kotlin/dev/ayuislands/font/FontPreset.kt` — modified (import + marker + override; `fromName(String?)` preserved)
- Commit `d3f0508` (test RED) — FOUND in `git log`
- Commit `3eda5b7` (feat interfaces) — FOUND in `git log`
- Commit `6415c5a` (feat retrofit, atomic 3-file) — FOUND in `git log`
- `./gradlew test --tests "ColorPresetMarkerTest"` → 9/9 PASS
- `./gradlew test --tests "GlowPresetTest"` / `VcsColorPresetTest` / `FontPresetTest` → no regressions
- `./gradlew detekt ktlintCheck` → BUILD SUCCESSFUL
- No files outside the 3 enums + the 3 new preset-package files modified
- No `STATE.md` or `ROADMAP.md` writes (worktree mode — orchestrator owns those)
