---
gsd_state_version: 1.0
milestone: v2.6.0
milestone_name: Peacock Parity
status: planning
stopped_at: "Phase 50 v1 rolled back; awaiting /gsd-discuss-phase 50 replan with hybrid sat+light curves, 16 categories, common ColorPreset abstraction"
last_updated: "2026-05-24T11:30:00.000Z"
last_activity: 2026-05-24
progress:
  total_phases: 12
  completed_phases: 3
  total_plans: 46
  completed_plans: 30
  percent: 65
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-04-22)

**Core value:** Beautiful, cohesive Ayu color experience across all three palettes with Islands UI
**Current focus:** Phase 50 — syntax-intensity

## Current Position

Phase: 50 (syntax-intensity) — RE-PLANNING after v1 rollback
Plan: 0 of ? (foundation 50-01 cherry-picked, rest TBD)
Status: Awaiting /gsd-discuss-phase 50

### v1 rollback notes (2026-05-24)
- Original Phase 50 (global intensity slider) misunderstood user intent — built one slider that uniformly scales all foreground RGB toward editor bg, and deleted Phase 49 DIMMED_COMMENTS. Wrong architecture.
- User wants: per-language × per-functional-primitive granular controls; pill row presets (Whisper/Ambient/Neon/Cyberpunk/Custom) with Custom fold-out exposing 16 primitive categories; hybrid (saturation + lightness) per-category curves; common ColorPreset abstraction extracted in this phase from existing Glow/Vcs/Font triplet; all ~26 supported languages covered.
- Rollback: `git reset --hard fa7c714` + cherry-pick foundation commits `706a2b2` (RgbBlend) and `614f093` (SyntaxLanguageRegistry). 24 wrong commits dropped from branch; recoverable from reflog @ `8c064f4` until ~90 days.
- Phase 49 (mood radio + 4 style axes + DIMMED_COMMENTS) fully restored — all source and tests intact.
- Old plans archived at `.planning/phases/50-syntax-intensity-v1-rolled-back/` for reference.
Last activity: 2026-05-23

Progress: [########] 100% (4/4 plans executed; awaiting /gsd-verify-work)

## Performance Metrics

**Velocity:**

- v2.4.0 milestone duration: 2026-04-04 to 2026-04-11 (8 days)

## Accumulated Context

### Decisions

- v2.9 Visual Focus & Flow cancelled 2026-04-22 before ship — Focus Mode / Zen Dimming stretched beyond the theme-plugin shape of Ayu Islands; dimming overlays + focus automation belong in a productivity-rail product. `feat/phase-38-focus-mode` branch deleted; no Focus Mode code on main. Archived planning at `.planning/milestones/v2.9-phases/38-focus-mode-zen-dimming/`.
- v2.6.0 Peacock Parity opened 2026-04-22 with 8 phases (40–47). Research skipped — P0+P1 decomposition is in conversation context: chrome tinting (Phase 40), shared project accent via `.idea/` (Phase 41), remote awareness (Phase 42), darken/lighten (Phase 43), favorites (Phase 44), command actions (Phase 45), startup surprise (Phase 46), status bar widget (Phase 47).
- Phase numbering 40–47 (39 reserved as buffer for any v2.9 salvage backlog items that may or may not surface later — none expected post-cancellation).

### Roadmap Evolution

- v2.4.0 milestone complete (phases 21-25 shipped)
- v2.7 Language Detection UX Quality in progress on separate branch (phases 26-37, not shipped)
- v2.9 Visual Focus & Flow **cancelled** 2026-04-22 (Phase 38 never shipped)
- v2.6.0 Peacock Parity opened 2026-04-22 with phases 40-47
- Phase 40 Chrome Tinting Engine shipped 2026-04-25 (PR #155, merge 7ae4b73)
- Phase 40.1 lifecycle-gating-cleanup INSERTED 2026-04-25 between 40 and 41 (URGENT) — closes pre-existing theme-switch + project-switch leaks discovered during v2.5.3 smoke; blocks Phase 41 (SHARED inherits the same bug class)
- Phase 48 Accent Toolbar Suite added 2026-05-17 to v2.6.0 — freemium split: free `ideRootPaneNorth` stripe over MainToolbar (default ON) + premium `MainToolbarWidgetFactory` chip inline (default OFF). Triggered by Material Theme author's "Accent on the toolbar — just like Project Bar" suggestion. 4 plans estimated.

### Pending Todos

None.

### Blockers/Concerns

- v2.7 completion audit still pending; Roman noted "де-факто завершений" but formal /gsd-complete-milestone pass not yet executed. v2.6.0 work can proceed in parallel — different code paths.

### Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|
| 260427-jpr | Calm v2.6.1 baseline for diff backgrounds across 3 colors XML (Dark, Light, Mirage) — DIFF_MODIFIED/INSERTED/DELETED softened, DIFF_CONFLICT untouched. Light DIFF_INSERTED restored to original alpha 20 after visual review (PR #162) | 2026-04-27 | 3adcbd4 | [260427-jpr-calm-v2-6-1-baseline-for-diff-background](./quick/260427-jpr-calm-v2-6-1-baseline-for-diff-background/) |

## Session Continuity

Last session: 2026-05-17T16:17:55.803Z
Stopped at: Phase 48 replanned: widget switcher, CONTEXT.md created
Resume file: .planning/phases/48-accent-toolbar-suite/48-CONTEXT.md

**Planned Phase:** 40.1 (lifecycle-gating-cleanup) — 4 plans, 4/4 complete — 2026-04-25T12:55:00.000Z
