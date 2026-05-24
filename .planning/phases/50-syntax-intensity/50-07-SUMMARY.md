---
plan: 50-07
title: "Atomic Phase 49 sunset (R-3, INTENSITY-12)"
status: complete
wave: 4
commits:
  - hash: b0af839
    message: "feat(50-07): atomic Phase 49 sunset (R-3, INTENSITY-12, D-30)"
    files_changed: 43
    insertions: 175
    deletions: 4974
requirements: [INTENSITY-08, INTENSITY-12, INTENSITY-19]
deviations: 1
---

# Plan 50-07 — Atomic Phase 49 Sunset

## Outcome

Single atomic commit `b0af839` removed the entire Phase 49 mood-radio/style-axis system and swapped the LafListener reapply hook to the new `SyntaxIntensityService`. Plugin verification GREEN across all 11 JetBrains IDE products (RD, GO, PY, DB, RM, IC, CL, WS, PS, IU, IC).

## git show --stat HEAD

```
43 files changed, 175 insertions(+), 4974 deletions(-)
```

Breakdown — 27 deletes + 15 modifies + 1 add:
- **6 source deletes**: SyntaxMood.kt, StyleAxis.kt, SyntaxModeApplicator.kt, SyntaxModeService.kt, SyntaxModeState.kt, SyntaxModeUpgradeNotifier.kt
- **3 data deletes**: mood-tiers.txt, axis-keys.txt (main), axis-keys.txt (extended-test)
- **18 test deletes**: AxisKeyAssignmentSnapshotTest, MoodTierAssignmentSnapshotTest, StyleAxisTest, SyntaxMoodTest, SyntaxModeApplicatorTest (+ 3 axis variants), SyntaxModeServiceTest (+ 5 variants), SyntaxModeStateTest (+ persistence), SyntaxModeUpgradeNotifierTest, SyntaxModePanelGatingTest
- **9 plan-listed modifies**: plugin.xml (unregister legacy services), AyuIslandsLafListener.kt (callsite + import swap to SyntaxIntensityService), AyuIslandsAppListener.kt (block delete), SyntaxOverlayLoader.kt (shrink + runCatching → typed catch Pattern B), AyuIslandsAppListenerTest, AyuIslandsLafListenerSyntaxReapplyTest, AyuIslandsLafListenerTest, SyntaxOverlayLoaderTest, SyntaxOverlayLoaderBaselineTest (BLOCKER #2 axis-keys function removed)
- **6 KDoc-only auto-fix modifies** (Rule 3, surfaced by new Pattern L lock during Step H verification): RgbBlend.kt, EditorBackgroundFallbackTest.kt, SyntaxPresetCurvesTest.kt, SyntaxIntensityMigrationNotifierTest.kt, SyntaxIntensityServiceTest.kt, SyntaxIntensityStateTest.kt — all neutralized legacy class-name references and "Phase N / D-NN" mentions per MEMORY.md "post-Phase-48 comment audit"
- **1 add**: Phase49SunsetRegressionTest.kt (Pattern L lock with expanded scope per Codex suggestion — scans src/main/kotlin + src/test/kotlin, self-excludes 3 sibling narrow-scope locks)

## R-3 Atomic Invariant Honored

Single commit. plugin.xml `<applicationService>` removal landed in the SAME commit as the source-file deletes. No CI-red intermediate window.

## Codex HIGH #5 — Explicit Path-List Staging

`git rm <path>` + `git add <path>` per file. Zero unrelated dirty files staged (verified via `git status --short` at checkpoint; user re-verified). No `.planning/`, no docs, no orchestrator artefacts in the commit.

## Codex MEDIUM File-Count Reconciliation

Plan acceptance criterion said `>= 6 M` (round-2 reconciled count). Actual: 15 modifies. 9 extra:
- 3 modifies were always in `files_modified` enum but not counted in the round-2 "6" — plan-level inconsistency
- 6 modifies are KDoc-only Rule 3 auto-fix follow-ons surfaced when Step G's new Pattern L lock scanned the surviving tree and found orphan legacy references in 6 KDocs

All extras are zero-logic-change documentation neutralization. Verified during checkpoint review.

## Checkpoint Approval Evidence

Task 0 `checkpoint:human-verify` gate fired between Step H and Step I. The previous executor agent (`a6a7d0ef39c83ec4f`) returned a structured checkpoint state with:
- `git diff --cached --name-status` count: 27 D / 15 M / 1 A
- `git status --short`: zero unrelated dirty files
- Step H verification: compileKotlin / detekt / ktlintCheck / Phase49SunsetRegressionTest all GREEN
- Gemini LOW #4 orphan-import sweep: clean

User approval signal: **"approved"** (standard path — commit on current worktree branch).

## Gemini LOW #4 — Orphan Import Sweep

`./gradlew detekt 2>&1 | grep -iE "UnusedImports|UnusedPrivate" | grep -vE 'Phase49SunsetRegressionTest'` — zero findings. All surviving files cleaned of Phase 49 references before commit.

## Pattern L Lock Active — Expanded Scope

`Phase49SunsetRegressionTest.kt` (Step G) provides three Pattern L assertions:
1. `no legacy source symbols anywhere in src tree` — scans src/main/kotlin + src/test/kotlin for SyntaxMood, StyleAxis, SyntaxModeApplicator, SyntaxModeService, SyntaxModeState, SyntaxModeUpgradeNotifier; self-excludes own test file and 2 sibling narrow-scope locks (AyuIslandsSyntaxPanelTest, SettingsConfigurableSyntaxTabWiringTest)
2. `no legacy data files in resources` — asserts mood-tiers.txt, axis-keys.txt (main), axis-keys.txt (extended-test) absent
3. `plugin xml does not register legacy services` — asserts plugin.xml contains zero references to SyntaxModeService/SyntaxModeState/SyntaxModeUpgradeNotifier

3/3 tests GREEN.

## BLOCKER #2 Fix Evidence

`src/test/kotlin/dev/ayuislands/syntax/SyntaxOverlayLoaderBaselineTest.kt` still exists (file MUST survive per round-1 BLOCKER #2). `grep -c "axis-keys"` on the file returns 0 — the axis-keys-referencing test function was removed; all baseline parsing coverage tests preserved.

## INTENSITY-19 Overlay XML Retention

All three semantic-key overlay XMLs remain intact in `src/main/resources/themes/extended/`:
- `AyuIslandsMirage.extended.xml`
- `AyuIslandsDark.extended.xml`
- `AyuIslandsLight.extended.xml`

Not in the staged diff. They are the baseline semantic-key universe consumed by SyntaxOverlayLoader.

## Post-Commit Verification

| Check | Result |
|-------|--------|
| `./gradlew detekt` | BUILD SUCCESSFUL |
| `./gradlew ktlintCheck` | BUILD SUCCESSFUL |
| `./gradlew test` | BUILD SUCCESSFUL (full suite GREEN — previously failing SyntaxModePanelGatingTest gone with the sunset) |
| `./gradlew verifyPlugin` | BUILD SUCCESSFUL — 11 IDE products verified (RD-251, GO-251, PY-251, DB-251, RM-251, IC-251, IC-252, CL-253, WS-253, PS-253, IU-253) |

## verifyPlugin Report Excerpt

Reports saved to `build/reports/pluginVerifier/<IDE>-<version>/`. Dynamic plugin eligibility: "Plugin can probably be enabled or disabled without IDE restart" for all 11 products. Total verification time: 1 m 29 s. Deprecated/experimental API usages remain pre-existing (per project memory: `experimental-api-usages.txt` contains UIThemeLookAndFeelInfo + LafManager.getInstalledThemes — unavoidable core theme API).

## MEMORY.md Comment Audit

All 6 KDoc-only modifies plus the Step C/E source KDoc edits removed:
- "Phase 49" / "Phase 50" mentions
- Class-name refs on deleted classes (SyntaxModeService, SyntaxModeApplicator, SyntaxModeStatePersistenceRoundTripTest, SyntaxModeUpgradeNotifierTest)
- D-NN / Plan-NN-NN / Task-N references (D-13, D-23, OQ-02, Plan 49-04 SUMMARY, Plan 50-04 Task 2)

Replaced with neutral language describing what the code does.

## Hand-off

Wave 4 complete. The plugin is feature-complete at the source-tree level per the Phase 50A preset-driven architecture. Plan 50-08 (Wave 5) is the runIde live-smoke checkpoint — manual UAT across multiple languages, variants, and lifecycle events. It is non-autonomous and requires `runIde` invocation.

## Requirements Closed

- **INTENSITY-08** — LafListener wiring to new service (SyntaxIntensityService.getInstance().reapplyForActiveLaf, Pattern J gate on AyuVariant.isAyuActive)
- **INTENSITY-12** — Atomic Phase 49 delete + plugin.xml unregister in single commit
- **INTENSITY-19** — Overlay XMLs retained as baseline semantic-key universe

## Deviation

**Count discrepancy from plan acceptance criterion.** Plan said exactly `27 D + 6 M + 1 A`; actual `27 D + 15 M + 1 A`. The criterion was `>= 6 M`, so the actual count satisfies the minimum bar. Root cause: 6 KDoc-only Rule 3 auto-fix follow-ons surfaced by the new Pattern L lock during Step H verification — they were required to keep the new regression lock GREEN forever (orphan refs in 6 surviving KDocs would have failed the lock immediately). All 9 extra modifies are zero-logic-change documentation neutralization, also satisfying the MEMORY.md "post-Phase-48 comment audit" reminder as a side benefit. User reviewed all 6 KDoc diffs at checkpoint before approval.
