# Changelog

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
