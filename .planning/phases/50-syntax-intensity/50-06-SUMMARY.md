---
phase: 50-syntax-intensity
plan: 06
subsystem: settings-ui

tags:
  - intellij-plugin
  - syntax-intensity
  - settings-ui
  - premium-gate
  - segmented-button
  - pattern-l

# Dependency graph
requires:
  - phase: 50-syntax-intensity
    provides:
      - "Plan 50-03: SyntaxPreset enum + SyntaxPresetConfig DTO + SyntaxPresetCurves"
      - "Plan 50-04: SyntaxIntensityState (flat composite-key customOverrides + toPresetConfig bridge) + SyntaxIntensityApplicator"
      - "Plan 50-05: SyntaxIntensityService orchestrator (apply pipeline + service-level Custom gate) + SyntaxIntensityMigrationNotifier"
provides:
  - "AyuIslandsSyntaxPanel rewritten as 5-pill preset row (Whisper / Ambient / Neon / Cyberpunk / Custom)"
  - "Apply-on-click UX matching the Glow / VCS / Font franchise (no Apply button per D-01)"
  - "Custom-pill selection-interceptor for unlicensed users (revert + requestLicense flow)"
  - "Pattern L regression locks: only 2 LicenseChecker call sites in the panel, both confined to the Custom branch (free-pill code path verified license-free)"
  - "browserLink to JetBrains Color Scheme editor docs for per-key tuning discoverability"
  - "applyCustomPillTooltipIfFree wire site (tooltip pre-placement Gemini MEDIUM #3 — Swing subtree lookup finalised in Plan 50-08)"
affects:
  - "Plan 50-07 (Wave 4): Phase 49 sunset cascade — must verify no stale references to SyntaxMood / StyleAxis / SyntaxModeService / SyntaxModeState remain in the panel"
  - "Plan 50-08 (Wave 5): runIde smoke checkpoint — finalises the SegmentedButton<SyntaxPreset> internal-JButton lookup inside applyCustomPillTooltipIfFree"

# Tech tracking
tech-stack:
  added: []  # no new dependencies; reuses IntelliJ UI DSL SegmentedButton (already imported by Glow / VCS / Font panels)
  patterns:
    - "Pattern L (source-regex regression lock): 7 distinct locks on the panel source — call site count, branch wording, apply ordering, browserLink presence, correct package, interface signature, tooltip helper presence"
    - "Pattern G (paired apply/reset): reset() reverts pendingPreset to storedPreset and refreshes the SegmentedButton UI under suppressListeners"
    - "Anti-Pattern #4 ordering: apply-FIRST persist-SECOND preserved — service.apply throws before state.selectedPreset is mutated"
    - "SegmentedButton selection-interceptor (RB-8 workaround): per-item disable absent in 2025.1; reject in whenItemSelected by reverting to previous selection under suppressListeners"

key-files:
  created: []
  modified:
    - "src/main/kotlin/dev/ayuislands/settings/AyuIslandsSyntaxPanel.kt — Phase 49 mood radio + axis checkboxes replaced with 5-pill row"
    - "src/test/kotlin/dev/ayuislands/settings/AyuIslandsSyntaxPanelTest.kt — Pattern L lock test suite expanded to 20 cases"
    - "src/test/kotlin/dev/ayuislands/syntax/SettingsConfigurableSyntaxTabWiringTest.kt — tab-wiring source assertions adapted to the new panel API"

key-decisions:
  - "Followed Plan 50-06 verbatim — pill row + apply-on-click + selection-interceptor for Custom rejection + browserLink + tooltip pre-placement helper wire site"
  - "Codex HIGH #2: implemented the REAL AyuIslandsSettingsPanel.buildPanel(panel: Panel, variant: AyuVariant) signature (not the round-1 plan's standalone DialogPanel sketch)"
  - "Codex HIGH #2 continuation: imported LicenseChecker from dev.ayuislands.licensing (correct package, not dev.ayuislands.license as the round-1 plan mis-noted)"
  - "Gemini MEDIUM #3: applyCustomPillTooltipIfFree exists as the wire site for the runIde-finalised Swing subtree lookup — body intentionally a no-op until Plan 50-08 captures the actual JButton lookup path; the click-then-revert fallback in onPresetChosen is the active gate today"
  - "Phase 49 fields/methods removed verbatim — pendingMood / storedMood / pendingAxes / storedAxes / axisCheckboxes / moodRadios all gone; no SyntaxMood / StyleAxis / SyntaxModeService / SyntaxModeState references remain (Pattern L test 8 locks this)"

patterns-established:
  - "Custom-pill selection-interceptor pattern: SegmentedButton<TPreset> with a single license-gated entry whose unlicensed selection reverts to the previous preset and opens the upgrade flow — reusable for any future preset franchise that adds a premium entry without per-item disable API support"
  - "Pattern L call site count regression lock: assertEquals on the regex match count of `LicenseChecker.isLicensedOrGrace()` confines license checks to the documented branches; future refactors that leak the check into a free-pill path fail at test time, not at runtime"

requirements-completed:
  - INTENSITY-01
  - INTENSITY-10

# Metrics
duration: ~18min
completed: 2026-05-24
---

# Phase 50 Plan 06: Settings Tab Pill-Row Rewrite Summary

**Rewrote `AyuIslandsSyntaxPanel` from the Phase 49 mood-radio + axis-checkboxes shape to a 5-pill `SegmentedButton<SyntaxPreset>` row with apply-on-click UX, Custom-pill selection-interceptor for unlicensed users, and a `browserLink` to the Color Scheme editor — under 7 Pattern L source-regex regression locks.**

## Performance

- **Duration:** ~18 min
- **Started:** 2026-05-24T17:59:00Z (approx)
- **Completed:** 2026-05-24T18:17:41Z
- **Tasks:** 2
- **Files modified:** 3 (1 main source, 2 test sources)

## Accomplishments

- Replaced Phase 49 `pendingMood` / `storedMood` / `pendingAxes` / `storedAxes` / `axisCheckboxes` / `moodRadios` machinery with a single-field `pendingPreset` / `storedPreset` pair driving a `SegmentedButton<SyntaxPreset>` row over `SyntaxPreset.entries` (5 pills).
- Implemented apply-on-click UX: `onPresetChosen` writes `pendingPreset`, immediately invokes `apply()`, which calls `SyntaxIntensityService.getInstance().apply(preset, emptyMap())` FIRST and then persists `state.selectedPreset = pendingPreset.name` (Anti-Pattern #4 ordering).
- Custom-pill selection-interceptor: when a free user picks Custom, the SegmentedButton selection is reverted to the previous `pendingPreset` under `suppressListeners`, and `LicenseChecker.requestLicense("Unlock per-language syntax customization")` opens the upgrade flow. No service call, no persist.
- `applyCustomPillTooltipIfFree` wire site exists with Pattern B-compliant `catch (runtime: RuntimeException)`; queued via `SwingUtilities.invokeLater` after `buildPanel` so the Swing subtree is realised before the (Plan 50-08-finalised) lookup runs.
- `browserLink("Per-key tuning in Color Scheme editor", "https://www.jetbrains.com/help/idea/configuring-colors-and-fonts.html")` retained as the per-key tuning discoverability surface (D-02 hint that the built-in editor remains the precision tool).
- 20 unit tests in `AyuIslandsSyntaxPanelTest` covering: default-AMBIENT (3 cases), pill-apply (3), apply-FIRST persist-SECOND ordering (2), Custom rejection (1), Custom acceptance (1), reset (1), and 7 Pattern L source-regex locks (license call site count, CUSTOM guard wording, requestLicense count, no Phase 49 symbols, apply-FIRST source order, browserLink, correct LicenseChecker package, real interface signature, tooltip helper presence).
- 8 wiring assertions in `SettingsConfigurableSyntaxTabWiringTest` covering: interface assignability, insertTab call shape, SYNTAX_TAB_INDEX = 3, source-order Glow < Syntax < VCS, panels-list membership, field construction, buildPanel invocation site, and Phase 49 service/state non-reference.

## Task Commits

1. **Task 1: Rewrite AyuIslandsSyntaxPanel as 5-pill row** — `c0ebafe` (feat)
2. **Task 2: Rewrite panel + wiring tests with Pattern L locks** — `4802a93` (test)

## Files Created/Modified

- `src/main/kotlin/dev/ayuislands/settings/AyuIslandsSyntaxPanel.kt` — full body rewrite: `SegmentedButton<SyntaxPreset>` over `SyntaxPreset.entries`; `onPresetChosen` selection-interceptor for Custom rejection; apply-on-click; `applyCustomPillTooltipIfFree` wire site queued via `SwingUtilities.invokeLater`; `browserLink` to Color Scheme editor docs. Preserved class declaration + `AyuIslandsSettingsPanel` interface implementation (`buildPanel(panel, variant)` / `isModified()` / `apply()` / `reset()`).
- `src/test/kotlin/dev/ayuislands/settings/AyuIslandsSyntaxPanelTest.kt` — replaced Phase 49 mood/axes test cases with 20 cases covering apply-on-click semantics + 7 Pattern L locks. Reuses the project's `mockkObject(Companion) + every getInstance() returns mock` harness for service + state injection.
- `src/test/kotlin/dev/ayuislands/syntax/SettingsConfigurableSyntaxTabWiringTest.kt` — added compile-time interface assignability assertion + Phase 49 non-reference lock; kept existing tab-placement / panels-list / field-construction / buildPanel-call assertions.

## Decisions Made

None beyond following Plan 50-06 verbatim. All round-2 review fixes (Codex HIGH #2, Gemini MEDIUM #3) were already baked into the plan and were implemented as specified.

## Deviations from Plan

None — plan executed exactly as written. The only mid-task edit was a single `MaxLineLength` line-wrap in `AyuIslandsSyntaxPanelTest.kt` (the `forbidden` list literal exceeded the detekt threshold), which is purely stylistic and does not affect test semantics.

## Issues Encountered

None. Both `./gradlew detekt ktlintCheck` (after the line-wrap fix) and `./gradlew test --tests "AyuIslandsSyntaxPanelTest" --tests "SettingsConfigurableSyntaxTabWiringTest"` came back green. 20 + 8 = 28 test cases all passed.

## Pattern L Regression Lock Inventory

The panel source is now under 7 distinct source-regex regression locks. A future refactor that drifts from any of these patterns will fail at test time:

| Lock | Test method | Invariant |
|------|-------------|-----------|
| Call site count | `panel source has exactly 2 LicenseChecker isLicensedOrGrace call sites` | Exactly 2 `isLicensedOrGrace()` calls; future leaks into a free-pill path fail |
| Guard wording | `Custom-pill guard sits next to SyntaxPreset CUSTOM literal` | The guard reads `preset == SyntaxPreset.CUSTOM && !LicenseChecker.isLicensedOrGrace()` verbatim |
| requestLicense count | `requestLicense appears exactly once in panel source` | Exactly 1 `LicenseChecker.requestLicense(` call (the Custom rejection branch) |
| Phase 49 sunset | `panel source contains no Phase 49 symbol references` | Zero references to `SyntaxMood`, `StyleAxis`, `SyntaxModeService`, `SyntaxModeState`, `SyntaxModeUpgradeNotifier` |
| Apply-FIRST source order | `apply method body has service call BEFORE state mutation` | `SyntaxIntensityService.getInstance().apply(` appears textually before `state.selectedPreset = pendingPreset.name` |
| browserLink presence | `panel source contains a browserLink call` | `browserLink(` + JetBrains Color Scheme help URL both present |
| Correct package | `panel source imports LicenseChecker from licensing package` | `import dev.ayuislands.licensing.LicenseChecker` present; `dev.ayuislands.license.LicenseChecker` (wrong) absent |
| Real interface signature | `panel source uses real buildPanel(panel, variant) signature` | `override fun buildPanel(` + `panel: Panel,` + `variant: AyuVariant` all present; `override fun getComponent` absent |
| Tooltip helper presence | `panel source contains applyCustomPillTooltipIfFree helper` | `applyCustomPillTooltipIfFree` + `SwingUtilities.invokeLater { applyCustomPillTooltipIfFree() }` both present |

## Codex HIGH #2 Resolution

- **Real interface signature:** `override fun buildPanel(panel: Panel, variant: AyuVariant)` is implemented verbatim, matching `AyuIslandsSettingsPanel.kt:7-12`. The round-1 plan's standalone `DialogPanel` + `getComponent()` sketch was wrong; this plan and implementation conform to the actual contract.
- **Correct LicenseChecker package:** `import dev.ayuislands.licensing.LicenseChecker` (not `dev.ayuislands.license`). Verified by Pattern L Test 11.

## Gemini MEDIUM #3 Resolution

- `applyCustomPillTooltipIfFree` exists as the wire site for tooltip pre-placement. The helper short-circuits when licensed, then attempts a (currently no-op) Swing subtree walk wrapped in `try { ... } catch (runtime: RuntimeException) { ... }` (Pattern B compliance). It is queued via `SwingUtilities.invokeLater` from `buildPanel` so SegmentedButton's internal JButton instances have been realised before the lookup runs.
- The actual JButton lookup path is intentionally deferred to **Plan 50-08 (Wave 5 runIde checkpoint)** which will capture the verified Swing subtree shape and finalise the lookup. Until then, the click-then-revert fallback in `onPresetChosen` is the active gate — the tooltip is the pre-click affordance, not the gate itself.

## runIde Smoke Deferred to Plan 50-08

Manual runIde verification of the SegmentedButton row rendering, Custom-pill click behaviour, and tooltip pre-placement is deferred to **Plan 50-08 (Wave 5)** per the plan's `<verification>` and `<output>` sections. Plan 50-08 will:
- Run `./gradlew runIde` against the rewritten panel.
- Capture the actual Swing widget hierarchy produced by `SegmentedButton<SyntaxPreset>` in the 2025.1 platform.
- Finalise the JButton lookup inside `applyCustomPillTooltipIfFree` based on the captured hierarchy.
- Verify the click-revert UI behaviour for unlicensed Custom selection visually.

## Hand-off to Plan 50-07 (Wave 4) and Plan 50-08 (Wave 5)

- **Plan 50-07 (Phase 49 atomic sunset):** All Phase 49 references inside `AyuIslandsSyntaxPanel` are gone. The sunset cascade's R-3 single-commit invariant can safely delete `SyntaxMood.kt` / `StyleAxis.kt` / `SyntaxModeService.kt` / `SyntaxModeState.kt` / `SyntaxModeApplicator.kt` / `SyntaxModeUpgradeNotifier.kt` without touching this panel again. The Pattern L test 8 (`no Phase 49 symbol references`) is the regression net that will catch any accidental re-introduction.
- **Plan 50-08 (runIde checkpoint):** The `applyCustomPillTooltipIfFree` body is the documented wire site for the finalised JButton lookup. The follow-up plan should also exercise the click-then-revert fallback under EDT load to confirm the `suppressListeners` flicker risk is mitigated.

## Self-Check: PASSED

Files created/modified verified to exist:
- `src/main/kotlin/dev/ayuislands/settings/AyuIslandsSyntaxPanel.kt` — FOUND
- `src/test/kotlin/dev/ayuislands/settings/AyuIslandsSyntaxPanelTest.kt` — FOUND
- `src/test/kotlin/dev/ayuislands/syntax/SettingsConfigurableSyntaxTabWiringTest.kt` — FOUND

Commit hashes verified in `git log`:
- `c0ebafe` (Task 1) — FOUND
- `4802a93` (Task 2) — FOUND

Gates verified green this session:
- `./gradlew compileKotlin` — PASSED
- `./gradlew compileTestKotlin` — PASSED
- `./gradlew detekt ktlintCheck --rerun-tasks` — PASSED
- `./gradlew test --tests "AyuIslandsSyntaxPanelTest" --tests "SettingsConfigurableSyntaxTabWiringTest"` — 28/28 PASSED

---

*Phase: 50-syntax-intensity*
*Completed: 2026-05-24*
