# Changelog

## [2.1.2] - 2026-03-08

### Added
- [Free] Reintroduce non-Islands (Classic UI) theme variants for Mirage, Dark, and Light
- [Free] Settings controls adapt to active theme type (Islands UI vs Classic)
- [Paid] Glow overlay now renders below tab strip for visual harmony with underline
- [Paid] Tab underline thickness and glow-sync controls for Classic UI

### Changed
- [Free] Updated plugin description with clearer free/premium feature breakdown
- Raised detekt object function threshold to 20

### Fixed
- [Free] License enforcement in dynamic settings state updates

## [2.1.1] - 2026-03-07

### Added
- [Paid] "Highlight indent errors" toggle for Indent Rainbow — disable to blend continuation lines into gradient

### Fixed
- [Paid] Fix write-unsafe EDT context error on startup with Indent Rainbow integration

## [2.1.0] - 2026-03-07

### Added
- [Paid] Indent Rainbow integration: accent-colored indent guides with 4 intensity presets (Whisper, Ambient, Neon, Cyberpunk)
- [Paid] Bracket scope gutter highlight for matched braces
- [Free] Accent-colored matched bracket styling
- [Free] New Integrations settings panel for third-party plugin sync

### Changed
- CI: remove redundant push trigger to eliminate duplicate runs on merge

## [2.0.1] - 2026-03-06

### Fixed
- Fix crash on theme switch: `setAttributes` received null in `revertAlwaysOnEditorKeys`
- Fix crash on theme switch: `repaintAllWindows` during `revertAll` triggered NPE in `HeaderToolbarButtonLook` because UI keys were cleared before the new theme loaded
- Replace deprecated `PluginDescriptor.isEnabled` with `PluginManagerCore.isDisabled()`
- Replace deprecated `ActionUtil.performActionDumbAwareWithCallbacks` with `ActionUtil.invokeAction`

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
- Add missing Marketplace changelog for v1.2.0 changes

## [1.2.0] - 2026-02-27

### Added
- Accent-colored active tab underline, caret, and focus borders for all variants
- Accent color defaults for editor selections, search highlights, and breadcrumbs
- Kotlin 2.1.10 JVM toolchain build support

### Changed
- CI build steps labeled for clarity
- Release workflow now has `contents: write` permission for GitHub Releases

### Fixed
- README color swatches and language list corrections

## [1.1.0] - 2026-02-26

### Added
- C#/.NET syntax highlighting (Rider/ReSharper) for all three variants
- Dart, Ruby, PHP, Scala, Kotlin language-specific highlighting
- Rust, Bash/Shell, SQL, Regular Expressions language-specific highlighting
- 20+ languages with hand-tuned syntax tokens

### Changed
- Upgraded language highlight count from 15+ to 20+
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
