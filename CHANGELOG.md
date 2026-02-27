# Changelog

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
- Each variant in two sub-themes: classic UI base + Islands UI
- 6 themes total: Mirage, Dark, Light x (classic, Islands)

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
