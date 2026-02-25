# Ayu Islands

Three Ayu color palettes with modern Islands UI for JetBrains IDEs.

## Variants

| Variant | Background | Accent | parentTheme |
|---------|-----------|--------|-------------|
| **Ayu Islands Mirage** | `#1F2430` warm dark | `#FFCC66` yellow | ExperimentalDark / Islands Dark |
| **Ayu Islands Dark** | `#0D1017` deep dark | `#E6B450` gold | ExperimentalDark / Islands Dark |
| **Ayu Islands Light** | `#FAFAFA` warm white | `#F29718` orange | IntelliJ / ExperimentalLight |

Each variant ships as two sub-themes: a classic UI base and an Islands UI version with rounded panels, island gaps, and compact mode support.

## Features

- **6 themes** — Mirage, Dark, Light, each in classic and Islands UI
- **Canonical ayu-colors palettes** — syntax colors from [ayu-theme/ayu-colors](https://github.com/ayu-theme/ayu-colors)
- **15+ language highlights** — HCL/Terraform, Go, Python, JavaScript/TypeScript, Markdown, YAML, JSON, HTML, CSS, XML
- **VCS integration** — tuned diff gutters, file status colors, terminal palette
- **Project gradients** — 9 color groups per variant

## Install

**From JetBrains Marketplace:**

Settings → Plugins → Marketplace → search "Ayu Islands" → Install

**Manual:**

Download `.zip` from [Releases](https://github.com/barad1tos/ayu-jetbrains/releases) → Settings → Plugins → ⚙ → Install from Disk

## Build

```bash
./gradlew buildPlugin
```

Output: `build/distributions/ayu-jetbrains-<version>.zip`

Test in a sandboxed IDE:
```bash
./gradlew runIde
```

## Roadmap

- [x] Ayu Dark Islands variant
- [x] Ayu Light Islands variant
- [ ] Java/Rust/Go/SQL language-specific highlighting
- [ ] Cross-variant quality validation

## License

[BSL-1.1](LICENSE) — source-available, converts to Apache 2.0 on 2030-02-24.
