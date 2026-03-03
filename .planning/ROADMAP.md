# Roadmap: Ayu Islands

## Current Milestone: v2.0.0 Visual Effects

**Goal:** Add accent color customization (10 presets, per-element toggles, conflict detection), neon glow effects, and freemium licensing via JetBrains Marketplace paid plugin API. First Kotlin code in the plugin.

## Phases

- [x] **Phase 5: Kotlin Build Infrastructure** - Add Kotlin 2.1.10 compilation to the existing theme-only plugin without breaking any themes
- [x] **Phase 6: Accent Borders (Free)** - ~~Accent-colored island borders~~ CANCELLED — platform rendering limitations (pixel artifacts, thick adjacency)
- [x] **Phase 7: Settings and State** - Settings panel under Appearance with accent color selection, persistent across IDE restarts
- [x] **Phase 8: Freemium Licensing** - JetBrains Marketplace paid plugin integration with free/paid feature boundary
- [x] **Phase 9: Accent Color Picker and Glow Spike** - 10 Ayu accent presets with per-element toggles, visual preview, conflict detection, and glow rendering feasibility validation (paid)
- [x] **Phase 10: Neon Glow Effects** - Full glow rendering on island borders, active tabs, and focused inputs (paid) (completed 2026-02-28)

## Phase Details

### Phase 5: Kotlin Build Infrastructure
**Goal**: Plugin compiles Kotlin code and all 6 existing themes still load correctly across 12 IDE targets
**Depends on**: Nothing (first phase of v2.0.0 milestone)
**Requirements**: BUILD-01, BUILD-02
**Success Criteria** (what must be TRUE):
  1. `./gradlew buildPlugin` succeeds with Kotlin 2.1.10 JVM plugin configured
  2. All 6 existing themes load and render correctly in a sandboxed IDE (no visual regression)
  3. `./gradlew verifyPlugin` passes across all 12 IDE targets (IC, IU, PS, WS, CL, RR, GO, PY, RD, DB, AI, AS)
**Plans:** 1 plan
Plans:
- [x] 05-01-PLAN.md -- Add Kotlin JVM plugin, PostStartupActivity, and verify all 12 IDE targets

### Phase 6: Accent Borders (Free) — CANCELLED
**Goal**: ~~Island boundaries show a subtle accent-colored border in all 3 Islands UI theme variants~~
**Cancelled**: Platform rendering limitations — Island.borderColor gap fill produces pixel artifacts on rounded corners (no anti-aliasing) and thick adjacency between panels (double-thickness gap). Kotlin/Graphics2D approach uses the same LAF renderer, so no fix possible. Accent color will be applied to properly-rendered UI elements (tabs, focus rings, selection) in Phase 9 instead.
**Requirements**: ~~BORDER-01, BORDER-02~~ (dropped)

### Phase 7: Settings and State
**Goal**: Users can configure accent color in IDE Settings, and their choice persists across IDE restarts
**Depends on**: Phase 5 (requires Kotlin compilation)
**Requirements**: SET-01, SET-02, SET-03
**Success Criteria** (what must be TRUE):
  1. Settings panel appears under Appearance > Ayu Islands in IDE Settings dialog
  2. User can select an accent color from the settings panel
  3. Selected accent color survives IDE restart (verified by closing and reopening IDE)
  4. Each variant (Mirage, Dark, Light) starts with an appropriate default accent color
**Plans:** 2 plans
Plans:
- [x] 07-01-PLAN.md -- Data model, accent presets, variant detection, state persistence, and accent applicator
- [x] 07-02-PLAN.md -- Settings panel UI, plugin.xml registration, startup integration, and verification

### Phase 8: Freemium Licensing
**Goal**: Free features work without a license; paid features prompt for license when unlicensed
**Depends on**: Phase 7 (settings panel needed for license status display)
**Requirements**: LIC-01, LIC-02, LIC-03, LIC-04
**Success Criteria** (what must be TRUE):
  1. plugin.xml contains product-descriptor with `optional="true"` for freemium model
  2. All 6 base themes work without any license (free tier)
  3. Attempting to use a paid feature without a license shows a non-hostile "license required" prompt
  4. Licensed users can access paid features without friction (no extra steps after purchase)
  5. LicensingFacade null on cold start is handled gracefully (not treated as "unlicensed")
**Plans:** 1 plan
Plans:
- [x] 08-01-PLAN.md -- LicenseChecker utility, product-descriptor, license-aware settings footer, startup license check

### Phase 9: Accent Color Picker and Glow Spike
**Goal**: Licensed users can choose from 10 Ayu accent colors with per-element toggles, visual preview, and third-party conflict detection; glow rendering feasibility validated
**Depends on**: Phase 8 (requires licensing for paid feature gate)
**Requirements**: ACCENT-01, ACCENT-02, ACCENT-03, ACCENT-04, ACCENT-05, ACCENT-06, ACCENT-07, GLOW-01
**Success Criteria** (what must be TRUE):
  1. 10 predefined Ayu accent colors are selectable in the settings panel
  2. Selected accent color applies visibly to UI elements (tabs, focus rings, selection backgrounds) at runtime without IDE restart
  3. Accent color works consistently across all 3 variants (Mirage, Dark, Light) and both UI sub-variants
  4. Per-element toggle checkboxes let users choose which UI elements receive accent theming
  5. Visual preview in settings panel shows changes before applying
  6. Conflict detection warns when Atom Material Icons or CodeGlancePro interfere with accent theming
  7. CodeGlancePro viewport color set via LafManagerListener + reflection when plugin detected
  8. Glow spike produces a working prototype that renders glow on at least one island panel border with acceptable performance (no visible IDE lag)
**Plans:** 4 plans
Plans:
- [x] 09-01-PLAN.md -- AccentElement interface, ConflictRegistry, state extension, plugin.xml EP (Wave 1)
- [x] 09-02-PLAN.md -- 8 AccentElement implementations + AccentApplicator refactor (Wave 2)
- [x] 09-03-PLAN.md -- Settings UI panels, CGP integration, LafListener (Wave 2)
- [x] 09-04-PLAN.md -- Glow spike: GlowRenderer + GlowPanel + PreviewPanel integration (Wave 3)

**Phase 6 learnings informing Phase 9:**
- Accent defaults already committed (f0d58ad) — baseline elements: tab underlines, caret row, ProgressBar, Link colors, matched bracket bg, search result bg, ScrollBar.Mac.* hover, Checkbox via icons.ColorPalette
- Three theme control levels: XML colors/attributes (editor), JSON ui (UI chrome), JSON icons.ColorPalette (SVG patching)
- Atom Material Icons replaces checkbox SVGs entirely — no theme-level workaround, needs conflict warning
- HTML links in notifications use hardcoded blue — unthemeable, exclude from accent elements
- ScrollBar requires Mac-specific keys (ScrollBar.Mac.*)

### Phase 10: Neon Glow Effects
**Goal**: Licensed users can enable neon glow that renders on island borders, active tabs, and focused inputs, following the selected accent color
**Depends on**: Phase 9 (requires validated glow spike and accent color system)
**Requirements**: GLOW-02, GLOW-03, GLOW-04, GLOW-05, GLOW-06
**Success Criteria** (what must be TRUE):
  1. Neon glow renders on island panel borders via Graphics2D without visible IDE lag
  2. Neon glow renders on active tab indicator and focused input fields
  3. Glow color automatically follows the user's selected accent color
  4. Glow toggle in settings (on/off, default off) controls glow visibility
  5. Glow works across all 3 color variants (Mirage, Dark, Light) and both UI sub-variants

**Plans:** 6/6 plans complete
Plans:
- [x] 10-01-PLAN.md -- Glow enums, presets, state fields, GlowRenderer multi-style
- [x] 10-02-PLAN.md -- GlowLayerUI, GlowOverlayManager tool window discovery + JLayer
- [x] 10-03-PLAN.md -- Effects settings panel (4 tabs), preview panel
- [x] 10-04-PLAN.md -- Animation system, configurable wiring, startup/LAF integration
- [x] 10-05-PLAN.md -- Tab glow painter + focus-ring border + EffectsPanel targets
- [ ] 10-06-PLAN.md -- Gap closure: wire GlowTabPainter into JBEditorTabs via JLayer hookup

## Progress

**Execution Order:** Phase 5 > 6 > 7 > 8 > 9 > 10

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 5. Kotlin Build Infrastructure | 1/1 | Complete | 2026-02-26 |
| 6. Accent Borders (Free) | — | Cancelled | 2026-02-27 |
| 7. Settings and State | 2/2 | Complete | 2026-02-27 |
| 8. Freemium Licensing | 1/1 | Complete | 2026-02-28 |
| 9. Accent Picker + Glow Spike | 4/4 | Complete | 2026-02-28 |
| 10. Neon Glow Effects | 6/6 | Complete    | 2026-02-28 |

## Completed Milestones

<details>
<summary>v2.0 — Three Variants (2026-02-24 > 2026-02-26)</summary>

Expanded from 1 Mirage variant to 6 themes (Mirage, Dark, Light x base + Islands UI), with 20+ language highlights including C#/.NET. Published to JetBrains Marketplace (ID 30373).

**Phases:**
1. Build Infrastructure — Gradle 8.13, Plugin 2.10.5, 12 IDE verifier targets
2. Ayu Dark Variant — Dark palette theme.json + editor scheme XML
3. Ayu Light Variant — Light palette with inverted inheritance chain
4. Language Coverage and QA — 20+ languages, 541 attributes per variant, cross-variant consistency
4.1. C#/.NET Attributes — 62 ReSharper attributes for Rider support

**Stats:** 5 phases, 13 plans, 24/24 requirements, 28 files changed (+14,081/-1,519)

**Archive:** `milestones/v2.0-ROADMAP.md`, `milestones/v2.0-REQUIREMENTS.md`, `milestones/v2.0-phases/`

</details>

---
*Last updated: 2026-03-02 — Phase 10.1.1 planned (2 plans)*

### Phase 10.2: Fix inspections: dead code cleanup, code quality, and Glow integration (INSERTED)

**Goal:** Resolve all IDE inspection warnings: remove dead code, deduplicate display name logic, fix UI string capitalization, apply code quality improvements, convert abstract class to interface, and integrate or delete unused Glow files
**Requirements**: None (inserted phase, no formal requirement IDs)
**Depends on:** Phase 10.1.1
**Success Criteria** (what must be TRUE):
  1. No dead code flagged by inspections remains (unused methods, properties, files removed)
  2. `elementDisplayName()` consolidated to `AccentElementId.displayName` enum property
  3. UI strings follow JetBrains sentence-case convention
  4. `AyuIslandsSettingsPanel` is an interface
  5. GlowLayerUI, GlowIslandBorder, GlowPanel, GlowPreset either integrated or deleted
  6. `./gradlew buildPlugin` succeeds
**Plans:** 2/2 plans complete

Plans:
- [ ] 10.2-01-PLAN.md -- Dead code cleanup, deduplication, UI strings, code quality, interface conversion
- [ ] 10.2-02-PLAN.md -- Glow dead file integration and GlowOverlayManager cleanup

### Phase 10.1: Settings Panel UX Overhaul (INSERTED)

**Goal:** Restructure the settings panel to eliminate vertical scroll, improve Effects discoverability via top-level JBTabbedPane, and clean up UX inconsistencies (reset button naming, orphan sections, master toggle visibility)
**Requirements**: None (urgent insertion, no formal requirement IDs)
**Depends on:** Phase 10
**Success Criteria** (what must be TRUE):
  1. All settings content fits without scrolling in a standard settings dialog
  2. Effects section is a top-level tab peer of Accent — immediately discoverable
  3. Preview strip visible above tabs — persistent across tab switches
  4. Reset buttons have distinct scope-clear names ("Reset Color", "Reset Style Defaults", "Reset All Settings")
  5. Master glow toggle visible at top of Effects tab — not hidden in Targets sub-tab
  6. `./gradlew buildPlugin` succeeds
**Plans:** 2/2 plans complete

Plans:
- [ ] 10.1-01-PLAN.md -- Sub-panel preparation: rename resets, absorb integrations, extract master toggle, compact preview
- [ ] 10.1-02-PLAN.md -- Configurable tab restructuring: JBTabbedPane, preview strip, footer, callback rewiring

### Phase 10.1.1: Fix freemium marketplace listing and license purchase 404 (INSERTED)

**Goal:** Eliminate dev-mode license bypass in release builds, align plugin version with JetBrains release-version requirements, update Marketplace listing for freemium, and configure vendor portal Sales Info to enable purchase links
**Requirements**: MKT-01, MKT-02, MKT-03, MKT-04
**Depends on:** Phase 10.1
**Success Criteria** (what must be TRUE):
  1. `isDevBuild()` returns false in release builds (uses system property, not resource marker)
  2. `pluginVersion` (2026.1.0) aligns with `release-version` (20261) per JetBrains requirements
  3. Plugin description and change-notes communicate freemium features and v2 changes
  4. Marketplace API returns `pricingModel: FREEMIUM` after vendor portal configuration and JetBrains approval
  5. `./gradlew buildPlugin` succeeds and built JAR contains no `ayu-dev-mode` marker
**Plans:** 1/2 plans executed

Plans:
- [ ] 10.1.1-01-PLAN.md -- Fix dev-mode detection, version alignment, plugin metadata update
- [ ] 10.1.1-02-PLAN.md -- Build verification and Marketplace vendor portal configuration
