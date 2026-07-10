# Changelog

## [2.8.0] - 2026-07-09

- [Paid] **Chrome tint on external themes** — chrome surfaces and accent
  elements can now follow the Ayu accent on non-Ayu themes when explicitly
  enabled; external chrome tint stays off by default.
- [Paid] **Glow placement** — choose where glow renders: the full island, a
  strip under the editor tabs, or side edges only (editor and tool windows).
- [Paid] **Accent from project icon** — projects without an override can take
  their accent from `.idea/icon.png` automatically when they open, and the
  Add Override dialog offers the icon's dominant color as a one-click pick.
- [Fix] **Commit panel accessibility crash** — screen-reader and macOS
  accessibility walks over the commit changes tree no longer flood idea.log
  with "Attempt to take read lock was prevented" errors; the path renderer
  now serves plain text when the platform forbids model access mid-walk.
- [Free] **Find what's new faster** — the settings page now points at newly
  added controls: a "New in this release" index at the top with a Review
  jump link, an accent dot on tabs that contain new settings, and small
  "New" marks on the rows themselves. Everything clears as you visit the
  tabs, never outlives 45 days, and fresh installs see no badges at all.

## [2.7.8] - 2026-07-06

- [Fix] **What's New and onboarding tabs** — a failed tab open no longer
  blocks the What's New tab or the license wizard for the rest of the IDE
  session; the next trigger simply retries.
- [Fix] **Accent apply recovery** — when applying an accent fails midway, the
  color now recovers on the next window focus instead of freezing until
  restart, and startup only trusts cached accents from a cleanly finished
  apply.
- [Fix] **Accent rotation reliability** — rotation now counts failed applies
  and stops with the "accent rotation stopped" notification instead of
  silently ticking forever against a broken apply.
- [Fix] **License downgrade revert** — the "restart to complete the reset"
  notice now appears when the free-tier revert could not finish cleanly.
- [Fix] **Settings overrides preview** — opening the Accent → Overrides
  resolution preview is now strictly read-only and never schedules background
  language scans.
- [Fix] **Resolution diagnostics consistency** — the "Currently active" label,
  the diagnostics row, and the accent actually applied now always name the
  same resolution source.
- [Fix] **Project-language accent scans** — background detection now cancels
  when a project closes, checks cancellation during large repository scans, and
  drops late cache or publish results so reopened projects do not inherit stale
  language accents.
- [Fix] **Large project language accents** — language detection now keeps exact
  scans for projects under the file cap and uses bounded sampling for larger
  repositories, so accents resolve consistently without walking every file in a
  monorepo.
- [Fix] **Font installer fallbacks** — fallback font download URLs are now
  smoke-checked in CI before release, catching broken upstream links before a
  bundled font install reaches users.

## [2.7.7] - 2026-07-01

- [Fix] **Language override diagnostics** — Settings and Quick-Switcher now show
  the detected project-language breakdown, manual forced-language state,
  polyglot fallback detail, and the language that actually won accent
  resolution.
- [Fix] **Mapped accents in mixed-language projects** — polyglot scans can now
  choose the highest-weight detected language that has a user accent mapping,
  so balanced projects can still use a configured language color instead of
  falling back to the global accent.
- [Fix] **Swift and Noctule Swift highlighting** — Swift declaration and
  reference aliases, predefined symbols, and `nil` fallback now use Ayu-native
  semantic colors across Dark, Mirage, and Light.
- [Fix] **Project fallback accents** — project fallback resolution now warms and
  reuses the detector verdict consistently, keeping diagnostics aligned with
  the accent that gets applied.
- [Fix] **Font install consent** — Settings and onboarding font installs now
  require the exact catalog entry that the user approved before any font files
  are queued for installation.

## [2.7.6] - 2026-06-24

- [Fix] **Language override diagnostics** — Quick-Switcher now keeps
  dominant-language proportions and fallback detail in the expanded resolution
  chain instead of crowding or overlapping the collapsed widget.
- [Fix] **Groovy and Jenkinsfile semantic highlighting** — Jenkinsfile
  pipeline calls, named arguments, dynamic Jenkins bindings (`env`, `params`,
  `currentBuild`), and unresolved chained calls now resolve to Ayu-native
  Groovy semantic colors across Dark, Mirage, and Light.
- [Fix] **Jenkinsfile unresolved references** — pipeline DSL symbols such as
  `properties`, `parameters`, `podTemplate`, and `containerTemplate` no longer
  render as near-background text or carry noisy dotted underlines.
- [Fix] **Focus-based accent refresh** — switching project windows now reapplies
  project and language accents through IntelliJ's write-safe dispatch path
  instead of a raw Swing callback.
- [Fix] **Chrome tinting dividers** — shared IntelliJ divider peers no longer
  pick up project accent tint and draw a colored editor frame outside the
  intended accent surfaces.

## [2.7.5] - 2026-06-18

- [Paid] **Accent Source status-bar widget** — Pro users can add a status-bar
  widget that shows which accent source won for the current project and opens
  the full resolution chain.
- [Paid] **Language override controls** — language accent detection now shows
  dominance and fallback details in Settings, and forced language or fallback
  choices take precedence over the global accent.
- [Free] **Quick-Switcher accent diagnostics** — the toolbar widget now shows
  the resolved accent source inline, with an expandable chain for project pins,
  language overrides, fallbacks, external themes, and global accents.
- [Free] **Groovy and Jenkinsfile syntax colors** — Groovy and Jenkinsfile roles
  now have explicit Ayu colors across Dark, Mirage, and Light, including
  methods, fields, map keys, strings, GStrings, keywords, documentation, and
  inlay type hints.
- [Fix] **Quick-Switcher widget buttons** — related toggle buttons now apply
  Chrome tinting, Glow, Accent rotation, and Follow system accent changes
  reliably from the widget.
- [Fix] **Chrome tinting target preservation** — widget toggles and Settings
  Apply now preserve the exact chrome tint targets you selected instead of
  turning every tint surface on or off together.
- [Fix] **Language override precedence** — forced-language and fallback choices
  now take precedence over project/global fallback when resolving the active
  accent.
- [Fix] **Accent diagnostics accuracy** — Quick-Switcher and status-bar
  diagnostics now match the actual resolver for language fallback, project
  fallback, and external automatic accents.
- [Fix] **Editor Glow with pinned tabs** — Glow overlays now align with editor
  content when only pinned tabs remain, instead of using a fixed tab-strip
  fallback.

## [2.7.4] - 2026-06-15

### Paid
- [Paid] **Language override diagnostics** — Settings now explains whether a project's language accent is detected, polyglot, forced to a specific language, or using a project fallback.

### Free
- [Free] **Swift semantic highlighting** — Noctule Swift files now render parameters, labels, properties, functions, and types with distinct Ayu colors across 109 semantic tokens.
- [Free] **Swift syntax colors** — Swift keywords, strings, numbers, and comments use Ayu palette colors in base themes.

## [2.7.3] - 2026-06-11

### Paid
- [Paid] **Commit Panel path display** — Pro users can move full Commit Panel paths to tooltips or shorten leading directories inline.

### Free
- [Free] **Syntax preview** — native highlighting now updates reliably when switching syntax presets and readability options.

## [2.7.2] - 2026-06-05

### Paid
- [Paid] **External theme accent inheritance** — Pro project and language accent pins can now drive selected Ayu integrations while another IDE theme is active.
- [Paid] **Glow on external themes** — Glow can follow Ayu accents on non-Ayu themes when explicitly enabled; external Glow stays off by default.
- [Paid] **Plugin sync on external themes** — CodeGlance Pro and Indent Rainbow can now sync to Ayu's external accent context on non-Ayu themes.
- [Paid] **External Quick-Switcher actions** — premium actions like Random, Lighter, and Darker now persist the chosen external accent on non-Ayu themes.

### Free
- [Free] **External theme support** — use selected Ayu accent enhancements on non-Ayu JetBrains themes without switching the active IDE theme to Ayu.
- [Free] **Quick-Switcher on external themes** — Quick-Switcher now supports external themes, including accent preset apply and copy flows when external theme support is enabled.
- [Free] **Automatic external accent sources** — external accents can resolve from Material Theme, IDE accent colors, or a manual fallback.

## [2.7.1] - 2026-06-01

### Added
- **.ignore plugin colors** — when the optional `.ignore` plugin is installed, its files use dedicated Ayu colors across Dark, Mirage, and Light editor schemes, including Syntax preset transformations and a Plugins-tab opt-out.

### Changed
- **Visible locked premium previews** — free users can now inspect premium Settings controls in their real layout, with the controls greyed out until a license or trial unlocks them.
- **Unified VCS preview** — the VCS tab now shows one compact code-style preview that reflects diff, merge/conflict, blame, and file-status presets together.
- **Font preset preview polish** — the Font tab now uses the same editor-style preview treatment as VCS, with clearer depth and file context.
- **Release verifier gate** — release builds now fail before Marketplace upload if JetBrains Plugin Verifier reports internal IntelliJ Platform API usage.

### Fixed
- Premium Settings controls stay visible-but-locked across VCS, Glow, Workspace, Chrome tinting, Accent Elements, plugin integrations, and accent overrides instead of disappearing behind a Pro notice.
- Locked premium controls no longer mark Settings as modified when clicked by unlicensed users.
- Reset/Revert restores nested plugin-integration rows correctly, including Indent Rainbow custom controls.
- Settings tabs and preview panels shrink more consistently in narrow Settings windows.
- Accent Settings preview stays synchronized with the selected color instead of restoring stale shuffle state during panel creation.
- Visible managed tool windows re-measure after layout and theme refreshes, so auto-fit sizing stays consistent.

## [2.7.0] - 2026-05-29

### Free
- **Expanded semantic highlighting** — hundreds more editor keys now carry Ayu-aware color across 26+ language families, including YAML/HCL, Scala docs, Kotlin gaps, Dart accessors, Groovy, and Go exported identifiers.
- **Syntax presets** — *Settings -> Ayu Islands -> Syntax* adds four one-click syntax moods: **Whisper**, **Ambient**, **Neon**, and **Cyberpunk**.

### Paid
- **Custom syntax tuning** — Pro users can tune each language separately with sliders for declarations, identifiers, keywords/docs, literals, and operators while untouched cells inherit the selected base preset.
- **Readability controls** — **Dim comments**, **Soften documentation**, **Quiet operators**, and **Emphasize declarations** layer on top of the active preset so dense code gets quieter without manual per-key Color Scheme editing.

### Fixed
- Matching tag highlights are calmer on dark backgrounds and no longer fall back to unreadable light-background styling.
- Status bar text stays readable on tinted chrome, and low chrome-tint intensity remains subtle instead of snapping straight to saturated accent color.
- YAML/HCL semantic keys and HCL block names keep Ayu styling after JetBrains key migration.
- The Syntax Custom page opens reliably on newer IDEs and keeps its compact, aligned slider layout.
- Syntax presets keep their expected intensity ordering on vivid palettes; custom cells also inherit the selected preset without losing scheme inheritance or stale colors after theme changes.

## [2.6.4] - 2026-05-18

### Added
- [Free] **Quick-Switcher Widget** — a new chip in the main toolbar reflects the focused project's accent. Left-click opens a popup with variant + Islands UI toggle, the full accent grid, related toggles (Chrome tinting, Glow, Accent rotation, Follow system), and quick actions (Pin, Random, Lighter, Darker, Copy Hex).
- [Free] **Right-click context menu** on the chip exposes the same quick actions for a keyboard-friendly workflow.
- [Paid] Premium rows in the popup (related toggles + quick actions) appear when your license is active or in trial; the FREE block (variant + accent grid) is available to everyone.
- [Free] Hide the widget anytime from Settings → Ayu Islands → General if you prefer a cleaner toolbar.

## [2.6.3] - 2026-05-12

### Added
- [Paid] **New VCS tab in Settings** — *Settings → Ayu Islands → VCS*, between Glow and Workspace. Tune VCS color intensity across five surfaces: **diff viewer**, **Project View file status**, **editor gutter**, **conflict markers**, and **Git blame annotations**.
- [Paid] **Four named profiles** — pick **Whisper** (calmer), **Ambient** (2.6.2 defaults), **Neon** (brighter, restores the pre-2.6.2 punch), or **Cyberpunk** (peak vibrancy). Each section (Diff, Merge, Blame) carries its own profile, so you can mix — Neon diffs with Whisper blame, for example.
- [Paid] **Custom mode** — unlocks per-surface sliders inside each section for fine-grained control.

### Fixed
- Project tool window no longer changes width when toggled on/off in Mirage Islands UI under auto-fit mode (issue #169). Project View, Commit, and Git panel auto-fit managers now ignore show-toggle events that don't change content.

### Compatibility
- Master toggle stays off by default — 2.6.2 colors are **byte-identical** until you opt in. No surprise tints on upgrade.

## [2.6.2] - 2026-05-10

### Fixed
- VCS modified-line color is now distinct from added — modified shifts to a saturated blue (Dark `#73B8FF`, Mirage `#80BFFF`) so it stops blending into the green added band on dense diffs. Also bluer file-status colors in Project View and tabs.
- Editor selection background returns to translucent (alpha 25%) on Dark and Mirage so highlighted text doesn't read as a solid blue block.
- Settings page no longer freezes on "Loading…" forever when a Custom font preset is active (issue #164) — non-curated presets now skip the install pipeline cleanly instead of crashing the panel build.
- Font preview pane now renders the user's chosen Custom font family on Settings open instead of falling back to "Install JetBrains Mono to preview" — a UX bug previously masked by the panel-build freeze.

## [2.6.1] - 2026-04-27

### Fixed
- Calmer DIFF_MODIFIED / INSERTED / DELETED backgrounds across all three variants — diff viewer no longer washes the whole gutter when reviewing large patches.

## [2.6.0] - 2026-04-26

### Added
- [Paid] **Peacock parity for JetBrains** — pin an accent to a project window or to a programming language and every chrome surface (status bar, nav bar, tool stripes, panel borders) tints with that accent automatically. WCAG-aware foreground keeps text readable at any saturation. Inspired by Peacock for VS Code (johnpapa.net) — built on JetBrains platform primitives, not ported.
- Auto-bind the matching editor color scheme on theme change — switching Ayu Mirage / Dark / Light variants now keeps theme + editor scheme in sync. Default ON; opt-out toggle in Settings → Theme Synchronization preserves your custom scheme (Solarized, Material, etc.) untouched.

### Fixed
- Glow overlays no longer linger after switching the active theme to a non-Ayu variant — overlays dispose cleanly the moment the theme leaves the Ayu family.
- Sticky-line panel transparency in the editor — code under the scope-header line no longer bleeds through.
- Indent Rainbow and CodeGlance Pro integrations now revert cleanly when their sync toggles are turned off in Settings.
- Path widget breadcrumb on IntelliJ 2026.1 New UI Compact Navigation — chrome tinting reads with stable contrast across the full intensity range, and the slider visibly tints in a single Apply click instead of needing a second nudge.

### Roadmap
Next releases will close the remaining Peacock features — accent committed into `.idea/` so a team shares one color, visual distinction for Remote / WSL / DevContainer / Code With Me windows, keystroke darken / lighten on the active accent, named favorite colors with a community presets row, and every Peacock command surfaced through Find Action.

## [2.5.3] - 2026-04-20

### Added
- [Paid] Rescan project language on demand — new "Rescan" link in the Overrides proportions row and "Tools → Ayu Islands → Rescan Project Language" menu action, for when you want the detected breakdown to refresh without waiting for the next Gradle sync

### Fixed
- Bug fixes

## [2.5.2] - 2026-04-17

### Added
- [Paid] Per-Language Accent Pins show a live language-proportions breakdown for the current project (e.g. "Detected: Kotlin 78% · Java 15% · other 7%") with a polyglot fallback when no single language dominates

### Fixed
- Fix accent rotation overriding pinned project colors — tabs and glow stay on your pinned accent across rotation ticks
- Fix Settings panel "Currently active:" and "Detected:" readouts binding to the wrong project when two or more windows are open

## [2.5.1] - 2026-04-16

### Changed
- Marketplace "About" description now lists all v2.5.0 features — per-project and per-language accent pins, onboarding wizard, first-launch release showcase, and Settings-based font install — that were missing from the listing page when v2.5.0 was published

## [2.5.0] - 2026-04-16

### Added
- [Paid] Pin an accent color to a specific project — set it once from Settings and every time you switch to that window your accent is there
- [Paid] Pin an accent color to a programming language — e.g. Kotlin → lime, Python → sky. Applies when a project's dominant language matches and no project override exists
- Recent projects populate the add-project dialog so you can configure overrides without opening the project first
- First-launch release showcase opens automatically in the editor on upgrade, with captioned screenshots of the marquee features; reopen anytime via Tools → Ayu Islands → Show What's New

## [2.4.2] - 2026-04-12

### Added
- [Paid] Install, delete, or reinstall curated fonts directly from Settings without opening the wizard
- Every install and delete now shows a consent dialog with the exact platform font folder path
- Deleting a font reverts the editor to JetBrains Mono and warns that full removal takes effect after IDE restart
- Settings now shows "Installed", "file missing — Reinstall", or "Install automatically" based on actual disk state

### Fixed
- Fix Settings install link silently writing to ~/Library/Fonts with no consent dialog

## [2.4.1] - 2026-04-11

### Fixed
- Fix font install retry getting stuck on corrupted download cache

## [2.4.0] - 2026-04-10

### Added
- Onboarding wizard — full-tab welcome experience with preset cards, theme variant picker, and accent swatches
- [Paid] Font installation — download and apply curated coding fonts directly from the wizard
- Responsive scaling — wizard adapts to split editors and small windows
- [Paid] License transition listener — mid-session license changes apply immediately

### Fixed
- Fix trial day calculation timezone drift (UTC instead of system default)
- Renewing a license preserves your existing settings
- 48-hour offline grace window for license checks

## [2.3.7] - 2026-04-01

### Fixed
- [Free] Fix tool window width oscillation when switching between panels (Commit, Notifications, AI Chat)

### Added
- [Paid] Editor section in Workspace settings with scrollbar visibility controls (hide vertical/horizontal scrollbars)

## [2.3.6] - 2026-03-28

### Fixed
- Bug fixes

## [2.3.5] - 2026-03-26

### Fixed
- Fix selection overlap bands when accent color is active on multi-row selections

## [2.3.4] - 2026-03-26

### Fixed
- Fix glow color desyncing from accent after rotation

## [2.3.3] - 2026-03-25

### Fixed
- Fix "Write-unsafe context" crash during accent rotation with Indent Rainbow
- Harden rotation lifecycle: dispose guard, wider exception catch, null-safe Application access

### Changed
- Rewrite Marketplace description with benefit-led copy
- Trim embedded changelog to last 3 releases with GitHub link
- Fix Shuffle UI label: Paid → Free in changelog and update notifier

## [2.3.0] - 2026-03-19

### Added
- [Paid] Auto-fit with configurable min/max width for Project View, Commit, and Git panels
- [Paid] Git panel internal splitter control — auto-resizes the branches tree and file changes tree
- [Free] 30-day premium trial — every premium feature unlocked on the first install, no credit card
- [Free] Returning users get a fresh trial with premium defaults re-applied

### Changed
- [Paid] Project View cleanup — hide filesystem path and horizontal scrollbar

## [2.2.2] - 2026-03-14

### Fixed

- [Free] Fix diff/merge viewer using wrong default colors instead of theme palette
- [Free] Fix black toolbar in the merge conflict gutter on light themes
- [Free] Improve diff modified-line background visibility across all variants

## [2.2.1] - 2026-03-11

### Fixed

- [Free] VCS modified-line colors corrected to canonical ayu palette (Mirage, Dark)

## [2.2.0] - 2026-03-11

### Added

- [Free] Font presets: 4 curated Nerd Fonts (Whisper, Ambient, Neon, Cyberpunk) with one-click apply, live preview, and per-preset customization
- [Free] Custom accent color picker — choose any color beyond the 12 presets
- [Free] 12 accent presets (added Rose and Slate)
- [Free] Follow system accent color on macOS
- [Free] Redesigned accent panel with shade name display
- [Free] Follow system appearance (auto Light/Dark switching)

## [2.1.3] - 2026-03-09

### Fixed

- [Free] Improve Light theme surface colors: separator, hover, and folded text backgrounds for better contrast
- [Paid] Glow now renders on floating (detached) tool windows — overlay re-attaches on dock↔float transitions

### Added

- [Free] Six theme preview screenshots (Classic + Islands UI for Mirage, Dark, Light)

## [2.1.2] - 2026-03-08

### Added

- [Free] Reintroduce non-Islands (Classic UI) theme variants for Mirage, Dark, and Light
- [Free] Settings controls adapt to the active theme type (Islands UI vs. Classic)
- [Paid] Glow overlay now renders below the tab strip for visual harmony with underline
- [Paid] Tab underline thickness and glow-sync controls for Classic UI

### Changed

- [Free] Updated plugin description with clearer free/premium feature breakdown

### Fixed

- [Free] License enforcement in dynamic settings state updates

## [2.1.1] - 2026-03-07

### Added

- [Paid] "Highlight indent errors" toggle for Indent Rainbow — disable to blend continuation lines into gradient

### Fixed

- [Paid] Fix write-unsafe EDT context error on startup with Indent Rainbow integration

## [2.1.0] - 2026-03-07

### Added

- [Paid] Indent Rainbow integration: accent-colored indent guides with four intensity presets (Whisper, Ambient, Neon,
  Cyberpunk)
- [Paid] Bracket scope gutter highlight for matched braces
- [Free] Accent-colored matched bracket styling
- [Free] New Integrations settings panel for third-party plugin sync

## [2.0.1] - 2026-03-06

### Fixed

- Fix crash on theme switch: `setAttributes` received null in `revertAlwaysOnEditorKeys`
- Fix crash on theme switch: `repaintAllWindows` during `revertAll` triggered NPE in `HeaderToolbarButtonLook` because
  UI keys were cleared before the new theme loaded

## [2.0.0] - 2026-03-06

### Added

- Accent color customization with 10 hand-picked Ayu palette presets
- Per-variant accent storage (independent colors for Mirage, Dark, Light)
- Live visual preview in the accent picker
- Per-element accent toggles for tabs, scrollbars, links, caret row, and more (premium)
- Neon glow effects for island borders, active tabs, and focused inputs (premium)
- Four glow styles: Whisper, Ambient, Neon, Cyberpunk, and custom mode plus animations (premium)
- Third-party conflict detection (Atom Material Icons, CodeGlance Pro)
- Settings panel under Appearance > Ayu Islands with tabbed Accent/Effects layout
- macOS system accent color and appearance sync
- Freemium licensing — all six base themes remain free forever

## [1.2.1] - 2026-02-27

### Fixed

- Added the missing Marketplace changelog for v1.2.0 changes

## [1.2.0] - 2026-02-27

### Added

- Accent-colored active tab underline, caret, and focus borders for all variants
- Accent color defaults for editor selections, search highlights, and breadcrumbs

### Fixed

- README color swatches and language list corrections

## [1.1.0] - 2026-02-26

### Added

- C#/.NET syntax highlighting (Rider/ReSharper) for all three variants
- Dart, Ruby, PHP, Scala, Kotlin language-specific highlighting
- Rust, Bash/Shell, SQL, Regular Expressions language-specific highlighting
- 20+ languages with hand-tuned syntax tokens

### Changed

- Redesigned README with palettes table, badges, and professional structure

## [1.0.3] - 2026-02-26

### Added

- Ayu Dark Islands variant (deep dark `#0D1017` background, gold `#E6B450` accent)
- Ayu Light Islands variant (warm white `#FAFAFA` background, orange `#F29718` accent)
- Each variant in two sub-themes: classic UI base and Islands UI
- Six themes total: Mirage, Dark, Light x (classic, Islands)

### Fixed

- Git Log contrast on dark variants (Table.foreground/background)
- Gray ramp darkened for better readability on dark variants
- Notification border colors

## [1.0.0] - 2026-02-24

### Added

- Ayu Mirage Islands dark theme
- Editor color scheme with HCL, Go, Python, JS/TS, Markdown, YAML, JSON support
- Full Islands Dark UI with compact mode
- Terminal color palette
- VCS and diff gutter colors
- Project color gradients (nine groups)
