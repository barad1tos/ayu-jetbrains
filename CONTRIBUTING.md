# Contributing to Ayu Islands

Thanks for your interest in contributing! Whether it's a bug fix, a new language highlight, or a color tweak — all contributions are welcome.

By participating, you agree to uphold our [Code of Conduct](.github/CODE_OF_CONDUCT.md).

## Getting Started

1. Fork the repository and clone it locally
2. Make sure you have **JDK 21+** installed
3. Build the plugin:
   ```bash
   ./gradlew buildPlugin
   ```
4. Launch a sandboxed IDE to test your changes:
   ```bash
   ./gradlew runIde
   ```

## Project Structure

| Path                                     | What it does                                                |
|------------------------------------------|-------------------------------------------------------------|
| `src/main/resources/themes/*.theme.json` | UI themes (colors, Islands UI config, component styling)    |
| `src/main/resources/themes/*.xml`        | Editor color schemes (syntax highlighting, terminal, VCS)   |
| `src/main/resources/META-INF/plugin.xml` | Plugin descriptor and theme registration                    |
| `src/main/kotlin/dev/ayuislands/`        | Kotlin runtime code (accent colors, settings, glow effects) |
| `build.gradle.kts`                       | Gradle build configuration                                  |

## Theme File Conventions

Each color variant (Mirage, Dark, Light) has **two** `.theme.json` files:
- Base variant (e.g. `ayu-islands-mirage.theme.json`)
- Island UI variant (e.g. `ayu-islands-mirage-islands.theme.json`)

**Always edit both files** when making color changes — users may have either variant active.

The `.theme.json` controls UI chrome (panels, toolbars, tabs). The `.xml` controls editor text (syntax, terminal, diffs). They're linked via `"editorScheme"` in the JSON file.

## Color System

The `"colors"` block in `.theme.json` defines named tokens. All UI properties reference these tokens — never use raw hex values in the `ui` section.

Two foreground tiers:
- `ForegroundDefault` — muted, for general UI text
- `ForegroundBright` — bright, for overlays and selected states

## Submitting Changes

1. Create a feature branch: `git checkout -b feat/your-change`
2. Make your changes and verify with `./gradlew buildPlugin`
3. Test visually in `./gradlew runIde`
4. Commit using [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `chore:`)
5. Open a Pull Request against `main`

## What Makes a Good PR

- Focused on one change (don't mix unrelated fixes)
- Includes before/after screenshots for visual changes
- Passes `./gradlew buildPlugin` and `./gradlew verifyPlugin`
- Edits both `.theme.json` files per variant when applicable

## License

By contributing, you agree that your contributions will be licensed under [BSL-1.1](LICENSE).
