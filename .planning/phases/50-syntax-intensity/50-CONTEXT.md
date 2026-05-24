# Phase 50: syntax-intensity — Context

**Gathered:** 2026-05-24 (assumptions mode, redesign after v1 rollback)
**Status:** Ready for planning

<domain>
## Phase Boundary

Replace Phase 49 mood radio + 4 style axes with a preset-driven syntax customization surface. Free tier ships 4 named presets (Whisper / Ambient / Neon / Cyberpunk) plus the Custom pill as a discovery surface. Premium tier unlocks the Custom drill-down with per-language × per-primitive-category sliders. Math = hybrid (saturationDelta + lightnessDelta) per category curve; Comments curve preserves the Phase 49 DIMMED_COMMENTS effect via additional lightness drop.

Phase 50 also extracts a shared `ColorPreset<TConfig>` abstraction across Glow / VCS / Font / Syntax preset franchises that currently duplicate the same structural pattern.

Out of scope: per-key foreground-color override, per-key font-style dropdown, master intensity slider, RGB-blend-toward-bg math, or any UI surface that scales the whole foreground uniformly. These were in v1 and have been rolled back.

Requirements scope: `INTENSITY-01..19` in `.planning/REQUIREMENTS.md` (rewritten 2026-05-24 to match the redesigned architecture).
</domain>

<decisions>
## Implementation Decisions

### Architecture
- **D-01:** Pill-row presets replace the master intensity slider. The active preset visually selected; click applies + persists immediately. No Apply button. Mirrors the Glow / VCS / Font franchise UI established in the codebase.
- **D-02:** Custom drill-down is the only path to per-language × per-primitive control. Free users see the Custom pill in a disabled state with an "Upgrade to Pro" tooltip + link. Phase A (4 named presets) is free; Phase B (Custom drill-down) is premium-gated via `LicenseChecker.isLicensedOrGrace()`. Premium-by-default rule (CLAUDE.md) honored at the Custom level; preset access stays free for parity with v1 INTENSITY-09 intent and broad discoverability.
- **D-03:** Math = hybrid HSL transform per category. Preset declares `(saturationDelta, lightnessDelta) ∈ [-1.0, +1.0]²` for every (language × category) pair. Apply path: baseline foreground RGB → `HslColor.fromColor` → add deltas → `coerceIn(0f, 1f)` on saturation, `coerceIn(0.10f, 0.95f)` on lightness (matches `AccentHsl` clamp semantics) → `HslColor.toColor` → write cloned TextAttributes. Hue invariant. No RGB-blend-toward-bg.
- **D-04:** No master intensity slider. The user controls intensity by choosing a preset; Custom mode is the granular surface, not a higher-level slider.

### Primitive categories
- **D-05:** 16 primitive categories form the granularity ceiling: function-decl, class-decl, interface-decl, keyword, parameter, local-var, string-literal, number-literal, comment, annotation, operator, type-ref, static-field, instance-field, generics, documentation (doc tags + escapes). All 16 are exposed as per-language sliders in Custom drill-down; presets declare full 16-value tuples per language.
- **D-06:** Mapping `TextAttributesKey` → primitive category is done via a hardcoded prefix-match table that extends the cherry-picked `SyntaxLanguageRegistry`. Mapping is language-aware (`JAVA_KEYWORD` → Java/keyword, `PY.KEYWORD` → Python/keyword, `STRING` (cascade-default) → cascade.string-literal materialized per language). The applicator uses the same `LanguageCascadeMap` materialization rules from the cherry-picked foundation.

### Color math and curves
- **D-07:** Comment-category curve includes lightness drop in addition to saturation drop. Whisper preset's Comments curve endpoint matches the Phase 49 DIMMED_COMMENTS hex from `themes/extended/AyuIslands{Mirage,Dark,Light}.extended.xml` (preserved overlay XMLs). All other categories use saturation-dominant curves with minimal or zero lightness shift.
- **D-08:** All curves are monotonic in `[0, 100]` slider space with preset baselines at 50. Slider 0 = curve's "subdued" endpoint, 100 = curve's "vivid" endpoint. The preset's declared `(saturationDelta, lightnessDelta)` value for the category equals the slider-50 anchor point.
- **D-09:** Editor background per variant sourced from `EditorColorsScheme.defaultBackground` at apply time. `RgbBlend.fallbackEditorBgFor(variantId)` (cherry-picked from v1) provides R-1 mitigation when scheme bg read fails. Although RGB-blend is no longer the primary math, the per-variant editor-bg constants stay useful as fallback context for low-lightness clamp edges.

### Languages
- **D-10:** All ~26 supported languages get personalized preset values. Generic fallback (DEFAULT_KEYWORD / DEFAULT_STRING / DEFAULT_NUMBER LANGUAGE_ABSTRACTION keys) applies when a TextAttributesKey is not in the prefix table — covers any future language without code change. Preset tables are hardcoded in source; per-language values are hand-tuned per category by the planner.

### Common abstraction
- **D-11:** Extract `ColorPreset<TConfig>` interface in this phase. Declarations: `displayName: String`, `entries: List<out ColorPreset<TConfig>>`, `companion fromName(name: String): ColorPreset<TConfig>`, `companion detect(config: TConfig): ColorPreset<TConfig>`. Refactor `GlowPreset`, `VcsColorPreset`, `FontPreset` to implement the interface (preserving each enum's existing constants and `companion detect()` algorithm). Implement `SyntaxPreset` through the same interface.
- **D-12:** The extracted interface lives in `dev.ayuislands.preset.ColorPreset` (new package). All four implementations (Glow / VCS / Font / Syntax) keep their existing packages and only add the interface implementation marker.

### Migration and Phase 49 sunset
- **D-13:** Phase 49 state file `ayu-islands-syntax-mode.xml` is read-and-discarded on first launch. New Phase 50 state file is `ayu-islands-syntax-intensity.xml` (different filename ⇒ unambiguous migration; no schema-version field needed).
- **D-14:** Migration notification flag is `ayu.syntax.intensity.notified` in `PropertiesComponent` — distinct from Phase 49's `ayu.syntax.notified` so users who saw the Phase 49 notification still see this migration message.
- **D-15:** Phase 49 source files deleted atomically with plugin.xml service unregistration (R-3 single-commit invariant): `SyntaxMood.kt`, `StyleAxis.kt`, `SyntaxModeApplicator.kt`, `SyntaxModeService.kt`, `SyntaxModeState.kt`, `SyntaxModeUpgradeNotifier.kt` + all their tests. Resource files `themes/extended/mood-tiers.txt`, `themes/extended/axis-keys.txt` deleted. `SyntaxOverlayLoader.kt` retained — still loads the `.extended.xml` overlay files which Phase 50 needs as the baseline semantic-key universe.

### State and persistence
- **D-16:** `SyntaxIntensityState` schema: `selectedPreset: String` (enum name), `customOverrides: Map<String, Map<String, Int>>` (language → category → slider 0-100). Default state: `selectedPreset = "AMBIENT", customOverrides = emptyMap()`. Free tier never writes to `customOverrides` — the field exists for forward-compatibility with Phase B (Custom drill-down).
- **D-17:** `@Service @State SimplePersistentStateComponent<BaseState>` template per existing accent/glow services. XML persistence in `ayu-islands-syntax-intensity.xml`.

### Apply pipeline
- **D-18:** Apply runs through `SyntaxIntensityService` (new class, parallel form to other `*Service` classes). Per-scheme isolation (Pattern B): clone each registered scheme's TextAttributes, mutate the clone, write back. H5 dual-write to `EditorColorsManager.getInstance().globalScheme` AND named registered schemes (`AYU_SCHEMES` tuple constants from prior accent work). Missing-scheme log-once (Pattern A).
- **D-19:** Single `MessageBus.syncPublisher(EditorColorsManager.TOPIC).globalSchemeChange(null)` wrapped in `ReadAction.run` (R-7) per apply call. Slider drag in Custom mode debounced 100 ms before apply (OQ-08 — single apply per pause).
- **D-20:** Apply re-fires automatically when LAF switches back to Ayu via the existing `AyuIslandsLafListener`. Pattern J gate on `AyuVariant.isAyuActive()`. No duplicate listener registration.

### Foundation reuse (from cherry-picked Plan 50-01 commits)
- **D-21:** `RgbBlend.kt` (commit `706a2b2`) retained as utility for per-variant editor-bg fallback constants (R-1). Not used as primary math, but its `fallbackEditorBgFor(variantId)` method remains the R-1 mitigation source.
- **D-22:** `SyntaxLanguageRegistry.kt` (commit `614f093`) retained and extended. The prefix-classifier and cascade catalog form the foundation for category-mapping (D-06) and apply-pipeline language scoping.

### Freemium and UX policy
- **D-23:** Default preset on first install = AMBIENT (identity transform). No visual regression from current Ayu palette out of the box.
- **D-24:** Pill row is always visible to free users — Custom pill disabled with tooltip + link rather than hidden. This is the discoverability surface for the Pro upgrade path.
- **D-25:** No license-gating on preset switching for Whisper / Ambient / Neon / Cyberpunk. Only the Custom pill and the Custom fold-out are premium-gated.

### Risks acknowledged
- **D-26:** R-1 (per-variant editor-bg fallback) retained from v1 via `RgbBlend.fallbackEditorBgFor`.
- **D-27:** H5 (dual-write to globalScheme + named schemes) retained as a Phase 49 carry-forward pattern.
- **D-28:** R-7 (ReadAction-wrapped publish) retained.
- **D-29:** Pattern A (missing-scheme log-once), Pattern B (per-scheme clone isolation), Pattern J (LafListener AyuVariant gate) all retained.
- **D-30:** R-3 (atomic plugin.xml service swap + source delete in one commit) honored — Plan 50-06 v1 split this across two commits which forced compile-gate cascade fixes; the replan must keep wire-up and deletion atomic.

### Folded Todos
None — no pending todos matched the phase scope on 2026-05-24.
</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

- `.planning/REQUIREMENTS.md` lines 130-179 — INTENSITY-01..19 (rewritten 2026-05-24)
- `.planning/phases/50-syntax-intensity-v1-rolled-back/` — legacy plans, summaries, and verification report from the abandoned v1 architecture, kept as reference for what NOT to do

### Code that already exists and must be consulted

- `src/main/kotlin/dev/ayuislands/syntax/RgbBlend.kt` — cherry-picked foundation (D-21)
- `src/main/kotlin/dev/ayuislands/syntax/SyntaxLanguageRegistry.kt` — cherry-picked foundation (D-22)
- `src/main/kotlin/dev/ayuislands/glow/GlowPreset.kt` — preset franchise reference (D-11)
- `src/main/kotlin/dev/ayuislands/vcs/VcsColorPreset.kt` — preset franchise reference (D-11)
- `src/main/kotlin/dev/ayuislands/vcs/VcsColorBlender.kt` — HSB lerp + shortest-arc hue + alpha-preservation patterns (reference for color math implementation)
- `src/main/kotlin/dev/ayuislands/font/FontPreset.kt` — preset franchise reference (D-11)
- `src/main/kotlin/dev/ayuislands/accent/color/AccentHsl.kt` — clamp semantics `[0.10f, 0.95f]` source (D-03)
- `src/main/kotlin/dev/ayuislands/rotation/HslColor.kt` — HSL ↔ RGB conversion utility (D-03)
- `src/main/kotlin/dev/ayuislands/syntax/SyntaxOverlayLoader.kt` — Phase 49 overlay loader, retained (D-15)
- `src/main/kotlin/dev/ayuislands/AyuIslandsLafListener.kt` — Pattern J gate host (D-20)
- `src/main/kotlin/dev/ayuislands/AyuIslandsStartupActivity.kt` — migration notifier wire site (D-14)
- `src/main/resources/themes/extended/AyuIslandsMirage.extended.xml` — baseline overlay (D-07, D-15)
- `src/main/resources/themes/extended/AyuIslandsDark.extended.xml` — baseline overlay (D-07, D-15)
- `src/main/resources/themes/extended/AyuIslandsLight.extended.xml` — baseline overlay (D-07, D-15)
- `src/main/resources/META-INF/plugin.xml` — service registry (D-30)
- `CLAUDE.md` — global project instructions (premium-by-default rule, UIDefaults discipline, commit style, coverage floors)
- `/Users/cloud/.claude/agents/RECURRING_PITFALLS.md` — 12-pattern checklist for executors

### Phase 49 code scheduled for deletion (D-15)

- `src/main/kotlin/dev/ayuislands/syntax/SyntaxMood.kt`
- `src/main/kotlin/dev/ayuislands/syntax/StyleAxis.kt`
- `src/main/kotlin/dev/ayuislands/syntax/SyntaxModeApplicator.kt`
- `src/main/kotlin/dev/ayuislands/syntax/SyntaxModeService.kt`
- `src/main/kotlin/dev/ayuislands/syntax/SyntaxModeState.kt`
- `src/main/kotlin/dev/ayuislands/syntax/SyntaxModeUpgradeNotifier.kt`
- `src/main/resources/themes/extended/mood-tiers.txt`
- `src/main/resources/themes/extended/axis-keys.txt`
- All Phase 49 tests in `src/test/kotlin/dev/ayuislands/syntax/`
</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets

- **Preset franchise** (D-11): `GlowPreset`, `VcsColorPreset`, `FontPreset` share an identical structural shape (4 named entries + CUSTOM + companion `fromName` and `detect`). No common abstraction exists yet. Pattern extraction is the cleanup payment for Phase 50's new `SyntaxPreset` entry — refusing extraction would mean a fourth duplicate of the same shape.
- **HSL/HSB math**: `HslColor` (in-repo) handles HSL ↔ RGB conversion with shortest-arc and clamp semantics. `AccentHsl` defines the clamp `[0.10f, 0.95f]` on lightness and `STEP = 0.05f` for lighten/darken actions. `VcsColorBlender` shows HSB lerp with shortest-arc hue + alpha preservation. Phase 50 math (D-03) reuses `HslColor` directly and inherits the `[0.10, 0.95]` clamp from `AccentHsl`.
- **Scheme write infrastructure**: Existing accent and glow services already write to `EditorColorsScheme` via clone-then-apply. Phase 50 `SyntaxIntensityService` follows the same pattern (D-18). The `AYU_SCHEMES` tuple constants exist in prior accent code.
- **LafListener gate** (D-20): `AyuIslandsLafListener` already has Pattern J gate on `AyuVariant.isAyuActive()`. Phase 50 extends the existing listener body, does not register a duplicate.
- **Foundation commits** (D-21, D-22): `RgbBlend.kt` and `SyntaxLanguageRegistry.kt` are on the branch from cherry-picks `706a2b2` and `614f093`. Both are pure-compute, platform-independent, and have GREEN tests.
- **Overlay XMLs**: `themes/extended/AyuIslands{Mirage,Dark,Light}.extended.xml` define the curated semantic-key universe. Phase 50 reads these as baseline for preset apply (D-15). `SyntaxOverlayLoader.kt` (Phase 49 code) is the loader and stays.

### Established Patterns

- **Pattern A** (Phase 49 carry-forward): missing-scheme situations logged once per (scheme, session) — no log spam (D-28)
- **Pattern B** (Phase 49 carry-forward): per-scheme isolation via clone-then-apply, never mutate baseline (D-18, D-28)
- **Pattern J** (Phase 49 carry-forward): LafListener gate on `AyuVariant.isAyuActive()` (D-20, D-28)
- **Pattern L** (Phase 49 carry-forward): regression locks via source-grep for invariants that cannot be runtime-tested (e.g., absence of LicenseChecker references on free panel)
- **H5** (Phase 49 carry-forward): dual-write to globalScheme + named registered schemes (D-18, D-27)
- **R-1** (Phase 50 carry-forward): per-variant editor-bg fallback constants for low-lightness clamp edge (D-09, D-21, D-26)
- **R-3** (codebase invariant): atomic plugin.xml service swap + source delete in one commit (D-15, D-30)
- **R-7** (Phase 49 carry-forward): single ReadAction-wrapped publish per apply (D-19, D-28)
- **OQ-08** (Phase 50 v1 carry-forward): slider-drag debounce at 100 ms before apply (D-19)

### Integration Points

- **AyuIslandsConfigurable** — adds new "Syntax" tab with pill row + Custom fold-out
- **plugin.xml** — registers `SyntaxIntensityService`, `SyntaxIntensityState`; unregisters Phase 49 `SyntaxModeService`, `SyntaxModeState`; retains `SyntaxOverlayLoader` registration (no change)
- **AyuIslandsLafListener.kt** — extends gated body with `SyntaxIntensityService.getInstance().reapplyForActiveLaf()` call
- **AyuIslandsStartupActivity.kt** — invokes `SyntaxIntensityMigrationNotifier.maybeFire(project)` inside the existing `SwingUtilities.invokeLater` block
- **ColorPreset interface** (new package `dev.ayuislands.preset`) — implemented by Glow / VCS / Font / Syntax preset enums
- **EditorColorsManager / globalScheme** — apply target via Pattern B isolation
- **EditorColorsManager.TOPIC** — single `globalSchemeChange(null)` publish per apply call, wrapped in ReadAction
</code_context>

<specifics>
## Specific Ideas

- **Whisper preset Comments curve** must reproduce the Phase 49 DIMMED_COMMENTS effect at slider position 0. The Phase 49 dimmed-comment colors from `themes/extended/AyuIslands{Mirage,Dark,Light}.extended.xml` define the curve endpoint.
- **Pill row visual style** mirrors the existing Glow preset pill row (screenshot shared on 2026-05-24). The Custom pill uses the disabled visual treatment with tooltip behaviour established for premium-gated controls elsewhere.
- **Lighten/Darken accent quick-action** (5% HSL lightness step in `AccentHsl`) is a structural reference for the per-category curve semantics. Phase 50 does NOT add quick-actions for syntax, but the math model is conceptually adjacent.
- **VCS preset slider positions** (Whisper = 0 = -10% saturation, Ambient = 33 = stock, Neon = 67 = +10% saturation, Cyberpunk = 100 = +20% saturation) document a tuning aesthetic the Syntax presets should align with — Whisper subdues, Cyberpunk maxes, Ambient is identity.
</specifics>

<deferred>
## Deferred Ideas

- **Per-key foreground colour picker in Custom drill-down** — v1 INTENSITY-13 mentioned this; rolled back. If desired, can be added as a follow-up phase. Out of scope for Phase 50.
- **Per-key font-style dropdown (Plain / Bold / Italic / Bold+Italic)** in Custom drill-down — same as above. Out of scope.
- **User-defined named presets (save current Custom config as new preset)** — out of scope. The 4 named presets are hardcoded in source.
- **Per-project syntax presets** (analogous to project accent shared via `.idea/`) — out of scope. Phase 50 state is application-level only.
- **Master "Reset all Syntax customization" button on the Settings panel header** — covered partially by INTENSITY-15's per-language reset; a global reset is a follow-up.
- **Quick-action keyboard shortcuts for switching presets** ("Ayu: Cycle Syntax Preset") — out of scope.
- **Status-bar widget showing active Syntax preset** — out of scope.

### Reviewed Todos (not folded)

None — no todos were surfaced for phase scope on 2026-05-24.
</deferred>
