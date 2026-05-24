# Requirements — v2.6.0 Peacock Parity

> Previous milestone snapshots: v2.4.0 Onboarding Redesign at `.planning/milestones/v2.4.0-REQUIREMENTS.md`. v2.9 Visual Focus & Flow cancelled 2026-04-22 before ship — snapshot at `.planning/milestones/v2.9-REQUIREMENTS.md`, archived planning at `.planning/milestones/v2.9-phases/`.



> Milestone goal: elevate per-project accent to full VSCode Peacock parity. Tint IDE chrome, make accent VCS-shareable, add remote-session awareness, and add the Peacock QoL layer (darken/lighten, favorites, actions, status bar widget, surprise-on-startup). All features premium (consistent with existing accent-mapping gating). Hard limit: native macOS/Linux-SSD title bars outside JBR custom decorations remain un-tintable — same constraint Peacock hits on macOS.

## Chrome Tinting

- [ ] **CHROME-01**: User enables Status Bar tinting in Settings → Ayu Islands → Chrome Tint; status bar background renders the resolved accent at the configured intensity.
- [ ] **CHROME-02**: User enables Main Toolbar / Title Bar tinting; header renders accent when JBR custom decorations are active. Toggle is disabled with a per-OS honest comment when native OS chrome is detected.

  **Platform support note (2026-04-22):** MainToolbar tint is available only when the IDE runs with JBR custom window decorations. JetBrains removed the user-facing "Merge main menu with window title" toggle and the `ide.mac.bigSurStyle` / `ide.mac.transparentTitleBarAppearance` registry entries in IDE 2026.1+, making custom title bar painting impossible on macOS for that version onward. On Windows 10/11, JBR enables custom decorations by default (MainToolbar tint works out-of-the-box). On Linux, it depends on the window manager. The Chrome Tinting settings panel shows a disabled Main Toolbar row with a per-OS comment explaining why the feature is unavailable in the user's current environment.
- [ ] **CHROME-03**: User enables Tool Window Stripe tinting; active stripe button renders accent background, hover states blend at configured intensity.
- [ ] **CHROME-04**: User enables Navigation Bar tinting; navbar background renders accent.
- [ ] **CHROME-05**: User enables Panel Border tinting; split dividers and OnePixelDivider render accent.
- [ ] **CHROME-06**: User adjusts the Intensity slider (10–100 %); all enabled tints blend at the configured alpha without requiring toggle-off/on.
    - Intensity slider implementation note: `ChromeTintBlender.blend` applies HSB-space hue replacement (uniform hue across surfaces per Gap 1) combined with a partial (50%) brightness pull toward the accent. Full brightness preservation made the slider imperceptible on dark theme bases; full brightness lerp would flatten the luma hierarchy. 50% is the calibrated compromise.
- [ ] **CHROME-07**: User toggles "Keep foreground readable"; contrast-aware foreground color is applied to tinted status bar / title bar text.
- [ ] **CHROME-08**: All chrome-tint elements cleanly revert to theme defaults on license loss, toggle-off, or switch to a non-Ayu theme — no residual UIManager keys left mutated.

## Shared Project Accent (`.idea/ayuAccent.xml`)

- [ ] **SHARED-01**: User triggers "Pin accent — shared via .idea/" from the Accent panel or Project View context menu; state persists in `.idea/ayuAccent.xml` as a `@Service(Service.Level.PROJECT)` PersistentStateComponent.
- [ ] **SHARED-02**: A teammate who pulls the repo sees the shared accent applied automatically on next IDE open for that project, with no local config required.
- [ ] **SHARED-03**: User triggers "Pin accent — personal, this install only"; state persists in the existing application-level `AccentMappingsState.projectAccents` path and does not enter `.idea/`.
- [ ] **SHARED-04**: Resolution chain priority: remote override → shared `.idea/` → app-level personal → language override → global. Source indicator shows which layer won.
- [ ] **SHARED-05**: On first open of a project that already has an app-level personal pin, the plugin shows a one-shot notification offering to migrate to `.idea/` (shared) with `Migrate` / `Keep personal` / `Dismiss` actions.
- [ ] **SHARED-06**: Settings include a help link that opens a docs page explaining the `.gitignore` whitelist requirement (`!/.idea/ayuAccent.xml`) and the team-sharing model.

## Remote Environment Awareness

- [ ] **REMOTE-01**: User enables "Use different accent for remote sessions" and picks a Remote Accent color (default `#FF6B9D`).
- [ ] **REMOTE-02**: Plugin detects Gateway (`AppMode.isRemoteDevHost`), WSL (`WslPath.parseWindowsUncPath`), Dev Container, and Code With Me guest sessions; when any is active, the Remote Accent wins over the project / language / global chain.
- [ ] **REMOTE-03**: A local session on the same machine retains the configured project / global accent unchanged — remote detection is non-destructive.
- [ ] **REMOTE-04**: When plugin code runs in a Gateway backend process (UI renders on a remote client), the apply pipeline early-returns without mutating UIManager, avoiding crashes and wasted work.
- [ ] **REMOTE-05**: Settings show the currently-detected environment kind as a label ("Detected: Gateway" / "WSL" / "DevContainer" / "Local") — user can see at a glance why the remote accent did or didn't win.

## Brightness Adjust Actions

- [ ] **ADJUST-01**: User invokes "Ayu: Darken Accent" (default shortcut `Alt+Cmd+−`); the current accent's HSL lightness drops by 5 % and applies live. Foreground contrast recomputed if CHROME-07 is on.
- [ ] **ADJUST-02**: User invokes "Ayu: Lighten Accent" (default shortcut `Alt+Cmd+=`); lightness rises by 5 % and applies live with the same contrast recompute.
- [ ] **ADJUST-03**: Adjustments write the new hex back to the same resolver layer that supplied the original accent (remote / shared / personal / language / global) — a darken never accidentally creates a new layer.
- [ ] **ADJUST-04**: Lightness is clamped to `[10 %, 95 %]` so repeated darken / lighten never collapses to pure black or white; at the clamp edge the action is a no-op with a brief balloon hint.

## Favorites

- [ ] **FAV-01**: User saves the current accent as a named favorite via "Ayu: Save Current As Favorite"; favorite appears in the Accent panel under "Your favorites".
- [ ] **FAV-02**: User renames or removes any saved favorite via right-click context menu on its swatch.
- [ ] **FAV-03**: User clicks "Add community favorites" link; 6 preloaded named colors (IntelliJ Red `#FC801D`, Kotlin Purple `#7F52FF`, Rust Orange `#CE412B`, Go Cyan `#00ADD8`, Python Yellow `#FFD43B`, TypeScript Blue `#3178C6`) are appended without overwriting existing user favorites.
- [ ] **FAV-04**: Favorites grid displays up to 8 user favorites in a 4×2 layout; when there are more, the grid scrolls horizontally inside the panel.

## Command / Action Menu

- [ ] **CMD-01**: "Ayu: Enter Color…" opens an input dialog that accepts hex (`#RRGGBB`), `rgb()`, `hsl()`, or HTML color names; invalid input shows an inline validation error without closing the dialog.
- [ ] **CMD-02**: "Ayu: Apply Favorite…" opens a JBPopupFactory list popup showing built-in presets + user favorites + community favorites in groups, with click-to-apply.
- [ ] **CMD-03**: "Ayu: Random Accent" picks an HSL-randomized color clamped to `H ∈ [0, 360]`, `S ∈ [0.6, 0.9]`, `L ∈ [0.5, 0.7]` so random never produces unreadable or muddy colors.
- [ ] **CMD-04**: "Ayu: Show & Copy Current Color" displays the current hex in a HintManager balloon at the status bar widget and copies it to the clipboard.
- [ ] **CMD-05**: "Ayu: Reset Project Accent" clears the winning project-layer override (shared or personal, whichever applied) and reapplies the resolver chain; a confirmation is only shown if the cleared layer was shared (team-affecting).
- [ ] **CMD-06**: "Ayu: Pin Accent to Project" is available from the main menu and as a context-menu entry on the Project View toolbar; submenu offers Shared / Personal / Unpin.

## Startup Surprise

- [ ] **SURPRISE-01**: User enables "Surprise me on startup" and picks one of three modes: `Random each open`, `Random from favorites`, or `Daily deterministic`.
- [ ] **SURPRISE-02**: On each project open, the selected mode picks a color that wins over the project / language / global layers for that session only — no persistence back to any resolver layer.
- [ ] **SURPRISE-03**: Daily-deterministic mode uses a `LocalDate.now()`-derived seed so every project opened the same day gets the same color; the color flips automatically at local midnight.
- [ ] **SURPRISE-04**: Startup surprise and the existing Accent Rotation are mutually exclusive; enabling one disables the other with a clear hint in Settings, and the disabled toggle shows *why* it's disabled.

## Ayu Quick-Switcher Widget (Phase 48 — replanned 2026-05-17)

> Replaces the original Phase 47 (status-bar widget) + Phase 48 (stripe + chip) plans. Both were folded into a single `MainToolbarRight` widget after live runIde testing exposed that the stripe was disabled on macOS 2026.1+ native-title-bar IDEs and the chip's standalone value did not justify a premium gate. The new widget consolidates accent + variant + related toggles + quick actions behind one click.

### Shared infrastructure (already shipped by Plan 48-01)

- [x] **WIDGET-INFRA-01**: Plugin ships an application-scoped `AccentChangedTopic: Topic<AccentChangeListener>` (payload: project, hex, source). `AccentApplicator.apply()` publishes after `state.lastApplyOk = true`; `ProjectAccentSwapService.handleWindowActivated()` publishes from the same-hex branch. Subscribers connect through a per-instance `Disposable` parent (NEVER application — Pattern E).
- [x] **WIDGET-INFRA-02**: `AccentResolver.sourceLabel(source: Source): String` produces user-visible source labels (`Global` / `Project override` / `Language override`) so widget tooltips and popup chrome stay consistent across surfaces.

### Widget chip (always-visible in `MainToolbarRight`)

- [ ] **WIDGET-01**: Widget renders a small accent-coloured chip inside `MainToolbarRight` via `AnAction` + `CustomComponentAction` (`MainToolbarWidgetFactory` does NOT exist in `platformVersion 2025.1` — `CustomComponentAction` is the stable public path). Default ON on first launch after the v2.6.x update.
- [ ] **WIDGET-02**: Chip dimensions and colour fill are tuned during the runIde checkpoint until the chip reads as a deliberate UI element next to neighbouring widgets (not a stray pixel). Exact values land via the planner's `D-*` decision lock, not pre-committed here.
- [ ] **WIDGET-03**: Chip tooltip text is `"{hex} — {sourceLabel}"` where `sourceLabel` resolves through `AccentResolver.sourceLabel(source)`. Updates within one EDT cycle of any `AccentChangedTopic` publish.
- [ ] **WIDGET-04**: Chip colour reflects the focused project's resolved accent. Switching focus between two projects with different accents updates the chip within one EDT cycle (verified via a `WindowFocusListener` test + mock harness, OR an equivalent "focused project changed" event the platform exposes — planner decides between `ProjectActivity`-driven re-resolve and a focus listener).

### Popup (left-click)

- [ ] **WIDGET-05**: Left-click opens a popup with four sections in this order: variant switcher, accent picker, related toggles, quick actions. Each section is collapsible-by-default OR always-expanded — the planner picks based on visual density during runIde.
- [ ] **WIDGET-06**: Variant section lists the currently-bundled Ayu Islands variants (Dark, Mirage, Light, Light Bordered — exact set sourced from `AyuVariant` enum at plan time). Selecting a variant applies it via the existing variant-swap pipeline; no separate "Apply" button.
- [ ] **WIDGET-07**: Accent section renders the 12 preset swatches as a grid plus a "Custom…" entry and a "More…" link that opens the Settings → Ayu Islands → Accent page. Clicking a preset persists through the existing AccentApplicator pipeline (per-project pin honoured if set).
- [ ] **WIDGET-08**: Related-toggles section exposes the same fields as their Settings panel counterparts — chrome tinting on/off, glow on/off, accent rotation on/off, follow-system accent on/off. State is single-sourced through `AyuIslandsSettings.state` so flipping a toggle in the popup matches flipping it in Settings, and vice versa.
- [ ] **WIDGET-09**: Quick-actions section exposes Pin / Random / Lighter / Darker / Copy Hex as one-click buttons. Each delegates to the same command action as the popup-less keyboard path (preserves single-source-of-truth for these operations).

### Right-click + lifecycle

- [ ] **WIDGET-10**: Right-click on the chip opens a streamlined context menu containing only the quick-actions section's entries (no popup chrome, no other sections). Same `actions` as WIDGET-09 — popup and context-menu must NOT diverge.
- [ ] **WIDGET-11**: Widget hides when the active LAF is not an Ayu variant (`AyuVariant.detect() == null`) — the chip would lie about a colour the user is not seeing. Re-appears automatically when the user switches back to Ayu. No user-visible toggle for this; it's tied to LAF state.
- [ ] **WIDGET-12**: User can hide or show the widget via a single Settings checkbox (default ON). Setting persists across IDE restart through the existing `AyuIslandsState` persistence pipeline.
- [ ] **WIDGET-13**: Widget disposes cleanly on plugin unload / project close — MessageBus listener counts verified via mockk subscribe/disconnect over 10 iterations (Pattern E). No leaked Disposables, no SEVERE in `idea.log` over a 10-cycle install-disable-uninstall test.

## Syntax Moods & Style Axes (Phase 49)

### Tab + intensity radio

- [ ] **SYNTAX-01**: A new tab labelled "Syntax" appears in `AyuIslandsConfigurable`'s `JBTabbedPane`. Exact tab position is the planner's decision (alphabetic/semantic fit between existing tabs). Implements `AyuIslandsSettingsPanel` interface.
- [ ] **SYNTAX-02**: The tab exposes a four-option radio "Syntax intensity": Minimal / Standard / Rich / Maximum. Each option shows the approximate key count next to its label (Minimal ≈ 1500, Standard ≈ 1900, Rich ≈ 2700, Maximum ≈ 3050) so the user sees what they're picking.
- [ ] **SYNTAX-03**: Selecting a mood applies the change within one EDT cycle to all three Ayu scheme variants (Mirage, Dark, Light), via `EditorColorsScheme.setAttributes(key, attrs|null)` mutation (proven pattern from `AccentApplicator.applyAlwaysOnEditorKeys`). A single `EditorColorsManager.fireGlobalSchemeChange()` is fired after batch apply.

### Style modifier checkboxes

- [ ] **SYNTAX-04**: The tab exposes four checkboxes labelled "Style modifiers": Italic declarations, Bold type references, Dimmed comments, Italic doc tags. Each modifier is a cross-cutting axis that applies a transform across all 26 supported languages simultaneously.
- [ ] **SYNTAX-05**: Axes stack additively on top of the selected mood. Toggling an axis off reverts ONLY that axis's transform — other active axes and the mood baseline remain applied. Implementation re-reads the mood baseline + re-applies the remaining axes (no in-place undo of just the toggled-off transform).

### Defaults + upgrade UX

- [ ] **SYNTAX-06**: Default mood on first launch is `MAXIMUM` so that users upgrading from a version that shipped the full expansion delta see zero visual regression. Default axes set is empty.
- [ ] **SYNTAX-07**: On the first IDE launch after the user installs the version that ships Phase 49, a single one-time IDE notification fires: "Ayu Islands — new Syntax Moods control: Minimal / Standard / Rich / Maximum + style modifiers. Settings → Ayu Islands → Syntax." `PropertiesComponent` tracks the "shown" flag; the notification never fires again on the same install.

### Gating + persistence

- [ ] **SYNTAX-08**: The feature is free for all users (no license gate). `LicenseChecker.isLicensedOrGrace()` is NOT consulted in this tab. Both free and Pro users see the same controls and the same behavior.
- [ ] **SYNTAX-09**: Mood + axes state persists across IDE restart via `@Service @State` `SimplePersistentStateComponent<BaseState>` (mirrors `AccentMappingsSettings` template). Mood stored as enum name string; axes stored as `Set<String>` of enum names.
- [ ] **SYNTAX-10**: Mood + axes re-apply automatically when the LAF switches back to Ayu from a non-Ayu theme. Extends the existing `AyuIslandsLafListener` (does NOT register a duplicate listener). Uses `AyuVariant.isAyuActive()` as the Pattern J gate.

### Coexistence with built-in Color Scheme editor

- [ ] **SYNTAX-11**: The built-in JetBrains Color Scheme editor (`Settings → Editor → Color Scheme → [Language]`) continues to operate as the final-grain control. User per-key overrides written to `_@user_*.icls` take precedence over our overlay (this is JetBrains' standard scheme inheritance; no special code needed — verify in live IDE that overrides survive mood/axis toggles).
- [ ] **SYNTAX-12**: The Syntax tab contains a `browserLink`-style pointer below the intensity radio, labelled "Settings → Editor → Color Scheme" with a one-line caption explaining that per-key tuning is still available there. Educational only — no actual navigation guarantee, since `jetbrains://idea/settings?name=...` URL behavior is platform-dependent.

## Syntax Intensity (Phase 50 — replaces Phase 49 Syntax Moods after redesign)

> **Architecture pivot (2026-05-24):** Earlier draft of INTENSITY-01..19 specified a single master intensity slider with RGB-blend toward editor background. After user testing on 2026-05-24, that approach was rolled back as a misunderstanding — it scaled the whole foreground uniformly instead of giving granular control over functional primitives, and incidentally destroyed the working Phase 49 DIMMED_COMMENTS effect. The replacement architecture below uses pill-row presets (mirroring the Glow / VCS / Font franchise) with a Custom drill-down for per-language × per-primitive-category fine tuning, hybrid (saturation + lightness) per-category curves, and full 26-language coverage. The Phase 49 DIMMED_COMMENTS effect is preserved as part of the Comments-category curve in the Whisper / Ambient presets. Legacy planning artifacts archived at `.planning/phases/50-syntax-intensity-v1-rolled-back/`.

### Phase A — Preset-driven syntax customization (free)

- [ ] **INTENSITY-01**: A "Syntax" tab in `AyuIslandsConfigurable`'s `JBTabbedPane` exposes a pill row with 5 options: Whisper / Ambient / Neon / Cyberpunk / Custom. The pill row mirrors the Glow / VCS / Font tabs' established preset franchise pattern. The active preset is visually selected. Clicking a preset applies + persists immediately. Replaces the mood radio + axis checkboxes shipped in Phase 49.
- [ ] **INTENSITY-02**: Each named preset (Whisper / Ambient / Neon / Cyberpunk) defines a per-language × per-primitive-category `(saturationDelta, lightnessDelta)` table. Per-language scope = all ~26 supported languages classified by `SyntaxLanguageRegistry`. Ambient = identity transform (zero deltas) and is the default. Whisper subdues all categories with an extra lightness drop on Comments (Phase 49 DIMMED_COMMENTS preserved). Neon boosts saturation across declarations and keywords. Cyberpunk maxes saturation across all categories.
- [ ] **INTENSITY-03**: Color math = hybrid HSL transform per category. For every (language × category) pair, the preset declares `(saturationDelta, lightnessDelta)` in `[-1.0, +1.0]` units. Apply path: parse baseline foreground → convert to HSL via `HslColor.fromColor` → add deltas with `coerceIn(0f, 1f)` clamp on saturation and `coerceIn(0.10f, 0.95f)` on lightness (matches `AccentHsl` clamp semantics) → convert back to RGB → write cloned TextAttributes. Hue preserved. Pure-compute, platform-independent function.
- [ ] **INTENSITY-04**: Apply path writes via Pattern B per-scheme isolation (clone-then-apply, never mutate baseline). H5 dual-write to `EditorColorsManager.getInstance().globalScheme` AND named registered schemes (`AYU_SCHEMES` tuple). Single `MessageBus.syncPublisher(EditorColorsManager.TOPIC).globalSchemeChange(null)` wrapped in `ReadAction.run` (R-7) per apply call. Missing schemes logged once per (scheme, session) (Pattern A log-once).
- [ ] **INTENSITY-05**: 16 primitive categories: function declaration, class declaration, interface declaration, keyword, parameter, local variable, string literal, number literal, comment, annotation, operator, type reference, static field, instance field, generics, documentation (doc tags + escapes). Mapping `TextAttributesKey` → category via a hardcoded prefix-match table that extends `SyntaxLanguageRegistry`. Categories are hidden under Custom mode — default UI shows only the 5 pills.
- [ ] **INTENSITY-06**: Default state: `selectedPreset = AMBIENT, customOverrides = emptyMap()`. First launch after install applies the Ambient identity transform — no visual regression from current Ayu palette.
- [ ] **INTENSITY-07**: State persists across IDE restart via `@Service @State SimplePersistentStateComponent<BaseState>`. Schema: `selectedPreset: SyntaxPreset` (enum name), `customOverrides: Map<String, Map<String, Int>>` (language → category → slider 0-100). XML filename `ayu-islands-syntax-intensity.xml` distinct from Phase 49's `ayu-islands-syntax-mode.xml` so the Phase 49 read-and-discard migration is unambiguous.
- [ ] **INTENSITY-08**: Active preset re-applies automatically when the LAF switches back to Ayu from a non-Ayu theme. Extends the existing `AyuIslandsLafListener` (does NOT register a duplicate). Pattern J gate on `AyuVariant.isAyuActive()`.
- [ ] **INTENSITY-09**: Editor background per variant sourced from `EditorColorsScheme.defaultBackground` at apply time — NOT hardcoded. Mirage / Dark / Light each pull their own bg as the fallback target for the low-lightness clamp edge. `RgbBlend.fallbackEditorBgFor(variantId)` provides R-1 mitigation when scheme bg read fails.
- [ ] **INTENSITY-10**: Free tier = 4 named presets (Whisper / Ambient / Neon / Cyberpunk) fully accessible. The Custom pill is disabled with an "Upgrade to Pro" tooltip + clickable link for free users. The pill row remains visible to free users so the gated feature is discoverable.
- [ ] **INTENSITY-11**: Phase 49 → Phase 50 migration. On first launch after upgrade, `SyntaxIntensityMigrationNotifier` reads-and-discards the old `SyntaxModeState` (ignores `mood` + `axes` fields per D-12 read-and-discard semantics). Notification body: "Ayu Islands — Syntax customization updated. New presets available in Settings → Ayu Islands → Syntax." One-shot latch via `PropertiesComponent` flag `ayu.syntax.intensity.notified` (distinct from Phase 49's `ayu.syntax.notified` flag so users who saw the Phase 49 notification still see this migration message).
- [ ] **INTENSITY-12**: Phase 49 source artifacts deleted atomically: `SyntaxMood.kt`, `StyleAxis.kt`, `SyntaxModeApplicator.kt`, `SyntaxModeService.kt`, `SyntaxModeState.kt`, `SyntaxModeUpgradeNotifier.kt`, plus all Phase 49 tests (`SyntaxMoodTest`, `StyleAxisTest`, `SyntaxModeApplicator*Test`, `SyntaxModeService*Test`, `SyntaxModeState*Test`, `SyntaxModeUpgradeNotifierTest`, `AxisKeyAssignmentSnapshotTest`, `MoodTierAssignmentSnapshotTest`, `SyntaxModePanelGatingTest`). Resource files `themes/extended/mood-tiers.txt` and `themes/extended/axis-keys.txt` deleted. Source deletion is paired with plugin.xml service unregistration in the same commit per R-3.

### Phase B — Custom drill-down (premium)

- [ ] **INTENSITY-13**: Clicking the Custom pill (only enabled for Pro users) reveals a fold-out panel below the pill row. The fold-out contains a per-language accordion. Each language section expands to show 16 per-category sliders (range 0-100, default 50 = preset baseline). Slider drag is debounced at 100 ms before apply (OQ-08).
- [ ] **INTENSITY-14**: Each per-category slider in Custom mode overrides the active preset's `(saturationDelta, lightnessDelta)` value for that (language × category) pair. Override semantics: slider 0..100 maps linearly to a `(saturationDelta, lightnessDelta)` pair via the same category-specific curve the preset uses; the active preset defines the slider default position for that category. The Comments curve includes an additional lightness-drop component (Phase 49 DIMMED_COMMENTS preserved at all slider positions <50).
- [ ] **INTENSITY-15**: Reset semantics: per-slider context menu offers "Reset to preset default" (clears that one override). A per-language "Reset language" link clears all overrides for that language. The master "Reset all customizations" button at the top of the Custom fold-out wipes the entire `customOverrides` map — the pill row falls back to the active preset cleanly.
- [ ] **INTENSITY-16**: Custom mode = premium-gated. Free users see the Custom pill with a disabled state, an "Upgrade to Pro" tooltip, and a clickable link to the existing license-purchase flow. Switching to Custom is impossible without a valid `LicenseChecker.isLicensedOrGrace()` result. The Phase A pill row (4 named presets) remains free regardless.
- [ ] **INTENSITY-17**: `customOverrides` storage is forward-compatible. Free Phase A never writes to this field. Premium Phase B activates writes when sliders move in the Custom fold-out. Schema migration is not required between Phase A and Phase B — the field already exists, empty by default (INTENSITY-07).

### Common preset abstraction (extracted in Phase 50)

- [ ] **INTENSITY-18**: Extract a `ColorPreset<TConfig>` interface (or sealed base class) from the existing preset franchise pattern shared by `GlowPreset`, `VcsColorPreset`, and `FontPreset`. The interface declares `displayName: String`, `entries: List<Self>`, `companion fromName(name: String): Self`, `companion detect(config: TConfig): Self`. Refactor `GlowPreset`, `VcsColorPreset`, `FontPreset` to implement the interface. Implement `SyntaxPreset` through the same interface. The common abstraction enables future preset-aware UI components to render the pill row uniformly across Glow / VCS / Font / Syntax tabs.

### Phase 49 baseline retention

- [ ] **INTENSITY-19**: Retain `themes/extended/AyuIslands{Mirage,Dark,Light}.extended.xml` overlay files — they are the curated semantic-key universe used as baseline for preset apply. `SyntaxOverlayLoader` (Phase 49 code, retained) still loads them. The Comments category's preset curves use the Phase 49 DIMMED_COMMENTS values from these XMLs as the Whisper-preset endpoint, preserving the working dim-comment effect.
- [ ] **INTENSITY-19**: Keep `themes/extended/AyuIslands{Mirage,Dark,Light}.extended.xml` overlay files — they ARE the curated semantic-key universe. Phase 50 still loads them via `SyntaxOverlayLoader` (renamed/extended to include `LanguageRegistry`).

## Future Requirements (deferred)

- **PEACOCK-SHARE-IMPORT** — Import a `.ayupreset` bundle from a teammate via drag-drop into Settings (folds into Phase 999.7 Theme Export when promoted).
- **LIVESHARE-LIKE-TINT** — Code With Me host-vs-guest automatic tint akin to Peacock's vsls integration; depends on `ClientSessionsManager` API stability.
- **CHROME-GUTTER-ACCENT** — Extend chrome tinting into the editor gutter, not just chrome — strictly opt-in and off by default given editor real estate.

## Out of Scope

- Native macOS title bar tinting when "Merge main menu with window title" is disabled — the OS-owned chrome is not reachable from plugin code. Same limitation as Peacock on macOS without custom title bar. Documented in Settings as a tooltip.
- Linux GNOME SSD window decorations — same native-chrome constraint.
- Overriding inspection highlight colors ("Squiggly-be-gone" in Peacock) — our accent already touches `WARNING_ATTRIBUTES` and `TODO_DEFAULT_ATTRIBUTES`; zeroing out error squiggles would fight the user's color scheme far more intrusively than in VSCode. Not worth the regression risk.
- Separate Peacock-style "workspace peek" — JetBrains' Recent Projects already surfaces accent-per-project via the resolver chain; a second entry point would duplicate without adding value.

## Traceability

| REQ-ID | Phase | Status |
|--------|-------|--------|
| CHROME-01..08 | Phase 40 | Planned |
| SHARED-01..06 | Phase 41 | Planned |
| REMOTE-01..05 | Phase 42 | Planned |
| ADJUST-01..04 | Phase 43 | Planned |
| FAV-01..04 | Phase 44 | Planned |
| CMD-01..06 | Phase 45 | Planned |
| SURPRISE-01..04 | Phase 46 | Planned |
| WIDGET-INFRA-01..02 | Phase 48 | Shipped (Plan 48-01) |
| WIDGET-01..13 | Phase 48 | Planned (replan) |
| SYNTAX-01..12 | Phase 49 | Withdrawn (replaced by INTENSITY-* in Phase 50) |
| INTENSITY-01..12 | Phase 50A | Planned (free) |
| INTENSITY-13..17 | Phase 50B | Planned (premium, follow-up) |
| INTENSITY-18..19 | Phase 50 (sunset) | Planned (executed alongside 50A) |
