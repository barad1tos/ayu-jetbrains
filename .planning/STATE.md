---
gsd_state_version: 1.0
milestone: v2.6.0
milestone_name: Peacock Parity
status: executing
stopped_at: "Phase 48 replanned: widget switcher, CONTEXT.md created"
last_updated: "2026-05-24T17:26:00.693Z"
last_activity: 2026-05-24
progress:
  total_phases: 12
  completed_phases: 3
  total_plans: 54
  completed_plans: 34
  percent: 63
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-04-22)

**Core value:** Beautiful, cohesive Ayu color experience across all three palettes with Islands UI
**Current focus:** Phase 50 — syntax-intensity

## Current Position

Phase: 50 (syntax-intensity) — EXECUTING
Plan: 1 of 8
Status: Executing Phase 50

### v1 rollback notes (2026-05-24)

- Original Phase 50 (global intensity slider) misunderstood user intent — built one slider that uniformly scales all foreground RGB toward editor bg, and deleted Phase 49 DIMMED_COMMENTS. Wrong architecture.
- User wants: per-language × per-functional-primitive granular controls; pill row presets (Whisper/Ambient/Neon/Cyberpunk/Custom) with Custom fold-out exposing 16 primitive categories; hybrid (saturation + lightness) per-category curves; common ColorPreset abstraction extracted in this phase from existing Glow/Vcs/Font triplet; all ~26 supported languages covered.
- Rollback: `git reset --hard fa7c714` + cherry-pick foundation commits `706a2b2` (RgbBlend) and `614f093` (SyntaxLanguageRegistry). 24 wrong commits dropped from branch; recoverable from reflog @ `8c064f4` until ~90 days.
- Phase 49 (mood radio + 4 style axes + DIMMED_COMMENTS) fully restored — all source and tests intact.
- Old plans archived at `.planning/phases/50-syntax-intensity-v1-rolled-back/` for reference.

Last activity: 2026-05-24

Progress: [██████░░░░] 63%

## Performance Metrics

**Velocity:**

- v2.4.0 milestone duration: 2026-04-04 to 2026-04-11 (8 days)

## Accumulated Context

### Decisions

- v2.9 Visual Focus & Flow cancelled 2026-04-22 before ship — Focus Mode / Zen Dimming stretched beyond the theme-plugin shape of Ayu Islands; dimming overlays + focus automation belong in a productivity-rail product. `feat/phase-38-focus-mode` branch deleted; no Focus Mode code on main. Archived planning at `.planning/milestones/v2.9-phases/38-focus-mode-zen-dimming/`.
- v2.6.0 Peacock Parity opened 2026-04-22 with 8 phases (40–47). Research skipped — P0+P1 decomposition is in conversation context: chrome tinting (Phase 40), shared project accent via `.idea/` (Phase 41), remote awareness (Phase 42), darken/lighten (Phase 43), favorites (Phase 44), command actions (Phase 45), startup surprise (Phase 46), status bar widget (Phase 47).
- Phase numbering 40–47 (39 reserved as buffer for any v2.9 salvage backlog items that may or may not surface later — none expected post-cancellation).
- [Phase 50]: Plan 50-02: 16 ordered suffix rules; first-match-wins; more-specific suffixes precede less-specific (DOC_COMMENT > COMMENT, INTERFACE_NAME > CLASS_NAME). Display-label patterns added to reach 95% Mirage coverage.
- [Phase 50]: Plan 50-02: SyntaxCategoryRegistry composes with SyntaxLanguageRegistry via discarded call — Codex MEDIUM #8 fix lock. Per-language curve lookup is the applicator's responsibility (Plan 50-04 calls SyntaxLanguageRegistry directly).
- [Phase 50]: Plan 50-02: Test 20 coverage check uses classpath read of overlay XML instead of @Service container — keeps Wave 2 strictly pure-compute for unit tests.
- [Phase ?]: [Phase 50]: Plan 50-04: SyntaxIntensityApplicator language-aware curve lookup via SyntaxLanguageRegistry.classify (Codex HIGH #4 fix). variantName param is for R-1 caller-contract WARN only — never reaches curve lookup as language.
- [Phase ?]: [Phase 50]: Plan 50-04: SyntaxIntensityState customOverrides is FLAT composite-key map<String, String> (key='language|category', value='0..100'). Gemini + OpenCode round-2 consensus — nested-map spike skipped. toPresetConfig() bridges to nested DTO at the boundary (Codex HIGH #1 continuation).
- [Phase ?]: [Phase 50]: Plan 50-04: R-1 caller contract — applicator takes editorBg: Color as parameter and WARN-once latches if dark variant arrives with Color.WHITE. Actual fallbackEditorBgFor mitigation is Plan 50-05 service-layer responsibility per RB-4.

### Roadmap Evolution

- v2.4.0 milestone complete (phases 21-25 shipped)
- v2.7 Language Detection UX Quality in progress on separate branch (phases 26-37, not shipped)
- v2.9 Visual Focus & Flow **cancelled** 2026-04-22 (Phase 38 never shipped)
- v2.6.0 Peacock Parity opened 2026-04-22 with phases 40-47
- Phase 40 Chrome Tinting Engine shipped 2026-04-25 (PR #155, merge 7ae4b73)
- Phase 40.1 lifecycle-gating-cleanup INSERTED 2026-04-25 between 40 and 41 (URGENT) — closes pre-existing theme-switch + project-switch leaks discovered during v2.5.3 smoke; blocks Phase 41 (SHARED inherits the same bug class)
- Phase 48 Accent Toolbar Suite added 2026-05-17 to v2.6.0 — freemium split: free `ideRootPaneNorth` stripe over MainToolbar (default ON) + premium `MainToolbarWidgetFactory` chip inline (default OFF). Triggered by Material Theme author's "Accent on the toolbar — just like Project Bar" suggestion. 4 plans estimated.
- Phase 50 edited: rewrote Phase 50 section: goal/depends/requirements/success criteria/plans aligned with preset-driven architecture from CONTEXT.md (Whisper/Ambient/Neon/Cyberpunk + Custom, hybrid HSL, ColorPreset franchise extraction). Plans list cleared for /gsd-plan-phase to regenerate.
- Phase 50 plans revised after checker round 1 (2026-05-24): `ColorPreset<TConfig>` full interface per D-11 (NOT marker-only — user explicitly chose D-11 literal compliance over the RESEARCH/PATTERNS marker-only simplification); `SyntaxOverlayLoaderBaselineTest.kt` added to Wave 4 sunset cascade (axis-keys-referencing test function removed, not whole file); 50-07 `autonomous: false` with human-verify checkpoint gating the irreversible atomic commit; 50-02 Test 20 uses classpath load not `@Service.getInstance()` (Wave 2 pure-compute premise preserved); INTENSITY-19 single-owner = 50-07 (was previously cross-listed with 50-03). Plan count unchanged at 8 (50-01 retrofit + caller-fix kept atomic in one plan rather than splitting into 50-01a/b — splitting would break compile-atomicity across Glow/Vcs/Font interface adoption).
- Phase 50 edited: BLOCKER #1 ColorPreset revised after /gsd-review: D-11 changed from full ColorPreset<TConfig> to marker-only + optional PresetFamily<P, C> adapter. 5-of-5 expert consensus (researcher + pattern-mapper + codex + gemini + opencode). Triggering /gsd-plan-phase 50 --reviews to incorporate this + 9 other consensus findings.
- Phase 50 plans revised after /gsd-plan-phase 50 --reviews round 2 (2026-05-24T12:43Z): all 8 PLAN.md files updated per cross-AI consensus. 50-01 REWRITTEN (marker-only ColorPreset + optional PresetFamily<P, TConfig> adapter; 3 enum one-line retrofits; no GlowDetectionContext; no caller-fix cascade; files_modified shrunk from 9 to 6). 50-02 MINOR (added language-aware composition: classifier delegates to SyntaxLanguageRegistry.classify FIRST per Codex MEDIUM #8 + cascade-bucket tests). 50-03 REWRITTEN (introduces SyntaxPresetConfig DTO to break 50-03↔50-04 compile cycle per Codex HIGH #1; SyntaxPreset companion adopts PresetFamily<SyntaxPreset, SyntaxPresetConfig> per revised D-11; concrete Whisper Comments tolerance test against Phase 49 RGB×0.6 per Codex MEDIUM #7). 50-04 REWRITTEN (skip nested-map spike, adopt flat composite-key Map<String,String> directly per Gemini+OpenCode MEDIUM consensus; add schemaVersion field per OpenCode; add toPresetConfig bridge; language-aware applicator per Codex HIGH #4 — derives language from key via SyntaxLanguageRegistry, not variantName). 50-05 REWRITTEN (R-1 AYU_SCHEMES whitelist replaces variant-name substring matching per OpenCode MEDIUM #5; service-layer CUSTOM premium gate per Codex MEDIUM #9; correct LicenseChecker package dev.ayuislands.licensing per Codex HIGH #2). 50-06 REWRITTEN (matches REAL AyuIslandsSettingsPanel.buildPanel(panel, variant) interface per Codex HIGH #2; correct LicenseChecker package; Gemini MEDIUM #3 tooltip pre-placement helper). 50-07 REWRITTEN (explicit path-list staging replaces git add -A per Codex HIGH #5; file counts reconciled to 27D+6M+1A; orphan-import sweep per Gemini LOW #4; expanded-scope Phase49SunsetRegressionTest per Codex suggestion; OpenCode tmp/50-07-staged safety branch option). 50-08 MINOR (jar tf replaces javap for class-presence per Codex LOW #10; weakened log-line count assertions). Plan count unchanged at 8.

### Pending Todos

None.

### Blockers/Concerns

- v2.7 completion audit still pending; Roman noted "де-факто завершений" but formal /gsd-complete-milestone pass not yet executed. v2.6.0 work can proceed in parallel — different code paths.

### Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|
| 260427-jpr | Calm v2.6.1 baseline for diff backgrounds across 3 colors XML (Dark, Light, Mirage) — DIFF_MODIFIED/INSERTED/DELETED softened, DIFF_CONFLICT untouched. Light DIFF_INSERTED restored to original alpha 20 after visual review (PR #162) | 2026-04-27 | 3adcbd4 | [260427-jpr-calm-v2-6-1-baseline-for-diff-background](./quick/260427-jpr-calm-v2-6-1-baseline-for-diff-background/) |
| Phase 50 P02 | 37min | 3 tasks | 4 files |
| Phase 50 P04 | 47m | 2 tasks | 4 files |

## Session Continuity

Last session: 2026-05-24T17:18:06.129Z
Stopped at: Phase 48 replanned: widget switcher, CONTEXT.md created
Resume file: None

**Planned Phase:** 40.1 (lifecycle-gating-cleanup) — 4 plans, 4/4 complete — 2026-04-25T12:55:00.000Z
</content>
</invoke>
