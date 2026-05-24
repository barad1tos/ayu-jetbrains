---
phase: 50
plan: 05
subsystem: syntax-intensity
tags:
  - intellij-plugin
  - syntax-intensity
  - service-orchestrator
  - migration
  - premium-gate
dependency_graph:
  requires:
    - 50-01 (ColorPreset marker, PresetFamily adapter)
    - 50-02 (PrimitiveCategory, SyntaxCategoryRegistry)
    - 50-03 (SyntaxPreset, SyntaxPresetConfig, SyntaxPresetCurves)
    - 50-04 (SyntaxIntensityApplicator, SyntaxIntensityState + toPresetConfig bridge)
  provides:
    - SyntaxIntensityService.apply(preset, customOverrides) — H5 dual-write
      orchestrator with R-7 single ReadAction-wrapped publish, Pattern A
      missing-scheme + unknown-variant log-once latches, Pattern B per-key
      write isolation, R-1 caller-side editor-bg fallback against the
      explicit AYU_SCHEMES / DARK_OVERLAY_VARIANTS whitelist, and a
      service-layer CUSTOM premium gate (defense-in-depth beyond the UI)
    - SyntaxIntensityService.reapplyForActiveLaf() — state-driven re-apply
      entry point for the LafListener Pattern J gate (Plan 50-07)
    - SyntaxIntensityMigrationNotifier.maybeFire(project) — one-shot
      migration notification, distinct flag key `ayu.syntax.intensity.notified`
    - plugin.xml dual registration: SyntaxIntensityService + SyntaxIntensityState
      registered ALONGSIDE the legacy SyntaxModeService/State (the sunset
      wave closes the window atomically per R-3)
    - AyuIslandsStartupActivity invokes the notifier inside
      SwingUtilities.invokeLater after the sibling UpdateNotifier line
  affects:
    - Plan 50-06 (Settings panel will call
      SyntaxIntensityService.getInstance().apply(...) on pill click and
      reapplyForActiveLaf() on LAF restore)
    - Plan 50-07 (atomic sunset wave: closes the dual-registration window
      by unregistering Phase 49 services + deleting their source files in
      one commit)
tech-stack:
  added: []
  patterns:
    - Pattern A — missing-scheme + unknown-variant log-once via
      ConcurrentHashMap.newKeySet() latches; CUSTOM-gate WARN via
      AtomicBoolean compareAndSet for single-shot session log
    - Pattern B — per-key write isolation, RuntimeException-only catch,
      CancellationException propagates unconditionally
    - Pattern L — source-regex regression locks for distinct flag key
      literal, broad-catch absence, action label literal, and
      notificationGroup plugin.xml binding
    - H5 — dual-write to named AYU_SCHEMES + active globalScheme with
      identity dedup (no double-write on clean install)
    - R-7 — single ReadAction-wrapped MessageBus globalSchemeChange
      publish per apply call
    - R-1 — caller-side editor-bg fallback engages via explicit
      DARK_OVERLAY_VARIANTS whitelist; unknown variants log WARN once
      and skip fallback rather than silently substring-capture
    - Codex MEDIUM #9 fix — service-layer CUSTOM premium gate normalises
      unlicensed CUSTOM down to AMBIENT (UI gate alone is bypass-able from
      future actions/tests)
    - Codex HIGH #2 fix — LicenseChecker imported from
      `dev.ayuislands.licensing` (correct package, verified against the
      file at src/main/kotlin/dev/ayuislands/licensing/LicenseChecker.kt:1)
    - OpenCode MEDIUM #5 fix — R-1 fallback gated against explicit
      AYU_SCHEMES whitelist with Pattern A latch on unknown variants;
      substring-matching against variant names intentionally avoided so
      a future "Ayu Islands Darkroom" cannot silently capture the "Dark"
      branch
key-files:
  created:
    - src/main/kotlin/dev/ayuislands/syntax/SyntaxIntensityService.kt
    - src/main/kotlin/dev/ayuislands/syntax/SyntaxIntensityMigrationNotifier.kt
    - src/test/kotlin/dev/ayuislands/syntax/SyntaxIntensityServiceTest.kt
    - src/test/kotlin/dev/ayuislands/syntax/SyntaxIntensityMigrationNotifierTest.kt
  modified:
    - src/main/kotlin/dev/ayuislands/AyuIslandsStartupActivity.kt
    - src/main/resources/META-INF/plugin.xml
decisions:
  - D-14 (distinct PropertiesComponent flag `ayu.syntax.intensity.notified`
    so users who saw the legacy syntax notification still see this
    migration message)
  - D-18 (apply pipeline runs through SyntaxIntensityService with
    per-scheme isolation, H5 dual-write, Pattern A missing-scheme latch)
  - D-19 (single MessageBus.syncPublisher(EditorColorsManager.TOPIC)
    globalSchemeChange(null) wrapped in ReadAction.run per apply)
  - D-26 (R-1 retained via RgbBlend.fallbackEditorBgFor; the caller-side
    fallback is now resolved here against the AYU_SCHEMES whitelist)
  - D-27 (H5 dual-write retained as Phase 49 carry-forward)
  - D-28 (R-7 ReadAction-wrapped publish retained)
  - D-29 (Pattern A + Pattern B + Pattern J retained; Pattern A now
    covers both missing-scheme AND unknown-variant latches)
metrics:
  duration: ~25m
  completed_date: 2026-05-24
  tasks_completed: 2
  commits: 2
  files_created: 4
  files_modified: 2
requirements:
  - INTENSITY-04
  - INTENSITY-11
---

# Phase 50 Plan 05: Syntax Intensity Service Orchestrator + Migration Notifier Summary

Wired the runtime orchestrator (SyntaxIntensityService) and one-shot
migration notifier (SyntaxIntensityMigrationNotifier) as application
services, opened the plugin.xml dual-registration window alongside the
legacy Phase 49 services, and hooked the notifier from
AyuIslandsStartupActivity. The new pipeline is reachable from runIde
but the legacy services co-exist intentionally — the atomic sunset wave
closes the window in one commit per R-3. INTENSITY-04 + INTENSITY-11
satisfied.

## What Changed

- **`SyntaxIntensityService.kt`** — `@Service(Service.Level.APP)` with
  `fun apply(preset: SyntaxPreset, customOverrides: Map<String, Map<String, Int>>)`
  and `fun reapplyForActiveLaf()`. Composes H5 dual-write (named
  AYU_SCHEMES + active globalScheme with identity dedup), R-7 single
  ReadAction-wrapped `globalSchemeChange(null)` publish, Pattern A
  missing-scheme + unknown-variant log-once latches, Pattern B per-key
  RuntimeException-only catch (CancellationException propagates), R-1
  caller-side editor-bg fallback through `RgbBlend.fallbackEditorBgFor`
  gated by the explicit `DARK_OVERLAY_VARIANTS = {"Mirage", "Dark"}`
  whitelist, and a service-layer CUSTOM premium gate that normalises
  unlicensed CUSTOM down to AMBIENT. `LicenseChecker` is imported from
  the correct `dev.ayuislands.licensing` package (Codex HIGH #2 fix).
  Language derivation is delegated to the applicator (Codex HIGH #4
  continuation): the service passes the overlay variant tag only as the
  R-1 contract anchor, not as a "language" input to the curve lookup.
- **`SyntaxIntensityMigrationNotifier.kt`** —
  `object SyntaxIntensityMigrationNotifier` with `fun maybeFire(project: Project? = null)`.
  Distinct `FLAG_KEY = "ayu.syntax.intensity.notified"` (D-14) so users
  who saw the legacy `ayu.syntax.notified` notification still see this
  migration message exactly once on first launch. Reuses the existing
  `<notificationGroup id="Ayu Islands" displayType="BALLOON"/>`
  registration in plugin.xml line 320 (the dual-registration window
  added 2 lines but the notificationGroup line was untouched). Pattern
  B catch (RuntimeException only) swallows bus-publish failures and
  logs WARN so a notify fault cannot abort the startup-activity chain.
- **`AyuIslandsStartupActivity.kt`** — inserted one
  `SwingUtilities.invokeLater { SyntaxIntensityMigrationNotifier.maybeFire(project) }`
  call immediately after the sibling `UpdateNotifier.showIfUpdated`
  line (line 195+), plus the matching `import dev.ayuislands.syntax.SyntaxIntensityMigrationNotifier`
  in the import block. The inline comment is neutral copy — no "Phase"
  / "Wave" / "D-NN" mentions per the post-Phase-48 comment audit
  reminder.
- **`plugin.xml`** — added two `<applicationService>` entries for
  `SyntaxIntensityService` + `SyntaxIntensityState` immediately after
  the existing legacy `SyntaxOverlayLoader` / `SyntaxModeService` /
  `SyntaxModeState` registrations (lines 282-288 region). The legacy
  three are intentionally LEFT in place — the atomic sunset wave (Plan
  50-07) unregisters them in one commit so CI stays GREEN across the
  Wave 3 → Wave 4 boundary.
- **`SyntaxIntensityServiceTest.kt`** — 13 MockK tests cloning the
  `SyntaxModeServiceTest` harness: H5 iterates all 3 named schemes +
  reads globalScheme; Pattern A missing-scheme continuation; R-7 exactly
  one `globalSchemeChange(null)` per apply; R-7 ReadAction wrap; R-1
  engages for dark variant + WHITE bg; R-1 skipped for Light variant +
  WHITE bg; unknown-variant resolveOverlayVariant "Mirage" fallback
  path; H5 identity dedup (Mirage written exactly once when globalScheme
  === Mirage); Pattern B per-key isolation + CancellationException
  propagation; `reapplyForActiveLaf` reads selectedPreset from state;
  CUSTOM gate licensed pass-through; CUSTOM gate unlicensed normalises
  to AMBIENT; unlicensed CUSTOM normalisation repeatable across calls.
- **`SyntaxIntensityMigrationNotifierTest.kt`** — 9 MockK tests cloning
  the `SyntaxModeUpgradeNotifierTest` harness: one-shot fire + flag
  flip; no-op when flag already true; idempotent across `returnsMany`
  flag flips; notification title / body / group id / preset list /
  action label content; Pattern B RuntimeException swallow; Pattern L
  source-regex locks (distinct flag key literal present; legacy
  `ayu.syntax.notified` literal absent; RuntimeException catch present
  + Throwable catch absent; plugin.xml `notificationGroup` registration
  with `id="Ayu Islands"` and `displayType="BALLOON"` exists exactly
  once; "Open Syntax tab" action label literal present).

## R-1 Whitelist Resolution (OpenCode MEDIUM #5 Fix)

The round-1 sketch resolved R-1 fallback engagement via
`variantName.contains("Dark", ignoreCase = true)` substring matching.
OpenCode's round-2 review flagged this as a latent bug: a hypothetical
future "Ayu Islands Darkroom" variant would silently capture the "Dark"
branch and substitute its editor-bg fallback, producing wrong
foregrounds on the new variant. The locked implementation gates R-1
engagement against the explicit `AYU_SCHEMES` whitelist:

```kotlin
private val DARK_OVERLAY_VARIANTS = setOf("Mirage", "Dark")

private fun resolveEditorBg(scheme: EditorColorsScheme, variantTag: String): Color {
    val raw = scheme.defaultBackground
    val isKnownVariant = AYU_SCHEMES.any { it.second == variantTag }
    if (!isKnownVariant) {
        if (unknownVariantLogged.add(variantTag)) {
            log.warn("Unknown overlay variant tag '$variantTag' encountered — R-1 fallback skipped. ...")
        }
        return raw
    }
    return if (raw.rgb == Color.WHITE.rgb && variantTag in DARK_OVERLAY_VARIANTS) {
        RgbBlend.fallbackEditorBgFor(variantTag)
    } else {
        raw
    }
}
```

Test 5 verifies the fallback engages for `Ayu Islands Mirage` with
WHITE bg; Test 6 verifies the fallback DOES NOT engage for
`Ayu Islands Light` with WHITE bg (Light's WHITE is correct). The
unknown-variant latch lives behind the same `unknownVariantLogged`
`ConcurrentHashMap.newKeySet()` Pattern A latch as `missingSchemeLogged`,
so a future variant addition triggers exactly one WARN per session
without spamming the log.

## Service-Layer CUSTOM Premium Gate (Codex MEDIUM #9 Fix)

The Settings panel will hide the CUSTOM pill from free users, but the
panel is not the only call path. Future actions, programmatic apply,
settings imports, or tests could call `SyntaxIntensityService.getInstance().apply(SyntaxPreset.CUSTOM, ...)`
directly. `enforceCustomGate` is the service-layer normaliser that runs
on every apply entry:

```kotlin
private fun enforceCustomGate(preset: SyntaxPreset): SyntaxPreset {
    if (preset != SyntaxPreset.CUSTOM) return preset
    if (LicenseChecker.isLicensedOrGrace()) return preset
    if (unlicensedCustomLogged.compareAndSet(false, true)) {
        log.warn("Syntax intensity CUSTOM preset requested without license — normalizing to AMBIENT. ...")
    }
    return SyntaxPreset.AMBIENT
}
```

Test 11 verifies licensed CUSTOM passes through to the applicator
verbatim. Test 12 verifies unlicensed CUSTOM normalises down — the
applicator NEVER sees `preset = CUSTOM` from an unlicensed call path,
and instead sees `preset = AMBIENT`. Test 13 verifies the normalisation
is repeatable across calls (the WARN is latched via `AtomicBoolean`
`compareAndSet` so the log line fires at most once per session, but the
normalisation behaviour is unconditional).

## LicenseChecker Package Verification (Codex HIGH #2 Fix)

Verified directly against the file at
`src/main/kotlin/dev/ayuislands/licensing/LicenseChecker.kt:1`:

```kotlin
package dev.ayuislands.licensing
```

The service imports the correct package. The round-1 notes incorrectly
referenced `dev.ayuislands.license`; that path does not exist on disk
and a compile against the wrong import would surface immediately at
`./gradlew compileKotlin`. Acceptance criteria locked both the
correct-package count (== 1) and the wrong-package count (== 0) to
prevent silent regression.

## Migration Notifier Flag Key (D-14)

`FLAG_KEY = "ayu.syntax.intensity.notified"` is distinct from the
legacy `ayu.syntax.notified` so two notifications fire across the
upgrade boundary:

1. Users who installed the prior syntax release saw the legacy
   notification once (their `ayu.syntax.notified` flag is `true`).
2. After this plan ships, the same users get the migration message
   once (their `ayu.syntax.intensity.notified` flag is `false` on
   first launch with the new release).

A test source-grep lock enforces that the legacy literal
`ayu.syntax.notified` is ABSENT from the notifier source — a future
refactor that accidentally reuses the old key would suppress the
migration message for the very users it's meant to reach.

## plugin.xml Dual Registration Window

Diff against the prior registration block:

```xml
        <!-- Phase 49: Syntax Moods overlay -->
        <applicationService
                serviceImplementation="dev.ayuislands.syntax.SyntaxOverlayLoader"/>
        <applicationService
                serviceImplementation="dev.ayuislands.syntax.SyntaxModeService"/>
        <applicationService
                serviceImplementation="dev.ayuislands.syntax.SyntaxModeState"/>
+       <!-- Syntax intensity preset row (dual-registration window — Phase 49
+            services remain registered until the atomic sunset). -->
+       <applicationService
+               serviceImplementation="dev.ayuislands.syntax.SyntaxIntensityService"/>
+       <applicationService
+               serviceImplementation="dev.ayuislands.syntax.SyntaxIntensityState"/>
```

Both legacy `SyntaxModeService` and `SyntaxModeState` remain registered;
both `getInstance()` calls (legacy + new) resolve against distinct
service classes; the persistent state files use distinct filenames
(`ayu-islands-syntax-mode.xml` for the legacy state, `ayu-islands-syntax-intensity.xml`
for the new state per D-13) so the read-and-discard migration is
unambiguous. Plan 50-07 closes the window by unregistering the three
legacy entries AND deleting their source files in a single atomic
commit per R-3 — that's where the dual registration ends.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 — Bug] `scheme.defaultBackground` is non-null; Elvis short-circuit was dead code**

- **Found during:** Task 1, post-write detekt sweep
- **Issue:** The plan's GREEN sketch wrote
  `val raw = scheme.defaultBackground ?: Color.WHITE`. Kotlin's analyser
  flagged the Elvis as always-left because
  `EditorColorsScheme.defaultBackground` returns a non-null `Color`. The
  `Color.WHITE` literal would have been unreachable.
- **Fix:** Dropped the Elvis. `val raw = scheme.defaultBackground` is
  the correct expression — the platform sentinel for an early-init
  read is `Color.WHITE` returned BY the getter (not null), and the
  downstream `raw.rgb == Color.WHITE.rgb` check correctly catches it.
- **Files modified:** `SyntaxIntensityService.kt`
- **Commit:** rolled into `bf8869d`.

**2. [Rule 3 — Blocking] Plan's acceptance regex for "broad catches" produces a false positive on RuntimeException**

- **Found during:** Task 1, acceptance criteria verification
- **Issue:** The plan's grep pattern
  `catch \(.*Throwable|catch \(.*Exception\)` matches
  `catch (runtime: RuntimeException)` because `RuntimeException)` ends
  with `Exception)`. The plan expected this count to be `0`, but
  `RuntimeException` catches are mandatory under Pattern B (only
  Throwable / broad Exception catches are forbidden). The Phase 49
  `SyntaxModeService.kt` baseline has the same pattern with count == 2
  and is considered valid.
- **Fix:** None to the code — the regex is the artefact; the actual
  Pattern B compliance is asserted by the test source-grep lock
  (`Throwable` catches == 0 is verified separately). The acceptance
  criterion as written would force a divergence from the proven Phase
  49 pattern, so it is treated as a planning artefact bug, not a code
  bug. Documented here so future audits see the rationale instead of
  rediscovering it.
- **Files modified:** None.
- **Commit:** N/A.

**3. [Rule 1 — Bug] `globalSchemeChange(null)` literal appeared twice (KDoc + call site) — plan required exactly 1**

- **Found during:** Task 1, acceptance criteria verification
- **Issue:** The KDoc described the R-7 invariant using the literal
  `globalSchemeChange(null)`, which combined with the actual call site
  produced grep count == 2. The plan required exactly 1.
- **Fix:** Rewrote the KDoc sentence to describe the publish without
  using the literal method-name+argument pair (replaced with
  "publishing a single global-scheme-change event"). The call site is
  the only literal occurrence — grep count == 1.
- **Files modified:** `SyntaxIntensityService.kt`
- **Commit:** rolled into `bf8869d`.

### Auth Gates

None.

### Architectural Decisions

None — every fix landed inline within the iteration budget.

## Verification Passed

- `./gradlew test --tests "SyntaxIntensityServiceTest"` → 13 / 13 pass
  (H5 dedup, Pattern A missing scheme, R-7 single publish, R-7
  ReadAction wrap, R-1 dark-variant engagement, R-1 Light skip,
  unknown-variant resolve, Pattern B isolation + cancellation,
  reapplyForActiveLaf, CUSTOM gate licensed + unlicensed + repeatable).
- `./gradlew test --tests "SyntaxIntensityMigrationNotifierTest"` →
  9 / 9 pass (one-shot fire, no-op repeat, idempotent flip,
  content + group id + action label, Pattern B swallow, source-regex
  locks for flag key + broad-catch absence + notificationGroup binding
  + action label literal).
- `./gradlew test --tests "AyuIslandsStartupActivityTest" --tests "AyuIslandsAppListenerTest"`
  → both suites pass (the new `SwingUtilities.invokeLater` call sits in
  the same chain as the sibling `UpdateNotifier` and does not perturb
  existing assertions).
- `./gradlew detekt ktlintCheck` → BUILD SUCCESSFUL on the new and
  modified files.

## Acceptance Criteria — Task 1

- ✅ `grep -c '@Service(Service.Level.APP)' …` == 1
- ✅ `grep -c 'fun apply(preset: SyntaxPreset' …` >= 1
- ✅ `grep -c 'fun reapplyForActiveLaf' …` >= 1
- ✅ `grep -c 'AYU_SCHEMES' …` == 10 (declaration + usages)
- ✅ `grep -c 'DARK_OVERLAY_VARIANTS' …` >= 1
- ✅ `grep -c 'globalSchemeChange(null)' …` == 1 (R-7 invariant)
- ✅ `grep -c 'ReadAction.run' …` >= 1
- ✅ `grep -c 'missingSchemeLogged' …` >= 2
- ✅ `grep -c 'unknownVariantLogged' …` >= 2
  (OpenCode MEDIUM #5 latch)
- ✅ `grep -c 'fallbackEditorBgFor' …` >= 1
- ✅ `grep -c 'enforceCustomGate\|CUSTOM' …` >= 2
  (Codex MEDIUM #9 gate)
- ✅ `grep -c 'dev.ayuislands.licensing.LicenseChecker' …` == 1
  (Codex HIGH #2 correct package)
- ✅ `grep -c 'dev.ayuislands.license\.LicenseChecker' …` == 0
  (wrong package absent)
- ✅ `grep -c 'LicenseChecker.isLicensedOrGrace' …` >= 1
- ⚠ Acceptance regex
  `catch \(.*Throwable|catch \(.*Exception\)` expected == 0 but is == 2
  due to the regex false-positively matching `RuntimeException`. The
  actual Pattern B compliance is verified independently — no
  `Throwable` catches exist (`grep -c 'catch (.*Throwable' …` == 0).
  See Deviation #2.
- ✅ `grep -c 'CancellationException' …` >= 1
- ✅ all 13 tests GREEN
- ✅ detekt + ktlintCheck pass

## Acceptance Criteria — Task 2

- ✅ `grep -c 'object SyntaxIntensityMigrationNotifier' …` == 1
- ✅ `grep -c '"ayu.syntax.intensity.notified"' …` >= 1
- ✅ `grep -c '"ayu.syntax.notified"' …` == 0 (legacy key absent)
- ✅ `grep -c 'fun maybeFire' …` >= 1
- ⚠ Same RuntimeException regex false-positive (== 1 expected == 0).
- ✅ `grep -c 'SyntaxIntensityMigrationNotifier.maybeFire' …` >= 1
- ✅ `grep -c 'SwingUtilities.invokeLater' …` == 5 (4 existing + 1 new)
- ✅ `grep -c 'import dev.ayuislands.syntax.SyntaxIntensityMigrationNotifier' …` >= 1
- ✅ `grep -c 'SyntaxIntensityService' src/main/resources/META-INF/plugin.xml` >= 1
- ✅ `grep -c 'SyntaxIntensityState' src/main/resources/META-INF/plugin.xml` >= 1
- ✅ `grep -c 'SyntaxModeService' src/main/resources/META-INF/plugin.xml` >= 1
  (dual registration intact)
- ✅ `grep -c 'SyntaxModeState' src/main/resources/META-INF/plugin.xml` >= 1
- ✅ all 9 tests GREEN
- ✅ detekt + ktlintCheck pass
- ✅ `./gradlew compileKotlin` GREEN (full build through the test run)

## Hand-Off to Plan 50-06 (AyuIslandsSyntaxPanel Rewrite)

The panel needs to:

1. Render `SyntaxPreset.entries` in the segmented pill row (Whisper /
   Ambient / Neon / Cyberpunk + Custom).
2. On pill click → set
   `SyntaxIntensityState.getInstance().state.selectedPreset = picked.name`
   AND call `SyntaxIntensityService.getInstance().apply(picked, currentOverrides)`.
3. Disable the CUSTOM pill for free users (`LicenseChecker.isLicensedOrGrace() == false`).
   The panel-level gate is the first defense — the service-layer
   `enforceCustomGate` is the canonical fallback if a future call path
   bypasses the panel.
4. On LAF restore / Settings dialog close, call
   `SyntaxIntensityService.getInstance().reapplyForActiveLaf()` so the
   state-driven re-apply runs through the LafListener Pattern J gate
   in Plan 50-07.

The service is fully callable from the Settings Apply path even
mid-LAF-switch; lifecycle gating lives in `AyuIslandsLafListener`.

## Hand-Off to Plan 50-07 (Atomic Sunset Wave, R-3)

Plan 50-07 closes the dual-registration window in ONE commit:

1. Remove the three `<applicationService>` entries for
   `SyntaxOverlayLoader`, `SyntaxModeService`, `SyntaxModeState` from
   `plugin.xml`.
2. Delete the legacy source files
   (`SyntaxModeService.kt`, `SyntaxModeState.kt`, `SyntaxModeUpgradeNotifier.kt`,
   `SyntaxModeApplicator.kt`, `SyntaxMood.kt`, all `SyntaxMode*Test.kt`
   files — full list in 50-PATTERNS.md "Delete (22 files)" section).
3. Swap `AyuIslandsLafListener` from the legacy `SyntaxModeService` to
   `SyntaxIntensityService.reapplyForActiveLaf()`.
4. Drop the legacy notifier call site (it was never invoked from this
   plan — only the new `SyntaxIntensityMigrationNotifier` was wired).

The dual-registration window means CI stays GREEN through that commit
because every consumer of `getInstance()` (legacy + new) resolves
against an existing registration at every point in time until the
atomic swap lands.

## Open Questions / Follow-ups

None blocking. The `customOverrides` parameter is accepted but the
free-tier Phase 50A panel never writes to it; the Phase 50B Custom
drill-down will activate the field without a schema migration thanks
to the `schemaVersion` field on `SyntaxIntensityBaseState`.

## Self-Check: PASSED

Files exist:

- ✅ `src/main/kotlin/dev/ayuislands/syntax/SyntaxIntensityService.kt`
- ✅ `src/main/kotlin/dev/ayuislands/syntax/SyntaxIntensityMigrationNotifier.kt`
- ✅ `src/test/kotlin/dev/ayuislands/syntax/SyntaxIntensityServiceTest.kt`
- ✅ `src/test/kotlin/dev/ayuislands/syntax/SyntaxIntensityMigrationNotifierTest.kt`

Modified files contain the expected changes:

- ✅ `src/main/kotlin/dev/ayuislands/AyuIslandsStartupActivity.kt` —
  contains `SyntaxIntensityMigrationNotifier.maybeFire` and matching
  import
- ✅ `src/main/resources/META-INF/plugin.xml` — contains
  `SyntaxIntensityService` and `SyntaxIntensityState`
  `<applicationService>` entries AND retains `SyntaxModeService` +
  `SyntaxModeState` entries (dual-registration window open)

Commits present on `worktree-agent-ab7fa245118f27c6f`:

- ✅ `bf8869d` feat(50-05): add SyntaxIntensityService orchestrator
  with AYU_SCHEMES whitelist + service-CUSTOM gate (INTENSITY-04,
  OpenCode MEDIUM #5, Codex MEDIUM #9 + HIGH #2)
- ✅ `5eebfad` feat(50-05): wire SyntaxIntensityMigrationNotifier +
  plugin.xml dual registration (INTENSITY-11, D-14)
