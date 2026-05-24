# Phase 50: syntax-intensity — Discussion Log (Assumptions Mode)

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions captured in 50-CONTEXT.md — this log preserves the analysis.

**Date:** 2026-05-24
**Phase:** 50-syntax-intensity
**Mode:** assumptions (redesign after v1 rollback)
**Areas analyzed:** architecture, color math, primitive categories, presets, freemium policy, REQUIREMENTS rewrite scope, rollback strategy

## Context

Phase 50 v1 (commits between `fa7c714` base and `8c064f4`) implemented a single master intensity slider that uniformly scaled all foreground RGB toward editor background. After live test on 2026-05-24, user reported the architecture missed the actual goal — granular per-primitive controls per language — and that the Phase 49 DIMMED_COMMENTS effect was incidentally destroyed.

User explicitly requested AskUserQuestion-driven redesign before any code rework: *«Проговори це зі мною із askUserQuestion перш ніж перероблювати»*. This log records that conversation.

## Assumptions Presented (none — direct discussion mode)

Skipped automated assumption-analyzer spawn. The codebase was already well-understood from the v1 verification cycle and the user's explicit guidance during rollback discussion provided sharper signals than codebase inference. Architecture surfaced directly through interactive AskUserQuestion rounds documented below.

## Discussion Rounds

### Round 1 — Architectural granularity, comments behaviour, rollback strategy

- **Granularity:** User chose option "Пресети + тонкий override" — preset picker as default UI, Custom mode opens an advanced fold-out exposing per-category controls.
- **Comments dimming:** "Частина системи примітивів" — comments is a category in the system, not a separate toggle. Dimming achieved via low slider on Comments category.
- **Rollback:** "Частковий rollback в Phase 50 (in-place fix)" — keep RgbBlend + Registry foundation, rework everything else.

### Round 2 — Color math, primitive set, presets, language scope

- **Color math:** User initially gave a hint to "use the lighten/darken accent mechanism as a shared abstraction." Codebase scout surfaced two candidate models: `AccentHsl` (HSL lightness ±5% step, clamp `[0.10, 0.95]`) and `VcsColorBlender` (HSB lerp baseline→target 0-100 with shortest-arc hue). User asked for tradeoff explanation.
- **Primitive set:** "Третій варіант, але вся ця складність буде ховатись під умовним оператором" — 16 extended categories hidden under Custom mode. Also requested extracting the common ColorPreset abstraction shared by Glow / VCS / Font in this phase.
- **Presets:** Deferred to "those four presets already used in Glow tab" — Whisper / Ambient / Neon / Cyberpunk + Custom.
- **Language scope:** "Всі ~26 мов відразу" — full coverage.

### Round 3 — Math tradeoffs explained

Presented full comparison of Option 2 (VcsColorBlender lerp, 416 hand-tuned hex pairs) vs Option 3 (saturation-only delta, no hand-tuning). Proposed hybrid: per-category curves with `(saturationDelta, lightnessDelta)` pair, Comments curve including lightness drop to preserve Phase 49 DIMMED_COMMENTS effect.

- **Decision:** "Гібрид (sat+light delta per category curve)" — D-03, D-07, D-08.

### Round 4 — REQUIREMENTS rewrite scope, freemium policy

- **REQUIREMENTS rewrite:** "In-place rewrite INTENSITY-01..19" — keep IDs, replace content. Single source of truth.
- **Freemium:** "Presets free, Custom = premium" — 4 named presets are free, Custom drill-down is premium-gated. Pill row visible to free users as Pro upgrade discovery surface.

## Corrections Made

None — all selections were positive design choices, no Claude-side assumptions to overturn.

## Auto-Resolved

None — assumptions mode skipped the analyzer spawn entirely. Every decision came from interactive AskUserQuestion rounds.

## External Research

None required — codebase contained sufficient precedent (Glow / VCS / Font preset patterns, AccentHsl clamp, HslColor utility, VcsColorBlender HSB lerp reference) and the user provided architectural direction directly.

## Rollback Operations Performed (2026-05-24)

- `git reset --hard fa7c714` — dropped 24 Phase 50 v1 commits from branch
- `git cherry-pick 706a2b2 614f093` — restored RgbBlend.kt and SyntaxLanguageRegistry.kt as foundation
- Pre-reset HEAD `8c064f4` retained in reflog for ~90 days
- `.planning/phases/50-syntax-intensity/` archived to `.planning/phases/50-syntax-intensity-v1-rolled-back/`
- `STATE.md` updated with full rollback context
- `.planning/REQUIREMENTS.md` INTENSITY-01..19 rewritten in-place under new architecture
- Build verified clean: `./gradlew compileKotlin compileTestKotlin detekt ktlintCheck` → BUILD SUCCESSFUL
- Phase 49 source + tests fully intact (mood radio, 4 axes, DIMMED_COMMENTS effect)
