# Ayu Islands — Project Instructions

> Project-specific instructions. See global `~/.claude/CLAUDE.md` for universal rules. Supplement, never contradict.

## Project Purpose

JetBrains IDE color theme plugin (IntelliJ Platform). Provides the "Ayu Islands" dark theme for all JetBrains IDEs (IntelliJ IDEA, PyCharm, WebStorm, etc.), based on the Ayu color palette with "Islands" variant styling. Supports both dark base and Islands accent variants.

## Tech Stack

- **Language**: Kotlin (plugin logic), JSON (theme definitions)
- **Build tool**: Gradle with Kotlin DSL (`build.gradle.kts`)
- **Platform SDK**: IntelliJ Platform Plugin SDK (Gradle IntelliJ Plugin)
- **Linting**: Detekt (static analysis) + ktlint (formatting)
- **Distribution**: JetBrains Marketplace

## Build Commands

```bash
# Build the plugin
./gradlew build

# Run plugin in sandbox IDE (for manual testing)
./gradlew runIde

# Verify plugin compatibility with IDE versions
./gradlew verifyPlugin

# Run linter checks
./gradlew detekt ktlintCheck

# Apply ktlint formatting
./gradlew ktlintFormat
```

## Theme File Patterns

- Theme definitions in `.theme.json` files — one per variant (base + islands)
- Always edit BOTH `.theme.json` files when changing colors or adding UI elements
- Color variables are cross-referenced between theme files for consistency

## Execution Style

- When asked to implement or execute, do NOT re-analyze or re-verify the plan. Start concrete implementation steps immediately unless explicitly asked to review first.
- Be concise. No verbose tables, lengthy option lists, or excessive explanation. Get to the point and execute.
- When fixing linter/detekt/CI issues, fix the actual code. Never raise thresholds or suppress warnings as a workaround unless explicitly approved.
- Emojis sparingly — 1-2 per piece of content to accent key moments, never as bullet decorators on every line.

## Git Workflow

- Never push directly to main. Always create a feature branch and open a PR.
- Use `/opt/homebrew/bin/gh` for GitHub CLI (see global CLAUDE.md for why).

## Changelog (plugin.xml change-notes)

Changelog entries are **user-facing only**. Describe what changed FOR THE USER.

**Allowed:** "Glow now renders on floating tool windows", "Settings adapt to active theme type"
**Forbidden:** implementation details, API names, class renames, refactors, "fix compilation errors", "correct API usage", "rewrite internals"

## Releases

- Always run `./gradlew verifyPlugin` before release and review `build/reports/pluginVerifier/`.
- `release-date` is locked per `release-version`. See memory for full rules.
- After feature completion, always offer `/deploy-to-ide`.

## Theme Files

- Always edit BOTH `.theme.json` files per variant (base + islands).
- All new features are premium by default.

## Linter Gate

- Run `./gradlew detekt ktlintCheck` after every logical code change, not just at completion.
- When `/code-conventions` or any review skill suggests a fix, verify the fix passes detekt/ktlint before considering it done.
- Thresholds live in `detekt.yml` — single source of truth. See `intellij-plugin-dev` skill `references/linter-rules.md` for practical guidance.

## Build Cache

Gradle incremental compilation cache survives `clean` and `--no-build-cache`. Always `rm -rf build .gradle` before building when debugging bytecode issues. Verify with `javap -c -p`.

## Testing Checklist

1. `grep "SEVERE.*Ayu" idea.log` — no crashes
2. `grep "accent applied" idea.log` — successful apply logged
3. Verify active theme name matches the `.theme.json` you edited
4. `javap -c` the installed JAR to confirm new code
