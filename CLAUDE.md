# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Ayu Islands is a JetBrains IDE theme plugin providing Ayu color variants with Islands UI. Theme files (JSON/XML) plus Kotlin runtime code for accent color customization.

License: BSL-1.1 (converts to Apache 2.0 on 2030-02-24).

## Build Commands

```bash
./gradlew buildPlugin      # Build distribution ZIP → build/distributions/
./gradlew runIde           # Launch sandboxed IDE with plugin loaded (manual testing)
./gradlew verifyPlugin     # Verify against IC-2025.1, IC-2025.2.3, IU-2025.3.3, PS/WS/CL-2025.3.3
./gradlew publishPlugin    # Publish to Marketplace (requires PUBLISH_TOKEN env var)
./gradlew test             # Run unit tests (excludes integration tests)
./gradlew koverVerify      # Enforce 80% line coverage minimum
```

Automated tests are not required during active development. Default validation: `./gradlew buildPlugin` succeeds + manual behavior verification in `runIde`. Add automated tests only when explicitly requested.

## Architecture

### Two-File Theme System

Each theme variant consists of exactly two files:

1. **`.theme.json`** — UI theme: colors palette, Islands UI config (arc radius, border width, gaps), component styling for 750+ UI properties
2. **`.xml`** — Editor color scheme: syntax highlighting per language, terminal colors, VCS/diff colors, console output colors

They are linked: the `.theme.json` references its editor scheme via `"editorScheme": "/themes/AyuIslandsMirage.xml"`.

### Registration

Themes are registered in `src/main/resources/META-INF/plugin.xml` as `<themeProvider>` extensions. Each variant gets one `<themeProvider>` entry pointing to its `.theme.json`.

### Current variants

- **Ayu Islands Mirage** (dark): `ayu-islands-mirage.theme.json` + `AyuIslandsMirage.xml`
- **Ayu Islands Mirage (Islands UI)**: `ayu-islands-mirage-islands.theme.json` + `AyuIslandsMirage.xml`
- **Ayu Islands Dark**: `ayu-islands-dark.theme.json` + `AyuIslandsDark.xml`
- **Ayu Islands Dark (Islands UI)**: `ayu-islands-dark-islands.theme.json` + `AyuIslandsDark.xml`
- **Ayu Islands Light**: `ayu-islands-light.theme.json` + `AyuIslandsLight.xml`
- **Ayu Islands Light (Islands UI)**: `ayu-islands-light-islands.theme.json` + `AyuIslandsLight.xml`

Each color variant has two UI sub-variants. Dark variants use `ExperimentalDark`/base parent; Light variants use `ExperimentalLight`/`IntelliJ` parent. **Always apply changes to BOTH `.theme.json` files per variant.**

### Color System in .theme.json

The `"colors"` block defines named tokens (e.g., `"Gray1.5": "#1F2430"`, `"ForegroundDefault": "#CCCAC2"`). Other tokens can reference these by name (e.g., `"BackgroundDark": "Gray1.5"`). All UI properties in the file reference these semantic tokens — never use raw hex values in UI sections.

The `"parentTheme": "ExperimentalDark"` provides the Islands Dark base; overrides are layered on top.

### Color Scheme XML Structure

- Dark variants: `parent_scheme="Darcula"` — inherits from Darcula, overrides specific attributes
- Light variant: `parent_scheme="Default"` — inherits from Default (IntelliJ light), overrides specific attributes
- Attributes use FOREGROUND, BACKGROUND, FONT_TYPE (1=bold, 2=italic, 3=bold+italic), EFFECT_TYPE, EFFECT_COLOR
- Language-specific scopes: Java, Kotlin, Scala, C#/.NET, Rust, Go, Python, JS/TS, Ruby, PHP, Dart, HCL/Terraform, HTML, CSS, XML, JSON, YAML, Bash, Markdown, Django Templates, RegExp

## Key Files

| File | Purpose |
|------|---------|
| `gradle.properties` | Plugin version, sinceBuild, platform version — edit here for version bumps |
| `src/main/resources/META-INF/plugin.xml` | Plugin descriptor, theme registration, marketplace metadata |
| `src/main/resources/themes/ayu-islands-mirage.theme.json` | Mirage UI theme (ExperimentalDark parent) |
| `src/main/resources/themes/ayu-islands-mirage-islands.theme.json` | Mirage UI theme (Islands Dark parent) |
| `src/main/resources/themes/AyuIslandsMirage.xml` | Mirage editor color scheme |
| `src/main/resources/themes/ayu-islands-dark.theme.json` | Dark UI theme (ExperimentalDark parent) |
| `src/main/resources/themes/ayu-islands-dark-islands.theme.json` | Dark UI theme (Islands Dark parent) |
| `src/main/resources/themes/AyuIslandsDark.xml` | Dark editor color scheme |
| `src/main/resources/themes/ayu-islands-light.theme.json` | Light UI theme (IntelliJ parent) |
| `src/main/resources/themes/ayu-islands-light-islands.theme.json` | Light UI theme (ExperimentalLight parent) |
| `src/main/resources/themes/AyuIslandsLight.xml` | Light editor color scheme |
| `build.gradle.kts` | Gradle build with `org.jetbrains.intellij.platform` v2.11.0 |

## Adding a New Theme Variant

1. Create `src/main/resources/themes/<name>.theme.json` with `parentTheme`, `colors`, and UI overrides
2. Create `src/main/resources/themes/<Name>.xml` with `parent_scheme` and attribute overrides
3. Link them: set `"editorScheme"` in the `.theme.json` to point to the `.xml`
4. Register in `plugin.xml`: add a new `<themeProvider>` with unique `id` and `path`

## CI/CD

- **build.yml**: runs on push to `main` + PRs — `buildPlugin` + `verifyPlugin`, uploads ZIP artifact
  - **Analyze** job: `./gradlew ktlintCheck detekt compileKotlin -Pkotlin.compiler.option.allWarningsAsErrors=true`
  - **theme-consistency** job (Tier 1): runs `scripts/check-consistency.py` (XML cross-variant) and `scripts/check-json-consistency.py` (JSON pair consistency)
- **release.yml**: triggers on `v*` tags — builds, verifies, publishes to Marketplace, creates GitHub Release
- Required secret: `JETBRAINS_MARKETPLACE_TOKEN`

## Pre-commit Hooks

Hooks run via `prek` (pre-commit Rust reimplementation) through the global `core.hooksPath` → `~/.config/codex-hooks/pre-commit`. Config: `.pre-commit-config.yaml`.

**Active hooks on Kotlin files:**
- `ktlint` — code style (function signature formatting, import ordering)
- `detekt` — static analysis (MagicNumber, complexity, naming)
- `no-commit-to-branch` — blocks direct commits to `main`

**Do NOT run `pre-commit install`** — it conflicts with global `core.hooksPath`. Hooks are already wired through `prek`. To test hooks manually: `prek run --hook-stage pre-commit`.

**Before committing Kotlin changes**, always run: `./gradlew ktlintCheck detekt` to catch issues early. CI runs these with `-Pkotlin.compiler.option.allWarningsAsErrors=true` which is stricter than local defaults.

## Gotchas (Lessons Learned)

### Always change BOTH .theme.json files per variant

Each color variant (Mirage, Dark) has TWO UI sub-variants: one with `parentTheme: "ExperimentalDark"` and one with `parentTheme: "Islands Dark"`. The user may have either active. **If you only edit one file, the change will be invisible if the user is on the other sub-variant.** This was the root cause of hours of debugging where changes "didn't work".

### Testing theme changes in a running IDE

1. **Always delete BOTH `build/` AND `.gradle/`** — Gradle's `.gradle/` directory caches incremental Kotlin compilation that survives `clean`, `--no-build-cache`, and `--rerun-tasks`. Only `rm -rf .gradle` guarantees a fresh compile.
2. Build and install:
   ```bash
   rm -rf build .gradle
   ./gradlew buildPlugin --no-build-cache --rerun-tasks
   ```
3. **Verify bytecode before deploying** — `javap -c -p <Class>.class | grep -c "<ExpectedSymbol>"` on the built JAR. If count is 0, the build used stale cache.
4. Install directly to plugins dir and restart:
   ```bash
   rm -rf "$PYCHARM_PLUGINS/ayu-jetbrains"
   unzip -o build/distributions/*.zip -d "$PYCHARM_PLUGINS/"
   pkill -f "PyCharm" && sleep 3 && open -a "PyCharm"
   ```
   Where `PYCHARM_PLUGINS` = `~/Library/Application Support/JetBrains/PyCharm<version>/plugins`
3. If changes still invisible, delete cached editor schemes:
   ```bash
   rm -f "$PYCHARM_COLORS/_@user_Ayu Islands Dark.icls"
   ```
   Where `PYCHARM_COLORS` = `~/Library/Application Support/JetBrains/PyCharm<version>/colors`

### Use diagnostic markers to verify theme loading

When debugging, set a UI element to an unmissable color (e.g. `StatusBar.background: "#FF6A00"` — bright orange). If the marker doesn't appear after install+restart, the theme file isn't being loaded.

### .theme.json controls UI chrome, .xml controls editor text

- **`.theme.json`** → UI elements: tables, lists, tool windows, popups, status bar
- **`.xml` editor scheme** → code syntax, terminal, diff colors, inline annotations
- **Git Log** commit text uses `Table.foreground` from `.theme.json` (NOT the XML scheme)
- PyCharm caches editor schemes as `_@user_*.icls` files in the `colors/` directory — these override plugin XML

### ProjectView hide-path uses Registry, NOT ProjectViewState

- **`ProjectViewImpl.isShowURL()`** reads directly from `Registry.is("project.tree.structure.show.url")` — confirmed by bytecode decompilation of `app-client.jar` in IC-2025.2
- `ProjectViewState.showURL` is **NOT read** by the Project View renderer — it's a separate per-project persistence that doesn't affect rendering
- **Registry keys persist** across IDE restarts in `options/ide.general.xml` — always use `Registry.get(key).resetToDefault()` for cleanup, never track originals in instance fields (fields die on restart, Registry survives)
- VCS annotations (branch name, file count) are handled by `RootFilteringRenderer` wrapper — separate from path visibility
- **Always decompile with `javap -c`** before trusting SO/docs about JetBrains internal APIs: `jar xf app-client.jar com/intellij/.../TargetClass.class && javap -c TargetClass.class`

### AccentApplicator runtime rules

- **NEVER use `SwingUtilities.updateComponentTreeUI()`** — triggers ActionToolbar.updateUI → "Slow operations on EDT" SEVERE crash. Use `window.repaint()` instead
- `ProjectActivity.execute()` runs on a background coroutine — wrap EDT operations in `SwingUtilities.invokeLater`
- When UIManager changes "don't work": check `idea.log` for SEVERE **before** debugging keys or theme files
- **Editor scrollbars bypass UIManager** — `OpaqueAwareScrollBar` installs `ColorKey.FUNCTION_KEY` that resolves colors from `EditorColorsScheme.getColor()` instead of UIManager. Must set scrollbar colors in BOTH UIManager (tool windows) AND `EditorColorsManager.getInstance().globalScheme.setColor(ColorKey.find(key), color)` (editor)

### Tool window auto-fit architecture

- `ToolWindowAutoFitter` (in `dev.ayuislands.toolwindow`) is a reusable utility: pass `toolWindowId`, `minWidth`, and `maxWidthProvider` lambda. Used by both `ProjectViewScrollbarManager` and `CommitPanelAutoFitManager`.
- To add auto-fit for a new tool window: create a `@Service(Service.Level.PROJECT)` that instantiates `ToolWindowAutoFitter`, subscribes to `ToolWindowManagerListener`, and registers in `plugin.xml` + `AyuIslandsStartupActivity`.
- Known tool window IDs: `"Project"` (min 253px), `"Commit"` (min 269px), `"Version Control"` (min 269px). Min widths are empirical — measured from toolbar icon overlap threshold.
- **Git tool window ID is `"Version Control"`, not `"Git"`** — despite the UI label showing "Git", `ToolWindowManager.getInstance(project).getToolWindow("Git")` returns null. Always use `"Version Control"` as the ID.
- **`Splitter.firstComponent`/`secondComponent` can be null** — tool window panels are lazy-loaded. When traversing a `Splitter` hierarchy (e.g. Git panel's branches/files split), always null-check both components. They may not exist until the user expands that section.

### macOS path gotchas

- Zsh glob with `~` fails when path contains spaces (`~/Library/Application Support/JetBrains/*/plugins/` → "no matches found"). Use `find "$HOME/Library" ...` or explicit `/Users/cloud/...` with double quotes instead.

### Verify installed JAR matches source

`unzip -o` can silently fail to overwrite. After installing plugin to PyCharm, verify bytecode:
```bash
cd /tmp && jar xf "$PYCHARM_PLUGINS/ayu-jetbrains/lib/ayu-jetbrains-*.jar" dev/ayuislands/accent/AccentApplicator.class && javap -c -p dev/ayuislands/accent/AccentApplicator.class | grep -A2 "keyword"
```

### Color token system

Two foreground tiers:
- `ForegroundDefault` (#9DA5B0) — muted, for general UI text including tables
- `ForegroundBright` (#BFBDB6) — bright, for overlays (popups, tooltips, notifications, selected states)

All UI foreground values should reference these tokens, not hardcoded hex. The only exception is `TrialWidget.Default.borderColor` (border, not foreground).

## Changelog Rules (plugin.xml, CHANGELOG.md, UpdateNotifier)

- **User-facing only** — changelog entries describe what changes FOR THE USER, not internal code details
- No implementation details (API names, class renames, refactors, code centralization) — users don't care
- No "fix compilation errors", "centralize X into Y", "rewrite internals" — invisible to users
- Good: "Fix glow color desyncing from accent after rotation"
- Bad: "Centralize glow sync into GlowOverlayManager.syncGlowForAllProjects()"

## Development Philosophy

- **New features are premium by default** — the free tier (6 themes, accent presets, font presets) is already generous. All new functionality should be gated behind `LicenseChecker.isLicensedOrGrace()` unless the user explicitly marks it as free. This applies to new settings, integrations, and UI enhancements alike.
- Breaking changes are acceptable when they improve architecture and code clarity
- Prefer direct refactors over compatibility shims, fallback paths, or migration layers unless explicitly requested
- Automated tests only when explicitly requested — default validation is manual + successful build
- **Coverage excludes must be justified** — only exclude truly untestable classes (IDE singletons like `EditorColorsManager`, pure Swing rendering panels). Domain logic adjacent to IDE glue must be extracted into testable files, not excluded.
- **NEVER use `@Suppress` to hide lint/detekt warnings** — fix the root cause instead (extract constants, restructure code, etc.). The only exception is when the user explicitly asks for a suppression.
- **Avoid releasing with deprecated or experimental API warnings.** Before every release, run `./gradlew verifyPlugin` and review `deprecated-usages.txt` / `experimental-api-usages.txt` in `build/reports/pluginVerifier/`. Replace deprecated APIs with their non-deprecated alternatives. When replacing, verify the alternative exists in the target `platformVersion` (decompile with `javap` from Gradle cache). Experimental API usage (`UIThemeLookAndFeelInfo`, `LafManager.getInstalledThemes`) is unavoidable — it's the core theme API with no alternative.

## Git Workflow

- **Never commit directly to `main`** — pre-commit hook enforces this
- All work happens on feature branches (e.g. `feat/issue-templates`, `fix/scrollbar-color`)
- Merge to `main` only through Pull Requests
- Release tags (`v*`) are created on `main` after PR merge

## Conventions

- Commit messages: conventional commits (`feat:`, `fix:`, `chore:`)
- Java 21 target (required by platform 2025.1+)
- `buildSearchableOptions` is disabled (accent settings use runtime application, not indexed options)
- `untilBuild` is explicitly null — plugin supports future IDE versions without upper bound
- **Never commit `.planning/` to the repository** — planning/state docs (ROADMAP.md, STATE.md, PLAN.md, SUMMARY.md, etc.) are local-only and excluded via global gitignore. Never use `git add -f` to force-add them.
